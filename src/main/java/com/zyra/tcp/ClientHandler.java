package com.zyra.tcp;

import com.zyra.parser.Command;
import com.zyra.parser.CommandParser;
import com.zyra.service.KeyValueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class ClientHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    private final Socket socket;
    private final int clientId;

    private final KeyValueService service = new KeyValueService();
    private final CommandParser parser = new CommandParser();

    public ClientHandler(Socket socket, int clientId) {
        this.socket = socket;
        this.clientId = clientId;
    }

    @Override
    public void run() {

        log.info("[Client-{}] Thread started: {}", clientId, Thread.currentThread().getName());

        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)
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

                    // ✅ Proper connection termination
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
            } catch (Exception ignored) {}
        }

        log.info("[Client-{}] Thread terminated", clientId);
    }
}