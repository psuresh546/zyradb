# ZyraDB Technical Documentation

This document explains how ZyraDB works internally and how the current codebase is organized. It is meant to be a clear technical reference for reviewers, contributors, and future maintenance.

## 1. Overview

ZyraDB is a Redis-inspired in-memory key-value database written in Java 21 with Spring Boot used for application bootstrapping. It does not expose HTTP or REST APIs. Clients connect over raw TCP and exchange simple line-based text commands.

Current capabilities:

- key-value storage in memory
- concurrent TCP clients
- `SET`, `GET`, `DEL`, `EXPIRE`, `TTL`, `INFO`, `QUIT`
- TTL expiration with passive reads and scheduled cleanup
- write-ahead logging
- snapshot persistence
- restart recovery by snapshot load plus WAL replay
- graceful shutdown

The project is intentionally compact. It focuses on the mechanics of a storage engine rather than a large external feature set.

## 2. High-Level Architecture

```text
                +----------------------+
                |        Client        |
                +----------+-----------+
                           |
                           v
                +----------------------+
                |      TCPServer       |
                |  TCP socket server   |
                +----------+-----------+
                           |
                           v
                +----------------------+
                |    CommandParser     |
                | tokenizes input line |
                +----------+-----------+
                           |
                           v
                +----------------------+
                |   KeyValueService    |
                | command validation   |
                | and execution        |
                +----+------------+----+
                     |            |
          write path |            | read path
                     v            v
            +---------------------------+
            |       InMemoryStore       |
            | keys, values, TTL metadata|
            +-------------+-------------+
                          |
        +-----------------+-----------------+
        |                                   |
        v                                   v
+-------------------+             +-------------------+
|   WriteAheadLog   |             |  ExpiryScheduler  |
| mutation durability|            | background cleanup|
+-------------------+             +-------------------+
                          |
                          v
                 +-------------------+
                 |  SnapshotManager  |
                 | snapshot + WAL    |
                 | compaction        |
                 +-------------------+
```

In practical terms:

- `TCPServer` accepts and serves TCP connections
- `CommandParser` turns a text line into a structured command
- `KeyValueService` validates and executes command semantics
- `InMemoryStore` holds values and TTL metadata
- `WriteAheadLog` protects recent mutations and is coordinated by the service layer
- `SnapshotManager` saves compact full-state snapshots
- `ExpiryScheduler` periodically removes expired keys

## 3. Main Components

### 3.1 Application Bootstrap

Relevant file: [src/main/java/com/zyra/ZyraDbApplication.java](../src/main/java/com/zyra/ZyraDbApplication.java)

The application startup sequence is:

1. Configure WAL sync mode and interval.
2. Load the snapshot if present.
3. Replay the WAL.
4. Start the expiry scheduler.
5. Start the TCP server.

The shutdown hook performs the reverse coordination:

1. Stop the scheduler.
2. Stop the TCP server.
3. Save a snapshot.
4. Close WAL resources.

If snapshot creation fails, the WAL is intentionally preserved.

### 3.2 TCP Server

Relevant file: [src/main/java/com/zyra/tcp/TCPServer.java](../src/main/java/com/zyra/tcp/TCPServer.java)

The TCP server:

- listens on port `6380` by default
- accepts multiple client connections
- handles each client with a cached thread-pool worker
- reads newline-delimited UTF-8 commands
- writes newline-delimited responses

Recent behavior worth noting:

- client sockets enable `TCP_NODELAY`
- responses are buffered and flushed in small batches
- flush happens on `BYE`, after 16 buffered responses, after 4096 buffered characters, or when the reader has no immediately ready input

This keeps the protocol simple while improving throughput for pipelined or bursty clients.

### 3.3 Command Parser

Relevant file: [src/main/java/com/zyra/parser/CommandParser.java](../src/main/java/com/zyra/parser/CommandParser.java)

The parser:

- trims leading and trailing whitespace
- extracts the command name
- tokenizes the remaining input on whitespace
- normalizes command names to uppercase

Examples:

```text
SET user alice
GET user
SET token abc EX 30
```

Important limitation:

- the parser is whitespace-delimited
- quoted values are not supported
- values containing spaces are not currently accepted

### 3.4 Service Layer

Relevant file: [src/main/java/com/zyra/service/KeyValueService.java](../src/main/java/com/zyra/service/KeyValueService.java)

`KeyValueService` is the semantic layer for command execution. It:

- validates argument count and syntax
- formats consistent client responses
- coordinates write ordering across the WAL and in-memory state

Supported responses follow a small, explicit contract:

- `OK` for successful writes
- `VAL <value>` for reads
- `NIL` for missing values
- `INT <n>` for integer-style responses
- `ERR ...` for invalid input or internal failure

### 3.5 In-Memory Store

