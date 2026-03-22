package com.zyra.tcp;

import java.io.*;
import java.net.Socket;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final int clientId;

    public ClientHandler(Socket socket, int clientId) {
        this.socket = socket;
        this.clientId = clientId;
    }

    @Override
    public void run() {

        System.out.println("[Client-" + clientId + "] Handling in thread: "
                + Thread.currentThread().getName());

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

                System.out.println("[Client-" + clientId + "] Received: " + line);

                // Echo response with client ID
                writer.write("[Client-" + clientId + "] Echo: " + line);
                writer.newLine();
                writer.flush();
            }

        } catch (IOException e) {
            System.out.println("[Client-" + clientId + "] Disconnected");
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}