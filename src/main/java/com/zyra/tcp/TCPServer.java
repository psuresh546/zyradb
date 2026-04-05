package com.zyra.tcp;

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
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class TCPServer {

    private static final Logger log = LoggerFactory.getLogger(TCPServer.class);

    private final int port;
    private final boolean enabled;
    private final KeyValueService service;
    private final com.zyra.parser.CommandParser parser;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<Socket> clientSockets = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile Thread serverThread;
    private volatile ExecutorService clientExecutor;

    @Autowired
    public TCPServer(
            @Value("${zyra.tcp.port:6380}") int port,
            @Value("${zyra.tcp.enabled:true}") boolean enabled,
            KeyValueService service,
            com.zyra.parser.CommandParser parser) {
        this.port = port;
        this.enabled = enabled;
        this.service = service;
        this.parser = parser;
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
        clientExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("zyra-client-worker");
            return thread;
        });
        serverThread = new Thread(this::runServer, "zyra-tcp-acceptor-" + port);
        serverThread.start();
    }

    private void runServer() {
        try (ServerSocket localServerSocket = new ServerSocket(port)) {
            serverSocket = localServerSocket;
            log.info("ZyraDB TCP Server started on port {}", port);

            while (running.get()) {
                Socket client = localServerSocket.accept();
                clientSockets.add(client);
                ExecutorService executor = clientExecutor;
                if (executor == null || executor.isShutdown()) {
                    closeClientQuietly(client);
                    clientSockets.remove(client);
                    continue;
                }

                try {
                    executor.execute(() -> handleClient(client));
                } catch (RejectedExecutionException e) {
                    closeClientQuietly(client);
                    clientSockets.remove(client);
                    if (running.get()) {
                        log.warn("Rejected client connection during server shutdown");
                    }
                }
            }

        } catch (SocketException e) {
            if (running.get()) {
                log.error("TCP server socket error", e);
            }
        } catch (IOException e) {
            log.error("TCP server failed", e);
        } finally {
            serverSocket = null;
            running.set(false);
            started.set(false);
        }
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
            log.warn("Error while closing TCP server", e);
        }

        for (Socket clientSocket : clientSockets) {
            try {
                clientSocket.close();
            } catch (IOException e) {
                log.debug("Error while closing client socket: {}", e.getMessage());
            }
        }

        ExecutorService localClientExecutor = clientExecutor;
        clientExecutor = null;
        if (localClientExecutor != null) {
            localClientExecutor.shutdown();
            try {
                if (!localClientExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    localClientExecutor.shutdownNow();
                    localClientExecutor.awaitTermination(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                localClientExecutor.shutdownNow();
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for client workers to stop");
            }
        }

        if (serverThread != null && serverThread.isAlive()) {
            try {
                serverThread.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for TCP server to stop");
            }
        }

        serverThread = null;
        serverSocket = null;
    }

    private void handleClient(Socket client) {
        log.info("Client connected: {}", client.getRemoteSocketAddress());

        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
        ) {

            String line;

            while ((line = reader.readLine()) != null) {

                if (line.isBlank()) continue;

                String response;
                try {
                    response = service.execute(parser.parse(line));
                } catch (RuntimeException e) {
                    log.warn("Client command failed: {}", e.getMessage());
                    response = "ERR internal server error";
                }

                writer.write(response);
                writer.newLine();
                writer.flush();

                if ("BYE".equals(response)) {
                    break;
                }
            }

        } catch (IOException e) {
            log.debug("Client I/O ended: {}", e.getMessage());
        } finally {
            closeClientQuietly(client);
            clientSockets.remove(client);
            log.info("Client disconnected");
        }
    }

    private void closeClientQuietly(Socket client) {
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }
}
