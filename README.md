# ZyraDB

**High-Performance In-Memory Key-Value Database (Built from Scratch in Java)**

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)
![Protocol](https://img.shields.io/badge/Protocol-TCP-blue)
![Concurrency](https://img.shields.io/badge/Concurrency-ThreadPerConnection-red)

ZyraDB is a Redis-inspired, in-memory key-value database built from scratch in Java 21 with Spring Boot. It focuses on storage-engine fundamentals such as TCP command handling, concurrent access control, write-ahead logging, snapshot-based persistence, TTL expiration, crash-tolerant replay, and graceful shutdown behavior.

## Project Overview

This project is designed as an educational database engine rather than a full Redis clone. The goal is to understand how a small storage system is structured end to end:

- a TCP server accepts text-based commands
- a parser converts raw input into internal commands
- a service layer validates and executes operations
- an in-memory store manages key/value state and expirations
- a write-ahead log preserves recent mutations for recovery
- snapshots compact state for faster restart

ZyraDB emphasizes correctness and lifecycle behavior over breadth of features.

## Why This Project Matters

ZyraDB is a strong systems project because it demonstrates more than CRUD over a map. It shows how a database-like service handles networking, concurrency, recovery, expiration, persistence, and shutdown safety in one coherent codebase. For interviews and portfolio review, that makes it much more compelling than a typical REST application.

## Key Learning Objectives

- Understand how a custom TCP-based database protocol works
- Learn the purpose of write-ahead logging and snapshot persistence
- Explore safe concurrency using read/write locks and serialized mutations
- Implement TTL expiration with both passive and scheduled cleanup
- Design startup recovery and graceful shutdown flows
- Practice building, testing, and evolving a storage engine incrementally

## Architecture

ZyraDB follows a small layered architecture:

1. `TCPServer`
   Accepts socket connections on port `6379` by default, reads line-based commands, and returns line-based responses.

2. `CommandParser`
   Parses raw text into a structured `Command` object and normalizes aliases such as `DELETE -> DEL`.

3. `KeyValueService`
   Validates commands and coordinates the atomic write path for mutations. Write commands serialize `WAL + memory` as one logical operation.

4. `InMemoryStore`
   Stores keys, values, and expiry metadata in memory. The store owns read/write locking for internal state integrity.

5. `WriteAheadLog`
   Persists mutations to `zyra.wal` so the latest changes can be recovered after restart.

6. `SnapshotManager`
   Saves a compact snapshot to `zyra.snapshot` and truncates the WAL after successful snapshot creation.

7. `ExpiryScheduler`
   Periodically removes expired keys in the background while passive expiration is also enforced during reads.

## Request Flow

A write command such as `SET user alice EX 30` moves through the system like this:

```text
Client
  -> TCPServer
  -> CommandParser
  -> KeyValueService
  -> mutation lock
  -> WriteAheadLog
  -> InMemoryStore
  -> response to client
```

A read command such as `GET user` is simpler:

```text
Client
  -> TCPServer
  -> CommandParser
  -> KeyValueService
  -> InMemoryStore read path
  -> response to client
```

This separation is important because writes must keep `WAL + memory` consistent, while reads should remain lightweight and concurrent.

## Features Implemented

- TCP command server
- In-memory key-value storage
- `SET`, `GET`, `DEL`, `EXPIRE`, `TTL`, `INFO`, `QUIT`
- Command aliases such as `DELETE`, `EXP`, and `EXIT`
- TTL support during `SET`
- Passive expiration on reads
- Background expiration cleanup
- Write-ahead logging for mutations
- Snapshot persistence
- WAL replay on restart
- Corruption-tolerant WAL replay
- Graceful shutdown with snapshot, WAL close, and server stop
- Automated tests for parser, service, store, WAL, TCP integration, and full system flow

## Supported Commands

| Command | Example | Response |
|---|---|---|
| `SET key value` | `SET user alice` | `OK` |
| `SET key value EX seconds` | `SET session abc EX 30` | `OK` |
| `GET key` | `GET user` | `VAL alice` or `NIL` |
| `DEL key` | `DEL user` | `INT 1` or `INT 0` |
| `EXPIRE key seconds` | `EXPIRE session 60` | `INT 1` or `INT 0` |
| `TTL key` | `TTL session` | `INT <seconds>` |
| `INFO` | `INFO` | `INFO keys=<n> uptime=<seconds>` |
| `QUIT` / `EXIT` | `QUIT` | `BYE` |

### Command Aliases

- `DELETE` acts as `DEL`
- `EXP` acts as `EXPIRE`
- `EX` can be used in `SET key value EX seconds`
- `EXIT` acts as `QUIT`

## TTL Expiration

ZyraDB supports expiration in two ways:

- Passive expiration
  Expired keys are treated as missing when accessed through `GET` or `TTL`.

- Scheduled cleanup
  A background scheduler periodically removes expired keys from memory.

### TTL Return Values

- `INT -1` means the key exists and has no expiry
- `INT -2` means the key does not exist or has already expired
- `INT N` means the key will expire in approximately `N` seconds

## Persistence and Recovery

ZyraDB uses two persistence mechanisms together:

- Write-ahead log
  Every mutation is written to `zyra.wal` before or within the atomic mutation path so recent changes can be replayed.

- Snapshot
  A full state snapshot is written to `zyra.snapshot`. After a successful snapshot, the WAL is truncated to prevent unbounded growth.

### Startup Recovery Flow

On startup, ZyraDB:

1. loads the snapshot if present
2. replays the WAL
3. skips malformed WAL lines instead of crashing
4. starts the expiry scheduler
5. starts the TCP server

### Graceful Shutdown Flow

On normal JVM shutdown, ZyraDB:

1. stops the expiry scheduler
2. stops the TCP server
3. saves a snapshot
4. truncates the WAL through snapshot completion
5. closes WAL resources

## Examples

### Basic Session

```text
SET name Zyra
OK

GET name
VAL Zyra

DEL name
INT 1

GET name
NIL
```

### TTL Example

```text
SET token abc123 EX 10
OK

TTL token
INT 10

GET token
VAL abc123
```

### Expire an Existing Key

```text
SET cart active
OK

EXPIRE cart 30
INT 1

TTL cart
INT 30
```

### Info Command

```text
INFO
INFO keys=3 uptime=42
```

## Project Structure

```text
src/
  main/
    java/com/zyra/
      ZyraDbApplication.java
      parser/
        Command.java
        CommandParser.java
      scheduler/
        ExpiryScheduler.java
      service/
        KeyValueService.java
      store/
        CommandExecutor.java
        InMemoryStore.java
        SnapshotManager.java
        WriteAheadLog.java
      tcp/
        ClientHandler.java
        TCPServer.java
    resources/
      application.properties
  test/
    java/com/zyra/
      ZyradbApplicationTests.java
      parser/
      service/
      store/
      tcp/
```

## Running the Project

### Prerequisites

- Java 21
- Maven 3.9+ or the included Maven Wrapper

### Start the Server

Using Maven Wrapper on Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

Using Maven Wrapper on macOS/Linux:

```bash
./mvnw spring-boot:run
```

The TCP server listens on:

```text
localhost:6379
```

### Run the Test Suite

Windows:

```powershell
.\mvnw.cmd test
```

macOS/Linux:

```bash
./mvnw test
```

## Verification

The project includes automated coverage across the main engine layers:

- parser tests for command parsing and aliases
- service tests for command validation and responses
- store tests for expiration, TTL behavior, and concurrent access
- WAL tests for durability and replay behavior
- TCP integration tests for real socket-based command execution
- full-system flow tests for restart, snapshot, replay, scheduler cleanup, and race scenarios

This gives ZyraDB both unit-level confidence and end-to-end behavior verification.

## Future Enhancements

- RESP-compatible protocol support
- append-only file compaction policies
- configurable snapshot intervals
- single-threaded command executor mode
- transactions or batch commands
- replication and follower recovery
- metrics and observability endpoints
- authentication and access control
- benchmark suite and performance profiling

## License

This project is intended for educational purposes only. It is shared to demonstrate database internals, systems design concepts, and storage-engine fundamentals in a compact Java codebase.

No formal open-source license is currently included in this repository. Until a license file is added, the project should be treated as all rights reserved by default.
