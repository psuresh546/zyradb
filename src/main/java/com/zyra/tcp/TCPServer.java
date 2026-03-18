package com.zyra.tcp;

/*
    Intuition:
    ----------
    We are building the lowest-level communication layer.

    - ServerSocket listens for incoming TCP connections
    - Once a client connects, we accept it
    - Read input from client
    - Echo response back (for testing)

    This acts as the foundation for Redis-like protocol handling later.
*/

import java.io.*;
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

                handleClient(clientSocket);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleClient(Socket socket) {

        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream())
                );
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream())
                )
        ) {
            String line;

            while ((line = reader.readLine()) != null) {

                System.out.println("Received: " + line);

                // Echo response
                writer.write("Echo: " + line);
                writer.newLine();
                writer.flush();
            }

        } catch (IOException e) {
            System.out.println("Client disconnected.");
        }
    }
}