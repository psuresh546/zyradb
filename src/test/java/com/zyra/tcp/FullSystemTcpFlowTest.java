package com.zyra.tcp;

import com.zyra.parser.CommandParser;
import com.zyra.scheduler.ExpiryScheduler;
import com.zyra.service.KeyValueService;
import com.zyra.store.InMemoryStore;
import com.zyra.store.SnapshotManager;
import com.zyra.store.WriteAheadLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullSystemTcpFlowTest {

    private static final Path WAL_PATH = Path.of("zyra.wal");

    private final InMemoryStore store = InMemoryStore.getInstance();

    private TCPServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        store.clear();
        Files.deleteIfExists(Path.of("zyra.snapshot"));
        Files.deleteIfExists(Path.of("zyra.snapshot.tmp"));
        WriteAheadLog.truncate();

        port = findFreePort();
        server = new TCPServer(port, true, new KeyValueService(store), new CommandParser());
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
        Files.deleteIfExists(Path.of("zyra.snapshot"));
        Files.deleteIfExists(Path.of("zyra.snapshot.tmp"));
        Files.deleteIfExists(WAL_PATH);
    }

    @Test
    void passesFullSystemTcpFlow() throws Exception {
        assertEquals("OK", sendCommand("SET a 1"));
        assertEquals("VAL 1", sendCommand("GET a"));
        assertEquals("INT 1", sendCommand("DEL a"));
        assertEquals("NIL", sendCommand("GET a"));

        assertEquals("OK", sendCommand("SET e1 v EX 5"));
        assertTtlBetween(sendCommand("TTL e1"), 4, 5);
        assertEquals("OK", sendCommand("SET e2 v EX 5"));
        assertTtlBetween(sendCommand("TTL e2"), 4, 5);
        assertEquals("OK", sendCommand("SET e3 v EX 5"));
        assertTtlBetween(sendCommand("TTL e3"), 4, 5);

        Thread.sleep(2000);
        assertTtlBetween(sendCommand("TTL e1"), 2, 3);

        Thread.sleep(4000);
        assertEquals("NIL", sendCommand("GET e1"));

        assertEquals("OK", sendCommand("SET t1 v"));
        assertEquals("INT -1", sendCommand("TTL t1"));
        assertEquals("INT -2", sendCommand("TTL no_key"));

        assertEquals("OK", sendCommand("SET r1 v EX 5"));
        Thread.sleep(2000);
        assertEquals("OK", sendCommand("SET r1 newv"));
        assertEquals("INT -1", sendCommand("TTL r1"));

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                final int index = i;
                tasks.add(() -> sendCommand("SET race value" + index));
            }

            List<Future<String>> results = executor.invokeAll(tasks);
            for (Future<String> result : results) {
                assertEquals("OK", result.get());
            }
        } finally {
            executor.shutdownNow();
        }
        assertTrue(sendCommand("GET race").matches("^VAL value\\d+$"));

        for (int i = 1; i <= 50; i++) {
            assertEquals("OK", sendCommand("SET snap" + i + " data" + i));
        }
        restartServer();
        assertEquals("VAL data25", sendCommand("GET snap25"));
        assertEquals("VAL data50", sendCommand("GET snap50"));

        assertEquals("OK", sendCommand("SET walkey 999"));
        assertEquals("OK", sendCommand("SET walttl 888 EX 20"));
        restartServer();
        assertEquals("VAL 999", sendCommand("GET walkey"));
        assertTtlBetween(sendCommand("TTL walttl"), 15, 20);

        assertEquals("OK", sendCommand("SET sched temp EX 3"));
        Thread.sleep(6000);
        assertEquals("NIL", sendCommand("GET sched"));

        for (int i = 1; i <= 50; i++) {
            assertEquals("OK", sendCommand("SET heavy" + i + " val" + i));
        }
        restartServer();
        assertEquals("VAL val25", sendCommand("GET heavy25"));
        assertEquals("VAL val50", sendCommand("GET heavy50"));

        assertEquals("OK", sendCommand("SET final1 value1"));
        assertEquals("OK", sendCommand("SET final2 value2 EX 30"));
        restartServer();
        assertEquals("VAL value1", sendCommand("GET final1"));
        assertTtlBetween(sendCommand("TTL final2"), 25, 30);
        assertEquals(0L, Files.size(WAL_PATH));
    }

    private void restartServer() throws Exception {
        ExpiryScheduler.shutdown();
        server.shutdown();
        waitForServerToStop();

        boolean snapshotSaved = SnapshotManager.save(store);
        assertTrue(snapshotSaved);
        WriteAheadLog.shutdown();

        store.clear();
        SnapshotManager.load(store);
        WriteAheadLog.replay(store);

        ExpiryScheduler.start(store);
        server.start();
        waitForServer();
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

    private void assertTtlBetween(String response, int min, int max) {
        assertTrue(response.startsWith("INT "));
        int ttl = Integer.parseInt(response.substring(4));
        assertTrue(ttl >= min && ttl <= max, "TTL out of range: " + ttl);
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

    private void waitForServerToStop() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();

        while (System.nanoTime() < deadline) {
            try (Socket ignored = new Socket("localhost", port)) {
                Thread.sleep(50);
            } catch (IOException ignored) {
                return;
            }
        }

        throw new IllegalStateException("TCP server did not stop in time");
    }
}