Relevant file: [src/main/java/com/zyra/store/InMemoryStore.java](../src/main/java/com/zyra/store/InMemoryStore.java)

The store keeps each key as:

```text
key -> ValueWrapper(value, expiryTime)
```

Where:

- `expiryTime = -1` means no expiration
- any non-negative timestamp is an absolute expiry time in epoch milliseconds

The current implementation uses:

- `ConcurrentHashMap<String, ValueWrapper>` for the live key space
- `64` striped `ReentrantLock`s for key-scoped mutation coordination
- a whole-store write barrier that temporarily acquires every stripe for snapshot save/load and WAL replay

### 3.6 Write-Ahead Log

Relevant file: [src/main/java/com/zyra/store/WriteAheadLog.java](../src/main/java/com/zyra/store/WriteAheadLog.java)

The WAL records mutations before they are relied on for recovery.

Current behavior:

- `SET` and `DEL` are appended to `zyra.wal`
- keys and values are Base64-encoded
- replay skips malformed entries rather than aborting startup
- pending writes are forced before replay and shutdown

The WAL supports two sync modes:

- `always`: every mutation is forced to disk immediately
- `periodic`: writes are buffered in memory and forced by a background task at a configured interval

The default runtime configuration is:

```properties
zyra.wal.sync-mode=periodic
zyra.wal.force-interval-ms=100
```

This is a throughput-oriented default. It improves write performance at the cost of a small crash window compared with `always`.

Recent implementation details worth noting:

- replay snapshots WAL lines before taking the store-wide write barrier, which avoids lock-order deadlocks with live mutations
- periodic mode batches multiple entries into one flush and `channel.force(...)` call, which materially improves mixed-workload throughput

### 3.7 Snapshot Manager

Relevant file: [src/main/java/com/zyra/store/SnapshotManager.java](../src/main/java/com/zyra/store/SnapshotManager.java)

Snapshots are used to keep restart time bounded.

The save flow is:

1. Take a copy of the live in-memory state.
2. Write it to `zyra.snapshot.tmp`.
3. Flush the temp file.
4. Move it into place as `zyra.snapshot`.
5. Reset the WAL.

The load flow is:

1. Open `zyra.snapshot` if it exists.
2. Decode each line.
3. Restore each valid entry into the store.
4. Skip malformed lines and continue.

The move step attempts an atomic replace first and falls back to a normal replace if necessary.

### 3.8 Expiry Scheduler

Relevant file: [src/main/java/com/zyra/scheduler/ExpiryScheduler.java](../src/main/java/com/zyra/scheduler/ExpiryScheduler.java)

Expired keys are handled in two ways:

- passive expiration during `GET` and `TTL`
- active cleanup every 5 seconds by the scheduler

The scheduler keeps stale keys from accumulating when no client reads them again.

## 4. Protocol and Commands

ZyraDB uses a simple line-based TCP protocol:

- one command per line
- one response per line
- UTF-8 text
- command names are case-insensitive

Supported commands:

| Command | Meaning | Example | Response |
| --- | --- | --- | --- |
| `SET key value` | store value | `SET user alice` | `OK` |
| `SET key value EX seconds` | store value with TTL | `SET token abc EX 30` | `OK` |
| `GET key` | read value | `GET user` | `VAL <value>` or `NIL` |
| `DEL key` | delete key | `DEL user` | `INT 1` or `INT 0` |
| `EXPIRE key seconds` | apply TTL to existing key | `EXPIRE token 30` | `INT 1` or `INT 0` |
| `TTL key` | read remaining TTL | `TTL token` | `INT <seconds>`, `INT -1`, or `INT -2` |
| `INFO` | return lightweight metadata | `INFO` | `INFO keys=<count> uptime=<seconds>` |
| `QUIT` | close client session | `QUIT` | `BYE` |

TTL semantics:

- `INT -1`: key exists and has no expiry
- `INT -2`: key does not exist or is expired
- `INT N`: approximately `N` seconds remain

## 5. Concurrency Model

The current concurrency model is layered.

### Read path

Point reads use the concurrent map directly:

```text
lookup key
check expiry
remove expired entry lazily if needed
return live value or missing
```

This keeps common reads lightweight.

### Write path

Mutating commands are coordinated in `KeyValueService`:

```text
acquire key stripe lock
append WAL entry
apply in-memory mutation
release key stripe lock
```

Whole-store maintenance operations such as snapshot save/load and WAL replay acquire every stripe in a fixed order instead. Expired-key cleanup only locks the specific key it is removing.

Benefits of this design:

- unrelated keys can mutate concurrently
- whole-store operations still have a clear exclusion boundary
- the WAL append and in-memory mutation stay within the same logical critical section

This is a better fit than a single coarse lock while still being easy to reason about.

