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
- [Roadmap](#roadmap)
- [License](#license)

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

ZyraDB follows a layered architecture where each component owns one part of the request or persistence lifecycle.

```text
Client
  ->
TCPServer
  ->
CommandParser
  ->
KeyValueService
  ->
InMemoryStore
  +-> WriteAheadLog
  +-> SnapshotManager
  +-> ExpiryScheduler
```

### Core Components

#### `ZyraDbApplication`

Bootstraps the system, loads persisted state, replays the WAL, starts the expiry scheduler, starts the TCP server, and registers a shutdown hook for safe termination.

#### `TCPServer`

Accepts client socket connections on the configured port and processes line-based commands. Each client connection is handled through the server's worker executor.

Default runtime configuration:

- `zyra.tcp.port=6380`
- `zyra.tcp.enabled=true`

#### `CommandParser`

Parses raw input into a structured `Command` object and normalizes command aliases.

#### `KeyValueService`

Acts as the command execution layer. It validates input, routes operations, and serializes writes so `WAL + in-memory state` remain consistent.

#### `InMemoryStore`

Maintains live key/value data and expiry metadata using a `ReentrantReadWriteLock` for safe concurrent access.

#### `WriteAheadLog`

Appends mutations to `zyra.wal` before in-memory state is considered committed. This allows the database to recover recent writes after restart.

#### `SnapshotManager`

Writes a compact snapshot to `zyra.snapshot` and truncates the WAL after a successful snapshot so recovery stays fast.

#### `ExpiryScheduler`

Runs periodic cleanup for expired keys while passive expiration is also enforced on reads like `GET` and `TTL`.

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

### Package Guide

- `parser`: command parsing and normalization
- `service`: command validation and execution
- `store`: in-memory storage, WAL, snapshot, and replay helpers
- `tcp`: socket server and client connection handling
- `scheduler`: background expiry cleanup
- `test`: unit and integration coverage for core engine behavior

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

- parser behavior and aliases
- service command handling
- store concurrency and TTL behavior
- WAL durability and replay
- TCP integration behavior
- full system startup, recovery, and shutdown flow

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

This project is currently shared for educational and portfolio purposes.

No formal open-source license file is included yet, so the repository should be treated as all rights reserved until a license is added.
