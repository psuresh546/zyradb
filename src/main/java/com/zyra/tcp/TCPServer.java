package com.zyra.tcp;

import com.zyra.store.InMemoryStore;
import com.zyra.store.SnapshotManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TCPServer {

    private static final Logger log = LoggerFactory.getLogger(TCPServer.class);

    private static final int DEFAULT_PORT = 6379;
    private static final int MAX_THREADS = 50;

    private final ExecutorService threadPool = Executors.newFixedThreadPool(MAX_THREADS);
    private final AtomicInteger clientCounter = new AtomicInteger(0);
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);
    private final int port;

    private volatile boolean running = true;
    private volatile ServerSocket serverSocket;

    public TCPServer() {
        this(DEFAULT_PORT);
    }

    public TCPServer(int port) {
        this.port = port;
    }

    public void start() {
        registerShutdownHook();
        log.info("Starting TCP Server on port {}", port);

        try (ServerSocket localServerSocket = new ServerSocket(port)) {
            serverSocket = localServerSocket;
            log.info("TCP Server started successfully and listening on port {}", port);

            while (running) {
                try {
                    Socket clientSocket = localServerSocket.accept();
                    int clientId = clientCounter.incrementAndGet();
                    log.info("Client-{} connected from {}", clientId, clientSocket.getRemoteSocketAddress());
                    threadPool.execute(new ClientHandler(clientSocket, clientId));
                } catch (SocketException e) {
                    if (running) {
                        log.error("Socket error while accepting client connections", e);
                    }
                    break;
                }
            }
        } catch (IOException e) {
            log.error("Server error: {}", e.getMessage(), e);
        } finally {
            serverSocket = null;
        }
    }

    public void shutdown() {
        if (!running) {
            return;
        }

        log.info("Shutting down TCP Server...");
        running = false;
        SnapshotManager.save(InMemoryStore.getInstance().snapshot());

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.warn("Error closing server socket", e);
        }

        threadPool.shutdown();
    }

    private void registerShutdownHook() {
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        }
    }

    public static void main(String[] args) {
        new TCPServer().start();
    }
}
