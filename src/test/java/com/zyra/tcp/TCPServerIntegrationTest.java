package com.zyra.tcp;

import com.zyra.parser.CommandParser;
import com.zyra.service.KeyValueService;
import com.zyra.store.InMemoryStore;
import com.zyra.store.WriteAheadLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TCPServerIntegrationTest {

    private final InMemoryStore store = InMemoryStore.getInstance();

    private TCPServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        store.clear();
        WriteAheadLog.truncate();
        port = findFreePort();
        server = new TCPServer(port, true, new KeyValueService(store), new CommandParser());
        server.start();
        waitForServer();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown();
        }
        store.clear();
        WriteAheadLog.truncate();
    }

    @Test
    void supportsMultipleCommandsOnSingleConnectionAndQuit() throws Exception {
        try (
                Socket socket = new Socket("localhost", port);
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        ) {
            writeLine(writer, "SET alpha 1");
            assertEquals("OK", reader.readLine());

            writeLine(writer, "GET alpha");
            assertEquals("VAL 1", reader.readLine());

            writeLine(writer, "QUIT");
            assertEquals("BYE", reader.readLine());
        }
    }

    @Test
    void supportsLineBasedPipeliningInOrder() throws Exception {
        try (
                Socket socket = new Socket("localhost", port);
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        ) {
            writeLine(writer, "SET p1 x");
            writeLine(writer, "SET p2 y");
            writeLine(writer, "GET p1");
            writeLine(writer, "GET p2");

            assertEquals("OK", reader.readLine());
            assertEquals("OK", reader.readLine());
            assertEquals("VAL x", reader.readLine());
            assertEquals("VAL y", reader.readLine());
        }
    }

    @Test
    void handlesExpiryOverTcpConnection() throws Exception {
        try (
                Socket socket = new Socket("localhost", port);
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        ) {
            writeLine(writer, "SET temp 42 EX 1");
            assertEquals("OK", reader.readLine());

            writeLine(writer, "TTL temp");
            assertEquals("INT 1", reader.readLine());

            Thread.sleep(1100);

            writeLine(writer, "GET temp");
            assertEquals("NIL", reader.readLine());
        }
    }

    @Test
    void canRestartAfterShutdown() throws Exception {
        server.shutdown();
        waitForServerToStop();

        server.start();
        waitForServer();

        try (
                Socket socket = new Socket("localhost", port);
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        ) {
            writeLine(writer, "SET restart ok");
            assertEquals("OK", reader.readLine());

            writeLine(writer, "GET restart");
            assertEquals("VAL ok", reader.readLine());
        }
    }

    @Test
    void canRetryStartAfterInitialBindFailure() throws Exception {
        server.shutdown();
        waitForServerToStop();

        try (ServerSocket blocker = new ServerSocket(port)) {
            assertThrows(IllegalStateException.class, server::start);
        }

        server.start();
        waitForServer();

        try (
                Socket socket = new Socket("localhost", port);
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        ) {
            writeLine(writer, "SET rebound ok");
            assertEquals("OK", reader.readLine());

            writeLine(writer, "GET rebound");
            assertEquals("VAL ok", reader.readLine());
        }
    }

    @Test
    void rejectedConnectionsAreRemovedFromTrackingSet() throws Exception {
        server.shutdown();
        waitForServerToStop();

        server = new TCPServer(port, true, 1, new KeyValueService(store), new CommandParser());
        server.start();
        waitForServer();

        List<Socket> clients = new ArrayList<>();
        int rejectedConnections = 0;

        try {
            for (int i = 0; i < 80; i++) {
                Socket socket = new Socket("localhost", port);
                socket.setSoTimeout(200);
                clients.add(socket);
            }

            for (Socket socket : clients) {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line = reader.readLine();
                    if ("ERR server at capacity, try again later".equals(line)) {
                        rejectedConnections++;
                    }
                } catch (SocketTimeoutException ignored) {
                    // Accepted connections block waiting for input, which is expected here.
                }
            }
        } finally {
            for (Socket socket : clients) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        assertTrue(rejectedConnections > 0);
        waitForTrackedClientsToDrain();
        assertEquals(0, trackedClientCount());
    }

    private void writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
        writer.flush();
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

    private void waitForTrackedClientsToDrain() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();

        while (System.nanoTime() < deadline) {
            if (trackedClientCount() == 0) {
                return;
            }
            Thread.sleep(50);
        }

        throw new IllegalStateException("Tracked client sockets did not drain in time");
    }

    @SuppressWarnings("unchecked")
    private int trackedClientCount() {
        try {
            Field field = TCPServer.class.getDeclaredField("clientSockets");
            field.setAccessible(true);
            return ((Set<Socket>) field.get(server)).size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to inspect tracked client sockets", e);
        }
    }
}
