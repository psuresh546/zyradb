package com.zyra.tcp;

import com.zyra.parser.CommandParser;
import com.zyra.service.KeyValueService;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class TCPServer {

    private final int port;
    private final KeyValueService service = new KeyValueService();
    private final CommandParser parser = new CommandParser();

    public TCPServer(int port) {
        this.port = port;
    }

    public void start() {
        new Thread(this::runServer).start();
        System.out.println("ZyraDB TCP Server started on port " + port);
    }

    private void runServer() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {

            while (true) {
                Socket client = serverSocket.accept();
                new Thread(() -> handleClient(client)).start();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleClient(Socket client) {
        System.out.println("Client connected: " + client.getRemoteSocketAddress());

        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(client.getInputStream()));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(client.getOutputStream()))
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

        } catch (IOException ignored) {
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {}
            System.out.println("Client disconnected");
        }
    }
}
