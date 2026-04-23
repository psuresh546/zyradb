# ZyraDB

A Redis-inspired in-memory key-value database built from scratch in Java. ZyraDB implements core storage engine concepts — custom TCP command handling, concurrent in-memory storage, write-ahead logging, snapshot persistence, TTL expiration, crash recovery, and graceful shutdown — end to end, from protocol to disk.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)
![Protocol](https://img.shields.io/badge/Protocol-TCP-blue)
![Persistence](https://img.shields.io/badge/Persistence-WAL%20%2B%20Snapshot-red)

> For a deep technical walkthrough, see [Detailed Documentation](docs/DETAILED_DOCUMENTATION.md).

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Request Lifecycle](#request-lifecycle)
- [Persistence and Recovery](#persistence-and-recovery)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Commands](#commands)
- [Sample Session](#sample-session)
- [Testing](#testing)
- [Roadmap](#roadmap)

---

## Overview

Most backend projects stop at CRUD over HTTP. ZyraDB goes a layer deeper into storage engine fundamentals:

- TCP-based command handling instead of REST
- Custom line-based protocol with typed responses
- Thread-safe in-memory reads and writes with key-stripe locking
- Write-ahead logging for crash durability
- Snapshot-based persistence with WAL compaction
- TTL support with passive expiration and background cleanup
- Coordinated startup recovery and graceful shutdown

This project is built for learning database internals and distributed systems concepts, not as a production Redis replacement.

---

## Features

| Category | Details |
|---|---|
| **Storage** | In-memory key-value store with concurrent access |
| **Protocol** | Custom line-based TCP protocol |
| **Commands** | `SET`, `GET`, `DEL`, `EXPIRE`, `TTL`, `INFO`, `QUIT` |
| **TTL** | Per-key expiry via `EX` flag or `EXPIRE` command |
| **Expiration** | Passive on read + background cleanup every 5 seconds |
| **WAL** | `always` and `periodic` sync modes, Base64-encoded entries |
| **Snapshots** | Atomic snapshot saves, expired entries excluded |
| **Recovery** | WAL replay on startup, corruption-tolerant parsing |
| **Shutdown** | Graceful drain: snapshot saved, WAL closed cleanly |
| **Testing** | Unit, integration, end-to-end, and stress test coverage |

---

## Architecture

ZyraDB has two distinct paths: the runtime command path and the lifecycle path for startup, recovery, background cleanup, and shutdown.

### Runtime Command Path

```
Client
  └─> TCPServer          (accepts connections)
        └─> CommandParser  (tokenizes input)
              └─> KeyValueService  (validates and dispatches)
                    ├─> InMemoryStore   (reads / writes with key-stripe locking)
                    └─> WriteAheadLog   (mutation log on writes)

Background
  └─> ExpiryScheduler    (cleans up expired keys every 5s)
```

### Lifecycle Path

```
ZyraDbApplication
  ├─> SnapshotManager    (load snapshot on startup, save on shutdown)
  ├─> WriteAheadLog      (configure, replay WAL on startup, close on shutdown)
  ├─> ExpiryScheduler    (start / stop background cleanup)
  └─> TCPServer          (start / stop connection handling)
```

`ZyraDbApplication` coordinates the full startup and shutdown sequence, ensuring memory state, persistence, and networking come up and go down in the correct order.

---

## Request Lifecycle

### Write Path (`SET user alice EX 30`)

```
Client
  -> TCPServer
  -> CommandParser
  -> KeyValueService
  -> key stripe lock acquired
  -> WriteAheadLog.logSet(...)    ← persistence first
  -> InMemoryStore.restore(...)   ← then memory
  -> response: OK
```

Writes always log to the WAL before updating memory, ensuring durability even if the process crashes mid-operation.

### Read Path (`GET user`)

```
Client
  -> TCPServer
  -> CommandParser
  -> KeyValueService
  -> InMemoryStore.get(...)       ← passive TTL check inline
  -> response: VAL alice
```

Reads lock only the target key and enforce TTL semantics without touching the WAL.

---

## Persistence and Recovery

ZyraDB combines two complementary persistence mechanisms.

### Write-Ahead Log (`zyra.wal`)

Every `SET` and `DEL` is appended to the WAL before the in-memory store is updated. Keys and values are Base64-encoded. Two sync modes are supported:

- **`always`** — flushes to disk after every write (strongest durability)
- **`periodic`** — batches flushes on a configurable interval (better throughput)

### Snapshots (`zyra.snapshot`)

A point-in-time capture of the full in-memory state. Expired entries are excluded. Snapshots are written atomically where the OS supports it. After a successful snapshot, the WAL is reset.

### Startup Sequence

1. Load snapshot (if present)
2. Replay WAL on top of snapshot state
3. Start expiry scheduler
4. Start TCP server

### Shutdown Sequence

1. Stop expiry scheduler
2. Stop TCP server
3. Save a fresh snapshot
4. Close WAL resources

If the snapshot save fails, the WAL is preserved so recovery remains possible on the next restart.

---

## Project Structure

```
zyradb/
├── .github/workflows/ci.yml
├── docs/DETAILED_DOCUMENTATION.md
└── src/
    ├── main/java/com/zyra/
    │   ├── ZyraDbApplication.java      # startup and shutdown orchestration
    │   ├── parser/
    │   │   ├── Command.java
    │   │   └── CommandParser.java
    │   ├── scheduler/
    │   │   └── ExpiryScheduler.java
    │   ├── service/
    │   │   └── KeyValueService.java
    │   ├── store/
    │   │   ├── CommandExecutor.java
    │   │   ├── InMemoryStore.java
    │   │   ├── SnapshotManager.java
    │   │   └── WriteAheadLog.java
    │   └── tcp/
    │       └── TCPServer.java
    ├── main/resources/
    │   └── application.properties
    └── test/java/com/zyra/
        ├── parser/
        ├── service/
        ├── store/
        ├── stress/
        ├── tcp/
        └── ZyradbApplicationTests.java
```

---

## Getting Started

### Prerequisites

- Java 21
- Maven 3.9+ (or use the included Maven Wrapper)

### Start the Server

**macOS / Linux**
```bash
./mvnw spring-boot:run
```

**Windows**
```powershell
.\mvnw.cmd spring-boot:run
```

The server listens on `localhost:6380` by default. Connect with `telnet`, `nc`, or any TCP client.

### Configuration

`src/main/resources/application.properties`:

```properties
spring.application.name=zyradb
zyra.tcp.port=6380
zyra.wal.sync-mode=periodic
zyra.wal.force-interval-ms=100
```

Additional supported properties: `zyra.tcp.enabled`, `zyra.tcp.max-connections`, `zyra.snapshot.interval-seconds`. All properties can be overridden at startup via JVM arguments.

---

## Commands

| Command | Syntax | Response |
|---|---|---|
| `SET` | `SET key value` | `OK` |
| `SET` with TTL | `SET key value EX seconds` | `OK` |
| `GET` | `GET key` | `VAL <value>` or `NIL` |
| `DEL` | `DEL key` | `INT 1` (deleted) or `INT 0` (not found) |
| `EXPIRE` | `EXPIRE key seconds` | `INT 1` or `INT 0` |
| `TTL` | `TTL key` | `INT <seconds>`, `INT -1`, or `INT -2` |
| `INFO` | `INFO` | `INFO keys=<n> uptime=<seconds>` |
| `QUIT` | `QUIT` | `BYE` |

### TTL Semantics

| Response | Meaning |
|---|---|
| `INT N` | Key expires in approximately N seconds |
| `INT -1` | Key exists with no expiry |
| `INT -2` | Key does not exist or is already expired |

### `EX` vs `EXPIRE`

- `EX` is a `SET` option: `SET key value EX 30`
- `EXPIRE` is a standalone command: `EXPIRE key 30`

Use `EXPIRE` to add or update a TTL on an already-existing key.

---

## Sample Session

```
> SET name Zyra
OK

> GET name
VAL Zyra

> SET token abc123 EX 10
OK

> TTL token
INT 10

> DEL name
INT 1

> INFO
INFO keys=1 uptime=42

> QUIT
BYE
```

---

## Testing

**macOS / Linux**
```bash
./mvnw test
```

**Windows**
```powershell
.\mvnw.cmd test
```

The test suite covers:

- Command parsing and validation
- Service-layer command handling
- Store concurrency and TTL behavior
- WAL durability and replay correctness
- TCP integration behavior
- Full system startup, recovery, and shutdown flows
- Concurrency burn-in and restart lifecycle regressions

CI is configured in `.github/workflows/ci.yml` and runs on every push to `main`.

---

## Roadmap

- RESP-compatible protocol support
- Richer value type handling in the parser
- Configurable snapshot and maintenance policies
- WAL log segmentation and compaction
- Transactions and batch execution
- Replication and follower recovery
- Metrics and observability
- Authentication and access control
- Benchmarking and profiling tooling

---

*ZyraDB is an educational project. It is not intended for production use.*
