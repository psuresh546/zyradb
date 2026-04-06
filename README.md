# ZyraDB

**A Redis-inspired in-memory key-value database built from scratch in Java**

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)
![Protocol](https://img.shields.io/badge/Protocol-TCP-blue)
![Persistence](https://img.shields.io/badge/Persistence-WAL%20%2B%20Snapshot-red)

ZyraDB is a systems-focused backend project that implements the core ideas behind a small database engine: custom TCP command handling, concurrent in-memory storage, write-ahead logging, snapshot persistence, TTL expiration, crash recovery, and graceful shutdown.

The goal of the project is not to clone Redis feature-for-feature, but to understand how a real storage service is stitched together end to end.

For a deeper walkthrough of the internals, see [Detailed Documentation](docs/DETAILED_DOCUMENTATION.md).

## Table of Contents

- [Why ZyraDB](#why-zyradb)
- [Feature Set](#feature-set)
- [Architecture](#architecture)
- [Request Lifecycle](#request-lifecycle)
- [Persistence and Recovery](#persistence-and-recovery)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Supported Commands](#supported-commands)
- [Sample Session](#sample-session)
- [Testing](#testing)
- [Benchmark](#benchmark)
- [Roadmap](#roadmap)
- [License](#license)
- [Disclaimer](#disclaimer)

## Why ZyraDB

Most small backend projects stop at CRUD over HTTP. ZyraDB goes a layer deeper and focuses on storage engine fundamentals:

- TCP-based request handling instead of REST
- command parsing and protocol-style responses
- serialized mutation flow for consistency
- thread-safe in-memory reads and writes
- write-ahead logging for durability
- snapshots for faster restart and WAL compaction
- TTL support with passive and scheduled cleanup
- startup recovery and graceful shutdown behavior

That makes it a strong portfolio project for learning distributed systems and database internals.

## Feature Set

- In-memory key-value storage
- Custom line-based TCP protocol
- `SET`, `GET`, `DEL`, `EXPIRE`, `TTL`, `INFO`, `QUIT`
- TTL support on `SET`
- Passive expiration during reads
- Background expiration cleanup every 5 seconds
- Write-ahead logging to `zyra.wal`
- Snapshot persistence to `zyra.snapshot`
- WAL replay on restart
- Corruption-tolerant recovery for malformed WAL or snapshot entries
- Graceful shutdown with server stop, snapshot save, and WAL close
- Unit and integration tests across parser, store, service, WAL, and TCP flow

## Architecture

ZyraDB is organized as a small storage-engine pipeline with separate layers for networking, command execution, in-memory state, and persistence.

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

### Architecture Breakdown

#### `ZyraDbApplication`

Bootstraps the database lifecycle. On startup it loads the snapshot, replays the WAL, starts the expiry scheduler, and then starts the TCP server. On shutdown it coordinates a safe persistence flow.

#### `TCPServer`

Handles raw TCP connections and line-based request/response exchange. Each client request enters the system through this layer.

Default runtime configuration:

- `zyra.tcp.port=6380`
- `zyra.tcp.enabled=true`

#### `CommandParser`

Converts a raw text command into a structured `Command` object that the service layer can validate and execute.

#### `KeyValueService`

Owns command semantics. It validates the request, routes the command, and serializes mutations so the write path stays consistent across memory and persistence.

#### `InMemoryStore`

Stores live key/value data and TTL metadata. It uses a `ReentrantReadWriteLock` so reads remain lightweight while writes stay safe.

#### `WriteAheadLog`

Persists mutating operations to `zyra.wal` before they are relied on for recovery. This protects recent writes across restart.

#### `SnapshotManager`

Creates a snapshot file `zyra.snapshot` and resets the WAL after a successful save so recovery remains fast and bounded.

#### `ExpiryScheduler`

Runs periodic cleanup for expired keys. Passive expiration still happens during reads, but this layer keeps stale keys from sitting in memory indefinitely.

## Request Lifecycle

### Write Path

For a mutation such as `SET user alice EX 30`:

```text
Client
  -> TCPServer
  -> CommandParser
  -> KeyValueService
  -> mutation lock
  -> WriteAheadLog.logSet(...)
  -> InMemoryStore.restore(...)
  -> response: OK
```

The important part here is that writes are serialized through the service layer so persistence and memory updates happen as one logical operation.

### Read Path

For a lookup such as `GET user`:

```text
Client
  -> TCPServer
  -> CommandParser
  -> KeyValueService
  -> InMemoryStore.get(...)
  -> response: VAL alice
```

Reads stay lightweight and use the store's read lock, while writes use a stricter path to preserve correctness.

## Persistence and Recovery

ZyraDB combines two persistence mechanisms:

### Write-Ahead Log

- Stored in `zyra.wal`
- Records every `SET` and `DEL`
- Uses Base64 encoding for stored key/value fields
- Flushes writes to disk so mutations survive crashes more reliably

### Snapshot

- Stored in `zyra.snapshot`
- Captures the current in-memory state
- Skips expired entries
- Replaces the old snapshot atomically when possible
- Resets the WAL after a successful snapshot

### Startup Flow

When the application starts, it:

1. loads the snapshot if present
2. replays the WAL
3. starts the expiry scheduler
4. starts the TCP server

### Shutdown Flow

When the JVM shuts down, it:

1. stops the expiry scheduler
2. stops the TCP server
3. saves a fresh snapshot
4. closes WAL resources

If snapshot creation fails, ZyraDB preserves the WAL so recovery can still happen later.

## Project Structure

The repository is split into a small runtime core, persistence helpers, and focused test coverage.

```text
zyradb/
|-- docs/
|   `-- DETAILED_DOCUMENTATION.md
|-- src/
|   |-- main/
|   |   |-- java/com/zyra/
|   |   |   |-- ZyraDbApplication.java
|   |   |   |-- parser/
|   |   |   |   |-- Command.java
|   |   |   |   `-- CommandParser.java
|   |   |   |-- scheduler/
|   |   |   |   `-- ExpiryScheduler.java
|   |   |   |-- service/
|   |   |   |   `-- KeyValueService.java
|   |   |   |-- store/
|   |   |   |   |-- CommandExecutor.java
|   |   |   |   |-- InMemoryStore.java
|   |   |   |   |-- SnapshotManager.java
|   |   |   |   `-- WriteAheadLog.java
|   |   |   `-- tcp/
|   |   |       `-- TCPServer.java
|   |   `-- resources/
|   |       `-- application.properties
|   `-- test/
|       `-- java/com/zyra/
|           |-- parser/
|           |-- service/
|           |-- store/
|           |-- tcp/
|           `-- ZyradbApplicationTests.java
`-- README.md
```

### Directory Guide

#### `src/main/java/com/zyra`

Contains the application runtime:

- `ZyraDbApplication.java`: startup and shutdown orchestration
- `parser/`: command parsing models and logic
- `service/`: command execution rules
- `store/`: in-memory state, WAL, snapshot, and replay helpers
- `tcp/`: TCP server and socket handling
- `scheduler/`: background expiry cleanup

#### `src/main/resources`

Contains runtime configuration such as `application.properties`.

#### `src/test/java/com/zyra`

Contains unit and integration tests for parser behavior, service logic, storage, persistence, concurrency, and end-to-end TCP flows.

#### `docs`

Contains the deeper technical write-up in `DETAILED_DOCUMENTATION.md`.

## Getting Started

### Prerequisites

- Java 21
- Maven 3.9+ or the included Maven Wrapper

### Run the Server

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

macOS/Linux:

```bash
./mvnw spring-boot:run
```

The TCP server listens on:

```text
localhost:6380
```

### Configuration

Current runtime properties in `src/main/resources/application.properties`:

```properties
spring.application.name=zyradb
zyra.tcp.port=6380
```

You can change the TCP port by updating `zyra.tcp.port`.

## Supported Commands

| Command | Example | Response |
|---|---|---|
| `SET key value` | `SET user alice` | `OK` |
| `SET key value [EX seconds]` | `SET session abc EX 30` | `OK` |
| `GET key` | `GET user` | `VAL alice` or `NIL` |
| `DEL key` | `DEL user` | `INT 1` or `INT 0` |
| `EXPIRE key seconds` | `EXPIRE session 60` | `INT 1` or `INT 0` |
| `TTL key` | `TTL session` | `INT <seconds>`, `INT -1`, or `INT -2` |
| `INFO` | `INFO` | `INFO keys=<n> uptime=<seconds>` |
| `QUIT` | `QUIT` | `BYE` |

### `EX` vs `EXPIRE`

- `EX` is only a `SET` option. Use `SET key value EX seconds`.
- `EXPIRE` is a standalone command. Use `EXPIRE key seconds` to add or update TTL on an existing key.

### TTL Semantics

- `INT -1` means the key exists and has no expiry
- `INT -2` means the key does not exist or is already expired
- `INT N` means the key expires in about `N` seconds

## Sample Session

```text
SET name Zyra
OK

GET name
VAL Zyra

SET token abc123 EX 10
OK

TTL token
INT 10

DEL name
INT 1

INFO
INFO keys=1 uptime=42

QUIT
BYE
```

You can connect using `telnet`, `nc`, or any simple TCP client.

## Testing

Run all tests:

Windows:

```powershell
.\mvnw.cmd test
```

macOS/Linux:

```bash
./mvnw test
```

The test suite covers:

- parser behavior and validation
- service command handling
- store concurrency and TTL behavior
- WAL durability and replay
- TCP integration behavior
- full system startup, recovery, and shutdown flow

GitHub Actions CI is configured in `.github/workflows/ci.yml` and runs `./mvnw test` on every push to `main`.

## Benchmark

On a local Windows development run, ZyraDB processed `10,000` sequential `SET` commands over TCP in about `33.799s`, which is roughly `296 ops/sec`. This is not a formal benchmark suite, but it gives a quick baseline for the current single-node implementation and makes the runtime behavior more concrete for reviewers.

## Roadmap

- RESP-compatible protocol support
- snapshot scheduling and config-driven persistence intervals
- append-only file compaction improvements
- transactions or pipelined batch execution
- replication and follower recovery
- metrics and observability
- authentication and access control
- benchmarking and profiling

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).

## Disclaimer

This project is built for educational and learning purposes only. It is not intended to be used as a production database or as a drop-in replacement for Redis or other mature data stores.
