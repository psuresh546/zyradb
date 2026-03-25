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

    private final CommandParser parser = new CommandParser();
    private final KeyValueService service = new KeyValueService();

    public ClientHandler(Socket socket, int clientId) {
        this.socket = socket;
        this.clientId = clientId;
    }

    @Override
    public void run() {

        log.info("[Client-{}] Handling in thread: {}", clientId, Thread.currentThread().getName());

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

                log.info("[Client-{}] Raw Input: {}", clientId, line);

                Command command = parser.parse(line);

                String response = service.execute(command);

                writer.write(response);
                writer.newLine();
                writer.flush();
            }

        } catch (IOException e) {
            log.info("[Client-{}] Disconnected", clientId);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}