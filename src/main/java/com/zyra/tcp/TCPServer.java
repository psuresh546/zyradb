package com.zyra.tcp;

/*
    Intuition:
    ----------
    Convert single-client blocking server into multi-client server.

    Instead of handling client in main thread:
        → Spawn a new thread per client

    This ensures:
        - Server keeps accepting new connections
        - Each client runs independently
*/

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class TCPServer {

    private final int port;

    public TCPServer(int port) {
        this.port = port;
    }

    public void start() {
        System.out.println("Starting ZyraDB TCP Server on port " + port);

        try (ServerSocket serverSocket = new ServerSocket(port)) {

            while (true) {
                System.out.println("Waiting for client...");

                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress());

                // 🔥 Spawn new thread
                ClientHandler handler = new ClientHandler(clientSocket);
                new Thread(handler).start();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}