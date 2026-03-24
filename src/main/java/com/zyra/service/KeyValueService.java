package com.zyra.service;

import com.zyra.parser.Command;
import com.zyra.store.InMemoryStore;

/*
    Intuition:
    ----------
    Handles business logic for all commands.

    Keeps TCP layer clean and focused only on IO.

    Future:
        - DELETE
        - EXPIRE
        - TTL handling
*/

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
}