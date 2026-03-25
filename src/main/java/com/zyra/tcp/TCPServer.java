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

    private static final AtomicInteger clientCounter = new AtomicInteger(0);

    public TCPServer(int port) {
        this.port = port;
    }

    public void start() {

        log.info("Starting ZyraDB TCP Server on port {}", port);

        try (ServerSocket serverSocket = new ServerSocket(port)) {

            while (true) {

                log.info("Waiting for client...");

                Socket clientSocket = serverSocket.accept();

                int clientId = clientCounter.incrementAndGet();

                log.info("[Client-{}] Connected: {}", clientId, clientSocket.getInetAddress());

                ClientHandler handler = new ClientHandler(clientSocket, clientId);
                new Thread(handler).start();
            }

        } catch (IOException e) {
            log.error("Server error", e);
        }
    }
}