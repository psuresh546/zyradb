package com.zyra.tcp;

import com.zyra.parser.CommandParser;
import com.zyra.service.KeyValueService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TCPServer {

    private static final Logger log = LoggerFactory.getLogger(TCPServer.class);

    private static final String RESPONSE_LINE_SEPARATOR = "\n";
    private static final int FLUSH_RESPONSE_THRESHOLD = 32;
    private static final int FLUSH_CHAR_THRESHOLD = 4096;
    private static final int SOCKET_READ_TIMEOUT_MS = 30_000;
    private static final int STARTUP_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_MAX_CONNECTIONS = 1000;

    private final int port;
    private final boolean enabled;
    private final int maxConnections;
    private final KeyValueService service;
    private final CommandParser parser;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<Socket> clientSockets = ConcurrentHashMap.newKeySet();
    private final AtomicInteger threadCounter = new AtomicInteger(0);

    private volatile ServerSocket serverSocket;
    private volatile Thread serverThread;
    private volatile ThreadPoolExecutor clientExecutor;
    private volatile IllegalStateException startupFailure;

    @Autowired
    public TCPServer(
            @Value("${zyra.tcp.port:6380}") int port,
            @Value("${zyra.tcp.enabled:true}") boolean enabled,
            @Value("${zyra.tcp.max-connections:" + DEFAULT_MAX_CONNECTIONS + "}") int maxConnections,
            KeyValueService service,
            CommandParser parser) {
        this.port = port;
        this.enabled = enabled;
        this.maxConnections = maxConnections;
        this.service = service;
        this.parser = parser;
    }

    public TCPServer(int port, boolean enabled, KeyValueService service, CommandParser parser) {
        this(port, enabled, DEFAULT_MAX_CONNECTIONS, service, parser);
    }

    public void start() {
        if (!enabled) {
            log.info("TCP server startup is disabled");
            return;
        }

        if (!started.compareAndSet(false, true)) {
            log.warn("TCP server is already running on port {}", port);
            return;
        }

        running.set(true);
        startupFailure = null;

        clientExecutor = new ThreadPoolExecutor(
                Math.min(8, maxConnections),
                maxConnections,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                runnable -> {
                    Thread t = new Thread(runnable);
                    t.setName("zyra-client-worker-" + threadCounter.incrementAndGet());
                    return t;
                },
                (runnable, executor) -> {
                    if (runnable instanceof ClientTask task) {
                        sendErrorAndClose(task.socket(), "ERR server at capacity, try again later");
                    }
                    if (running.get()) {
                        log.warn("Rejected client connection: server at capacity ({} threads)",
                                maxConnections);
                    }
                });

        CountDownLatch startupSignal = new CountDownLatch(1);
        serverThread = new Thread(
                () -> runServer(startupSignal),
                "zyra-tcp-acceptor-" + port);
        serverThread.start();
        awaitStartup(startupSignal);
    }

    public void stop() {
        shutdown();
    }

    @PreDestroy
    public void shutdown() {
        if (!started.compareAndSet(true, false)) {
            return;
        }

        running.set(false);

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.warn("Error while closing TCP server socket", e);
        }

        closeAllClientSockets();
        shutdownClientExecutor();

        Thread localServerThread = serverThread;
        serverThread = null;
        if (localServerThread != null && localServerThread.isAlive()) {
            try {
                localServerThread.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for TCP acceptor to stop");
            }
        }

        serverSocket = null;
        log.info("TCP server stopped");
    }

    private void runServer(CountDownLatch startupSignal) {
        boolean serverBound = false;
        try (ServerSocket localServerSocket = new ServerSocket(port)) {
            serverSocket = localServerSocket;
            serverBound = true;
            log.info("ZyraDB TCP server started on port {}", port);
            startupSignal.countDown();

            while (running.get()) {
                Socket client;
                try {
                    client = localServerSocket.accept();
                } catch (SocketException e) {
                    if (running.get()) {
                        log.error("TCP server socket error during accept", e);
                    }
                    break;
                }

                clientSockets.add(client);

                ThreadPoolExecutor executor = clientExecutor;
                if (executor == null || executor.isShutdown()) {
                    sendErrorAndClose(client, "ERR server shutting down");
                    continue;
                }

                try {
                    executor.execute(new ClientTask(client));
                } catch (RejectedExecutionException e) {
                    clientSockets.remove(client);
                }
            }

        } catch (IOException e) {
            if (!serverBound) {
                startupFailure = new IllegalStateException(
                        "Failed to bind TCP server on port " + port, e);
            } else if (running.get()) {
                log.error("TCP server failed to bind or accept", e);
            }
        } finally {
            serverSocket = null;
            running.set(false);
            serverThread = null;

            if (started.compareAndSet(true, false)) {
                closeAllClientSockets();
                shutdownClientExecutor();
                log.warn("TCP server on port {} stopped unexpectedly", port);
            }

            if (startupSignal.getCount() > 0) {
                startupSignal.countDown();
            }
        }
    }

    private void awaitStartup(CountDownLatch startupSignal) {
        boolean startedInTime;
        try {
            startedInTime = startupSignal.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown();
            throw new IllegalStateException("Interrupted while waiting for TCP server startup", e);
        }

        if (!startedInTime) {
            shutdown();
            throw new IllegalStateException(
                    "Timed out waiting for TCP server startup on port " + port);
        }

        if (startupFailure != null) {
            throw startupFailure;
        }
    }

    private final class ClientTask implements Runnable {
        private final Socket socket;

        private ClientTask(Socket socket) {
            this.socket = socket;
        }

        private Socket socket() {
            return socket;
        }

        @Override
        public void run() {
            handleClient(socket);
        }
    }

    private void handleClient(Socket client) {
        String clientAddr = String.valueOf(client.getRemoteSocketAddress());
        log.debug("Client connected: {}", clientAddr);

        try {
            client.setTcpNoDelay(true);
        } catch (SocketException e) {
            log.debug("Unable to set TCP_NODELAY for {}: {}", clientAddr, e.getMessage());
        }

        try {
            client.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
        } catch (SocketException e) {
            log.debug("Unable to set SO_TIMEOUT for {}: {}", clientAddr, e.getMessage());
        }

        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
        ) {
            String line;
            int bufferedResponses = 0;
            StringBuilder pendingResponses = new StringBuilder(512);

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                String response;
                try {
                    response = service.execute(parser.parse(line));
                } catch (RuntimeException e) {
                    log.warn("Command execution failed for {}: {}", clientAddr, e.getMessage());
                    response = "ERR internal server error";
                }

                if (pendingResponses.length() == 0) {
                    boolean directFlush = "BYE".equals(response) || !reader.ready();
                    if (directFlush) {
                        writer.write(response);
                        writer.write(RESPONSE_LINE_SEPARATOR);
                        writer.flush();

                        if ("BYE".equals(response)) {
                            break;
                        }
                        continue;
                    }
                }

                pendingResponses.append(response).append(RESPONSE_LINE_SEPARATOR);
                bufferedResponses++;

                // Flush immediately for request/response traffic, but still allow
                // lightweight batching when the client has already sent more lines.
                boolean shouldFlush = "BYE".equals(response)
                        || bufferedResponses >= FLUSH_RESPONSE_THRESHOLD
                        || pendingResponses.length() >= FLUSH_CHAR_THRESHOLD
                        || !reader.ready();

                if (shouldFlush) {
                    writer.write(pendingResponses.toString());
                    writer.flush();
                    pendingResponses.setLength(0);
                    bufferedResponses = 0;
                }

                if ("BYE".equals(response)) {
                    break;
                }
            }

            if (pendingResponses.length() > 0) {
                writer.write(pendingResponses.toString());
                writer.flush();
            }

        } catch (SocketTimeoutException e) {
            log.debug("Client {} timed out after {}ms of inactivity",
                    clientAddr, SOCKET_READ_TIMEOUT_MS);
        } catch (IOException e) {
            log.debug("Client {} I/O ended: {}", clientAddr, e.getMessage());
        } finally {
            closeClientQuietly(client);
            clientSockets.remove(client);
            log.debug("Client disconnected: {}", clientAddr);
        }
    }

    private void sendErrorAndClose(Socket client, String message) {
        try {
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));
            writer.write(message + RESPONSE_LINE_SEPARATOR);
            writer.flush();
        } catch (IOException ignored) {
        } finally {
            closeClientQuietly(client);
            clientSockets.remove(client);
        }
    }

    private void closeAllClientSockets() {
        for (Socket clientSocket : clientSockets) {
            try {
                clientSocket.close();
            } catch (IOException e) {
                log.debug("Error closing client socket: {}", e.getMessage());
            }
        }
        clientSockets.clear();
    }

    private void shutdownClientExecutor() {
        ThreadPoolExecutor localExecutor = clientExecutor;
        clientExecutor = null;
        if (localExecutor == null) {
            return;
        }

        localExecutor.shutdown();
        try {
            if (!localExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                localExecutor.shutdownNow();
                localExecutor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            localExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for client workers to stop");
        }
    }

    private void closeClientQuietly(Socket client) {
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }
}
