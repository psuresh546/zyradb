package com.zyra.service;

import com.zyra.parser.Command;
import com.zyra.store.InMemoryStore;

public class KeyValueService {

    private final InMemoryStore store = InMemoryStore.getInstance();

    public String execute(Command command) {

        if (command == null) {
            return "ERROR: Empty command";
        }

        return switch (command.getName()) {
            case "SET" -> handleSet(command);
            case "GET" -> handleGet(command);
            case "DEL" , "DELETE" -> handleDelete(command);
            case "EXPIRE" -> handleExpire(command);
            case "TTL" -> handleTTL(command);
            default -> "ERROR: Unknown command";
        };
    }

    private String handleSet(Command command) {

        if (command.getArgs().size() < 2) {
            return "ERROR: SET requires key and value";
        }

        String key = command.getArgs().get(0);
        String value = command.getArgs().get(1);

        long ttl = -1;

        if (command.getArgs().size() > 2) {

            if (command.getArgs().size() != 4) {
                return "ERROR: Use: SET key value EX seconds";
            }

            if (!command.getArgs().get(2).equalsIgnoreCase("EX")) {
                return "ERROR: Unsupported SET option";
            }

            try {
                ttl = Long.parseLong(command.getArgs().get(3));
                if (ttl <= 0) {
                    return "ERROR: Expiry must be positive";
                }
            } catch (NumberFormatException e) {
                return "ERROR: Invalid expiry seconds";
            }
        }

        store.set(key, value, ttl);
        return "OK";
    }

    private String handleGet(Command command) {
        if (command.getArgs().size() != 1) {
            return "ERROR: GET requires key";
        }

        String val = store.get(command.getArgs().get(0));
        return val == null ? "NULL" : val;
    }

    private String handleDelete(Command command) {
        if (command.getArgs().size() != 1) {
            return "ERROR: DEL requires key";
        }

        return store.delete(command.getArgs().get(0)) ? "1" : "0";
    }

    private String handleExpire(Command command) {

        if (command.getArgs().size() != 2) {
            return "ERROR: EXPIRE requires key and seconds";
        }

        try {
            long seconds = Long.parseLong(command.getArgs().get(1));
            if (seconds <= 0) {
                return "ERROR: Expiry must be positive";
            }

            return store.expire(command.getArgs().get(0), seconds) ? "1" : "0";

        } catch (NumberFormatException e) {
            return "ERROR: Invalid seconds value";
        }
    }

    private String handleTTL(Command command) {
        if (command.getArgs().size() != 1) {
            return "ERROR: TTL requires key";
        }

        return String.valueOf(store.ttl(command.getArgs().get(0)));
    }
}