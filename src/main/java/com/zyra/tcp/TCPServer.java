package com.zyra.tcp;

import com.zyra.scheduler.ExpiryScheduler;
import com.zyra.store.InMemoryStore;
import com.zyra.store.SnapshotManager;
import com.zyra.store.WriteAheadLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TCPServer {

    private static final Logger log = LoggerFactory.getLogger(TCPServer.class);

    private static final int DEFAULT_PORT = 6379;
    private static final int MAX_THREADS = 50;
    private static final int MAX_QUEUE_SIZE = 100;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final ThreadPoolExecutor threadPool = new ThreadPoolExecutor(
            MAX_THREADS,
            MAX_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUE_SIZE)
    );
    private final AtomicInteger clientCounter = new AtomicInteger(0);
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);
    private final Set<Socket> activeClients = ConcurrentHashMap.newKeySet();
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
                Socket clientSocket = null;

                try {
                    clientSocket = localServerSocket.accept();
                    if (!running) {
                        closeQuietly(clientSocket);
                        break;
                    }

                    int clientId = clientCounter.incrementAndGet();
                    Socket acceptedSocket = clientSocket;
                    log.info("Client-{} connected from {}", clientId, clientSocket.getRemoteSocketAddress());
                    activeClients.add(acceptedSocket);
                    threadPool.execute(new ClientHandler(acceptedSocket, clientId, () -> activeClients.remove(acceptedSocket)));
                } catch (RejectedExecutionException e) {
                    log.warn("Rejecting client connection because the server is at capacity");
                    if (clientSocket != null) {
                        rejectClient(clientSocket);
                    }
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

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.warn("Error closing server socket", e);
        }

        closeActiveClients();
        threadPool.shutdown();
        awaitWorkerShutdown();
        SnapshotManager.save(InMemoryStore.getInstance().snapshot());
    }

    private void registerShutdownHook() {
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        }
    }

    private void awaitWorkerShutdown() {
        try {
            if (!threadPool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Forcing shutdown of remaining client handlers");
                threadPool.shutdownNow();
                threadPool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            threadPool.shutdownNow();
        }
    }

    private void closeActiveClients() {
        for (Socket clientSocket : activeClients) {
            closeQuietly(clientSocket);
        }
    }

    private void rejectClient(Socket clientSocket) {
        try (PrintWriter writer = new PrintWriter(
                new BufferedWriter(
                        new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8)),
                true)) {
            writer.println("ERR server busy");
        } catch (IOException ignored) {
        } finally {
            activeClients.remove(clientSocket);
            closeQuietly(clientSocket);
        }
    }

    private void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public static void main(String[] args) {
        InMemoryStore store = InMemoryStore.getInstance();
        SnapshotManager.load(store);
        WriteAheadLog.replay(store);
        ExpiryScheduler.start(store);

        new TCPServer().start();
    }
}
