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
    private volatile ServerSocket serverSocket;
    private volatile Thread serverThread;

    @Autowired
    public TCPServer(
            @Value("${zyra.tcp.port:6379}") int port,
            @Value("${zyra.tcp.enabled:true}") boolean enabled,
            KeyValueService service,
            com.zyra.parser.CommandParser parser) {
        this.port = port;
        this.enabled = enabled;
        this.service = service;
        this.parser = parser;
    }

    TCPServer(int port, boolean enabled, KeyValueService service, com.zyra.parser.CommandParser parser) {
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
        serverThread = new Thread(this::runServer, "zyra-tcp-acceptor-" + port);
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void runServer() {
        try (ServerSocket localServerSocket = new ServerSocket(port)) {
            serverSocket = localServerSocket;
            log.info("ZyraDB TCP Server started on port {}", port);

            while (running.get()) {
                Socket client = localServerSocket.accept();
                Thread clientThread = new Thread(() -> handleClient(client), "zyra-client-" + client.getPort());
                clientThread.setDaemon(true);
                clientThread.start();
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

    @PreDestroy
    public void shutdown() {
        if (!started.get()) {
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

                String response = service.execute(
                        parser.parse(line)
                );

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
            try {
                client.close();
            } catch (IOException ignored) {
            }
            log.info("Client disconnected");
        }
    }
}
