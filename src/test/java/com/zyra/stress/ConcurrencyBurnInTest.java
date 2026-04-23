package com.zyra.stress;

import com.zyra.parser.Command;
import com.zyra.parser.CommandParser;
import com.zyra.scheduler.ExpiryScheduler;
import com.zyra.service.KeyValueService;
import com.zyra.store.InMemoryStore;
import com.zyra.store.SnapshotManager;
import com.zyra.store.WriteAheadLog;
import com.zyra.tcp.TCPServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyBurnInTest {

    private static final Path SNAPSHOT_PATH = Path.of("zyra.snapshot");
    private static final Path SNAPSHOT_TEMP_PATH = Path.of("zyra.snapshot.tmp");
    private static final Path WAL_PATH = Path.of("zyra.wal");
    private static final Duration WORKLOAD_DURATION = Duration.ofSeconds(6);

    private final InMemoryStore store = InMemoryStore.getInstance();
    private final KeyValueService service = new KeyValueService(store);

    private TCPServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        ExpiryScheduler.shutdown();
        store.clear();
        WriteAheadLog.configure("periodic", 5);
        WriteAheadLog.truncate();
        Files.deleteIfExists(SNAPSHOT_PATH);
        Files.deleteIfExists(SNAPSHOT_TEMP_PATH);
        Files.deleteIfExists(WAL_PATH);

        port = findFreePort();
        server = new TCPServer(port, true, service, new CommandParser());
        ExpiryScheduler.start(store);
        server.start();
        waitForServer();
    }

    @AfterEach
    void tearDown() throws Exception {
        ExpiryScheduler.shutdown();

        if (server != null) {
            server.shutdown();
        }

        store.clear();
        WriteAheadLog.shutdown();
        Files.deleteIfExists(SNAPSHOT_PATH);
        Files.deleteIfExists(SNAPSHOT_TEMP_PATH);
        Files.deleteIfExists(WAL_PATH);
    }

    @Test
    @Timeout(35)
    void mixedConcurrencyBurnInCompletesWithoutDeadlockOrProtocolCorruption() throws Exception {
        long deadlineNanos = System.nanoTime() + WORKLOAD_DURATION.toNanos();
        ExecutorService executor = Executors.newFixedThreadPool(7);
        List<Future<?>> futures = new ArrayList<>();

        try {
            futures.add(executor.submit(() -> {
                runDirectWorker(deadlineNanos, 101L);
                return null;
            }));
            futures.add(executor.submit(() -> {
                runDirectWorker(deadlineNanos, 202L);
                return null;
            }));
            futures.add(executor.submit(() -> {
                runTcpWorker(deadlineNanos, 303L);
                return null;
            }));
            futures.add(executor.submit(() -> {
                runTcpWorker(deadlineNanos, 404L);
                return null;
            }));
            futures.add(executor.submit(() -> {
                runSnapshotWorker(deadlineNanos);
                return null;
            }));
            futures.add(executor.submit(() -> {
                runReplayWorker(deadlineNanos);
                return null;
            }));
            futures.add(executor.submit(() -> {
                runCleanupWorker(deadlineNanos);
                return null;
            }));

            long timeoutMillis = WORKLOAD_DURATION.plusSeconds(10).toMillis();
            for (Future<?> future : futures) {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertEquals("OK", sendCommand("SET postcheck stable"));
        assertEquals("VAL stable", sendCommand("GET postcheck"));
    }

    private void runDirectWorker(long deadlineNanos, long seed) throws Exception {
        SplittableRandom random = new SplittableRandom(seed);

        while (System.nanoTime() < deadlineNanos) {
            int operation = random.nextInt(7);
            String key = "hot-" + random.nextInt(32);
            String value = "v-" + seed + "-" + random.nextInt(10_000);

            String response = switch (operation) {
                case 0 -> service.execute(new Command("SET", List.of(key, value)));
                case 1 -> service.execute(new Command(
                        "SET", List.of(key, value, "EX", String.valueOf(1 + random.nextInt(3)))));
                case 2 -> service.execute(new Command("GET", List.of(key)));
                case 3 -> service.execute(new Command("DEL", List.of(key)));
                case 4 -> service.execute(new Command(
                        "EXPIRE", List.of(key, String.valueOf(1 + random.nextInt(3)))));
                case 5 -> service.execute(new Command("TTL", List.of(key)));
                default -> service.execute(new Command("INFO", List.of()));
            };

            assertTrue(isValidResponse(response), "Unexpected direct response: " + response);
        }
    }

    private void runTcpWorker(long deadlineNanos, long seed) throws Exception {
        SplittableRandom random = new SplittableRandom(seed);

        while (System.nanoTime() < deadlineNanos) {
            int operation = random.nextInt(7);
            String key = "hot-" + random.nextInt(32);
            String value = "tcp-" + seed + "-" + random.nextInt(10_000);

            String command = switch (operation) {
                case 0 -> "SET " + key + " " + value;
                case 1 -> "SET " + key + " " + value + " EX " + (1 + random.nextInt(3));
                case 2 -> "GET " + key;
                case 3 -> "DEL " + key;
                case 4 -> "EXPIRE " + key + " " + (1 + random.nextInt(3));
                case 5 -> "TTL " + key;
                default -> "INFO";
            };

            String response = sendCommand(command);
            assertNotNull(response, "TCP response was null for command: " + command);
            assertTrue(isValidResponse(response),
                    "Unexpected TCP response for '" + command + "': " + response);
        }
    }

    private void runSnapshotWorker(long deadlineNanos) throws Exception {
        while (System.nanoTime() < deadlineNanos) {
            assertTrue(SnapshotManager.save(store), "Snapshot save failed during burn-in");
            Thread.sleep(5);
        }
    }

    private void runReplayWorker(long deadlineNanos) throws Exception {
        while (System.nanoTime() < deadlineNanos) {
            WriteAheadLog.replay(store);
            Thread.sleep(5);
        }
    }

    private void runCleanupWorker(long deadlineNanos) throws Exception {
        while (System.nanoTime() < deadlineNanos) {
            store.cleanupExpiredKeys();
            store.size();
            WriteAheadLog.force();
            Thread.sleep(2);
        }
    }

    private boolean isValidResponse(String response) {
        return response.equals("OK")
                || response.equals("NIL")
                || response.startsWith("VAL ")
                || response.startsWith("INT ")
                || response.startsWith("INFO keys=");
    }

    private String sendCommand(String command) throws IOException {
        try (
                Socket socket = new Socket("localhost", port);
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        ) {
            writer.write(command);
            writer.newLine();
            writer.flush();
            return reader.readLine();
        }
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void waitForServer() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();

        while (System.nanoTime() < deadline) {
            try (Socket ignored = new Socket("localhost", port)) {
                return;
            } catch (IOException ignored) {
                Thread.sleep(50);
            }
        }

        throw new IllegalStateException("TCP server did not start in time");
    }
}
