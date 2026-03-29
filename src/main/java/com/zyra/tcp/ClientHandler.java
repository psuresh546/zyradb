package com.zyra.tcp;

import com.zyra.parser.Command;
import com.zyra.parser.CommandParser;
import com.zyra.service.KeyValueService;
import com.zyra.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    private final Socket socket;
    private final int clientId;
    private final Runnable onDisconnect;

    private final KeyValueService service;
    private final CommandParser parser;

    public ClientHandler(Socket socket, int clientId) {
        this(socket, clientId, new KeyValueService(InMemoryStore.getInstance()), new CommandParser(), () -> { });
    }

    public ClientHandler(Socket socket, int clientId, Runnable onDisconnect) {
        this(socket, clientId, new KeyValueService(InMemoryStore.getInstance()), new CommandParser(), onDisconnect);
    }

    public ClientHandler(Socket socket, int clientId, KeyValueService service, CommandParser parser, Runnable onDisconnect) {
        this.socket = socket;
        this.clientId = clientId;
        this.service = service;
        this.parser = parser;
        this.onDisconnect = onDisconnect;
    }

    @Override
    public void run() {

        log.info("[Client-{}] Thread started: {}", clientId, Thread.currentThread().getName());

        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter writer = new PrintWriter(
                        new BufferedWriter(
                                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)),
                        true)
        ) {

            String input;

            while ((input = reader.readLine()) != null) {

                input = input.trim();

                if (input.isEmpty()) {
                    continue;
                }

                log.info("[Client-{}] Input: {}", clientId, input);

                try {
                    Command command = parser.parse(input);
                    String response = service.execute(command);

                    writer.println(response);

                    if ("BYE".equals(response)) {
                        log.info("[Client-{}] Connection closed by client command", clientId);
                        break;
                    }

                } catch (Exception e) {
                    log.error("[Client-{}] Command error: {}", clientId, e.getMessage());
                    writer.println("ERR " + e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[Client-{}] Socket error: {}", clientId, e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            onDisconnect.run();
        }

        log.info("[Client-{}] Thread terminated", clientId);
    }
}
