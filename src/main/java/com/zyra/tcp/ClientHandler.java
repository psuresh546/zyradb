package com.zyra.tcp;

import com.zyra.parser.Command;
import com.zyra.parser.CommandParser;
import com.zyra.service.KeyValueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;

public class ClientHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    private final Socket socket;
    private final int clientId;

    private final KeyValueService service = new KeyValueService();
    private final CommandParser parser = new CommandParser(); // ✅

    public ClientHandler(Socket socket, int clientId) {
        this.socket = socket;
        this.clientId = clientId;
    }

    @Override
    public void run() {
        log.info("[Client-{}] Thread started: {}", clientId, Thread.currentThread().getName());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

            String input;
            CommandParser parser = new CommandParser();

            while ((input = reader.readLine()) != null) {

                input = input.trim();
                log.info("[Client-{}] Input: {}", clientId, input);

                // ✅ Connection commands
                if (input.equalsIgnoreCase("EXIT") || input.equalsIgnoreCase("QUIT")) {
                    writer.println("BYE");
                    break;
                }

                try {
                    Command command = parser.parse(input);
                    String response = service.execute(command);
                    writer.println(response);
                } catch (Exception e) {
                    writer.println("ERROR: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[Client-{}] Error: {}", clientId, e.getMessage());
        }
    }
}