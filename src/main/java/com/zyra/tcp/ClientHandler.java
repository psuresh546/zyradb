package com.zyra.tcp;

import com.zyra.parser.Command;
import com.zyra.parser.CommandParser;
import com.zyra.service.KeyValueService;

import java.io.*;
import java.net.Socket;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final int clientId;

    private final CommandParser parser = new CommandParser();
    private final KeyValueService service = new KeyValueService();

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

                System.out.println("[Client-" + clientId + "] Raw Input: " + line);

                Command command = parser.parse(line);

                // 🔥 Delegate to service layer
                String response = service.execute(command);

                writer.write(response);
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