## 6. Persistence and Recovery

ZyraDB uses a standard two-part recovery model:

```text
snapshot base state + WAL tail
```

### Startup recovery

On startup:

1. Load the snapshot.
2. Replay the WAL.
3. Start accepting new work only after recovery is complete.

### Normal shutdown

On shutdown:

1. Stop background cleanup.
2. Stop new TCP traffic.
3. Save a fresh snapshot.
4. Close and flush WAL resources.

### Corruption tolerance

Both recovery paths are tolerant of bad input:

- malformed WAL lines are skipped
- malformed snapshot lines are skipped

The goal is to recover as much valid state as possible instead of failing startup because of one bad trailing record.

## 7. Example Flows

### 7.1 `SET user alice EX 30`

```text
client sends command
TCPServer reads line
CommandParser creates Command
KeyValueService validates syntax
KeyValueService acquires the key lock
WriteAheadLog appends mutation
InMemoryStore stores value with expiry timestamp
TCPServer returns OK
```

### 7.2 `GET user`

```text
client sends command
TCPServer reads line
CommandParser creates Command
KeyValueService calls store.get(...)
InMemoryStore returns live value or removes expired data lazily
TCPServer returns VAL alice or NIL
```

### 7.3 Restart recovery

```text
load zyra.snapshot
replay zyra.wal
start scheduler
start TCP server
```

## 8. Testing

Relevant test areas:

- [src/test/java/com/zyra/parser](../src/test/java/com/zyra/parser)
- [src/test/java/com/zyra/service](../src/test/java/com/zyra/service)
- [src/test/java/com/zyra/store](../src/test/java/com/zyra/store)
- [src/test/java/com/zyra/stress](../src/test/java/com/zyra/stress)
- [src/test/java/com/zyra/tcp](../src/test/java/com/zyra/tcp)

The suite covers:

- parser behavior
- service validation
- TTL semantics
- concurrency behavior
- WAL replay
- periodic WAL sync behavior
- snapshot persistence
- restart recovery and TCP lifecycle behavior
- multi-threaded burn-in coverage
- end-to-end TCP flows

Local verification on April 24, 2026:

- `37` tests passed
- Maven reported `BUILD SUCCESS`
- total test time: `42.200s`

Run locally:

```powershell
.\mvnw.cmd test
```

## 9. Benchmarking

Benchmark numbers in this project come from local TCP runs against the packaged ZyraDB jar using a private helper script that is intentionally kept out of version control.

The local benchmark flow:

- launches a separate ZyraDB process
- seeds data when needed
- drives multiple TCP clients
- validates server responses
- reports throughput in ops/sec

This is a local developer benchmark, not a formal performance suite.

Current local benchmark on April 24, 2026 after the latest hot-path optimizations:

| Setting | Throughput |
| --- | --- |
| `PipelineDepth=1` | `7235.20 ops/sec` |
| `PipelineDepth=32` | `12576.88 ops/sec` |

Run configuration:

- `100000` commands
- `8` clients
- `mixed` workload
- `zyra.wal.sync-mode=periodic`
- `zyra.wal.force-interval-ms=100`

In this local run, pipelining improved throughput by about `73.8%`.

## 10. Design Trade-Offs

The current design intentionally favors clarity over maximum sophistication.

### Why a text TCP protocol

- easy to inspect manually
- simple to test
- good fit for an educational engine

Trade-off:

- not compatible with existing Redis tooling

### Why `ConcurrentHashMap` plus striped locks

- better concurrency for unrelated keys
- simpler than full transactional coordination
- still compatible with whole-store maintenance locks

Trade-off:

- more coordination logic than a single lock

### Why WAL plus snapshot

- fast mutable state stays in memory
- recent writes remain recoverable
- restart cost stays bounded

Trade-off:

- more moving parts than memory-only storage

### Why periodic WAL sync by default

- better write throughput in local runs
- batching lets one force cover multiple mutations
- useful for development and benchmarking

Trade-off:

- a small durability window exists between background flushes

## 11. Current Limitations

- no quoted or multi-word values
- no RESP compatibility
- no replication
- no authentication
- no eviction policy
- no disk-based storage engine

These are deliberate scope limits for now, not hidden behavior.

## 12. Roadmap

Reasonable next steps for the project:

- RESP-compatible protocol support
- richer benchmarking and profiling
- snapshot scheduling and configurable maintenance
- eviction policies such as LRU or LFU
- replication and follower recovery
- metrics and observability
- authentication and access control

## 13. Summary

ZyraDB is a compact storage-engine project that demonstrates:

- socket-based command processing
- structured parsing
- concurrent in-memory state management
- TTL expiration
- WAL durability
- snapshot-based recovery
- coordinated startup and shutdown

That makes it a good reference project for learning how a small database service is put together in practice.
