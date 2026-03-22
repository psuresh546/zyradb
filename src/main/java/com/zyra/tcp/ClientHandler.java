package com.zyra.tcp;

import com.zyra.parser.Command;
import com.zyra.parser.CommandParser;

import java.io.*;
import java.net.Socket;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final int clientId;

    private final CommandParser parser = new CommandParser();

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

                if (command == null) {
                    writer.write("ERROR: Empty command");
                } else {
                    writer.write(handleCommand(command));
                }

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

    private String handleCommand(Command command) {

        String name = command.getName();

        switch (name) {
            case "SET":
                return handleSet(command);

            case "GET":
                return handleGet(command);

            default:
                return "ERROR: Unknown command";
        }
    }

    private String handleSet(Command command) {

        if (command.getArgs().size() < 2) {
            return "ERROR: SET requires key and value";
        }

        String key = command.getArgs().get(0);
        String value = command.getArgs().get(1);

        // No storage yet
        return "OK (SET parsed): " + key + " = " + value;
    }

    private String handleGet(Command command) {

        if (command.getArgs().isEmpty()) {
            return "ERROR: GET requires key";
        }

        // String key = command.getArgs().get(0);
        String key = command.getArgs().getFirst();

        // No storage yet
        return "OK (GET parsed): " + key;
    }
}