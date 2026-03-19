package com.zyra.tcp;

/*
    Intuition:
    ----------
    Each client connection gets its own handler.

    Responsibilities:
        - Read input from client
        - Process it (for now: echo)
        - Send response

    This isolates client logic and improves modularity.
*/

import java.io.*;
import java.net.Socket;

public class ClientHandler implements Runnable {

    private final Socket socket;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {

        System.out.println("Handling client in thread: " + Thread.currentThread().getName());

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

                System.out.println("[" + Thread.currentThread().getName() + "] Received: " + line);

                // Echo response
                writer.write("Echo: " + line);
                writer.newLine();
                writer.flush();
            }

        } catch (IOException e) {
            System.out.println("Client disconnected: " + socket.getInetAddress());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}