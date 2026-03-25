package com.zyra.service;

import com.zyra.parser.Command;
import com.zyra.store.InMemoryStore;

public class KeyValueService {

    private final InMemoryStore store = InMemoryStore.getInstance();

    public String execute(Command command) {

        if (command == null) {
            return "ERROR: Empty command";
        }

        String name = command.getName();

        switch (name) {
            case "SET":
                return handleSet(command);

            case "GET":
                return handleGet(command);

            case "DELETE":
                return handleDelete(command);

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

        if (command.getArgs().size() < 1) {
            return "ERROR: GET requires key";
        }

        String key = command.getArgs().get(0);

        String value = store.get(key);

        return value != null ? value : "NULL";
    }

    // 🔥 NEW
    private String handleDelete(Command command) {

        if (command.getArgs().size() < 1) {
            return "ERROR: DELETE requires key";
        }

        String key = command.getArgs().get(0);

        boolean deleted = store.delete(key);

        return deleted ? "1" : "0";
    }
}