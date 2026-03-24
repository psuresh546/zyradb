package com.zyra.tcp;

import com.zyra.parser.Command;
import com.zyra.parser.CommandParser;
import com.zyra.store.InMemoryStore;

import java.io.*;
import java.net.Socket;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final int clientId;

    private final CommandParser parser = new CommandParser();
    private final InMemoryStore store = InMemoryStore.getInstance();

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

                String response;

                if (command == null) {
                    response = "ERROR: Empty command";
                } else {
                    response = handleCommand(command);
                }

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

        store.set(key, value);

        return "OK";
    }

    private String handleGet(Command command) {

        if (command.getArgs().isEmpty()) {
            return "ERROR: GET requires key";
        }

        String key = command.getArgs().get(0);

        String value = store.get(key);

        return value != null ? value : "NULL";
    }
}