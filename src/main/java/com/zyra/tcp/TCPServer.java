package com.zyra.tcp;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

public class TCPServer {

    private final int port;

    // 🔥 Thread-safe client ID generator
    private static final AtomicInteger clientCounter = new AtomicInteger(0);

    public TCPServer(int port) {
        this.port = port;
    }

    public void start() {
        System.out.println("Starting ZyraDB TCP Server on port " + port);

        try (ServerSocket serverSocket = new ServerSocket(port)) {

            while (true) {
                System.out.println("Waiting for client...");

                Socket clientSocket = serverSocket.accept();

                // 🔥 Assign unique client ID
                int clientId = clientCounter.incrementAndGet();

                System.out.println("[Client-" + clientId + "] Connected: " + clientSocket.getInetAddress());

                ClientHandler handler = new ClientHandler(clientSocket, clientId);
                new Thread(handler).start();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}