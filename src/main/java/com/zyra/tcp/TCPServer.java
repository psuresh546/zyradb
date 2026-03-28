package com.zyra.tcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

public class TCPServer {

    private static final Logger log = LoggerFactory.getLogger(TCPServer.class);

    private final int port;

    // Only responsibility: track clients
    private static final AtomicInteger clientCounter = new AtomicInteger(0);

    public TCPServer(int port) {
        this.port = port;
    }

    public void start() {

        log.info("Starting ZyraDB TCP Server on port {}", port);

        try (ServerSocket serverSocket = new ServerSocket(port)) {

            while (true) {

                Socket clientSocket = serverSocket.accept();
                int clientId = clientCounter.incrementAndGet();

                log.info("[Client-{}] Connected from {}",
                        clientId, clientSocket.getInetAddress());

                // ✅ TCPServer does NOT know about Store or Service
                new Thread(new ClientHandler(clientSocket, clientId)).start();
            }

        } catch (IOException e) {
            log.error("TCP Server error", e);
        }
    }
}