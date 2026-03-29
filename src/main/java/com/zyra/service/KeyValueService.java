package com.zyra.service;

import com.zyra.parser.Command;
import com.zyra.store.InMemoryStore;
import com.zyra.store.WriteAheadLog;

import java.util.List;

public class KeyValueService {

    private final InMemoryStore store = InMemoryStore.getInstance();

    public String execute(Command command) {

        if (command == null || command.getName() == null) {
            return "ERR empty command";
        }

        String name = command.getName().toUpperCase();

        return switch (name) {
            case "SET" -> handleSet(command);
            case "GET" -> handleGet(command);
            case "DEL", "DELETE" -> handleDelete(command);
            case "EXPIRE", "EXP", "EX" -> handleExpire(command);
            case "TTL" -> handleTTL(command);
            case "QUIT", "EXIT" -> "BYE";
            default -> "ERR unknown command";
        };
    }

    // ----------------------------------------------------
    // SET key value [EX seconds]
    // ----------------------------------------------------
    private String handleSet(Command command) {

        List<String> args = command.getArgs();

        if (args.size() < 2) {
            return "ERR SET requires key and value";
        }

        String key = args.get(0);
        String value = args.get(1);

        long ttl = -1;

        if (args.size() > 2) {
            if (args.size() != 4) {
                return "ERR invalid SET syntax. Use: SET key value EX/EXP/EXPIRE seconds";
            }

            String option = args.get(2).toUpperCase();

            if (!(option.equals("EX") || option.equals("EXP") || option.equals("EXPIRE"))) {
                return "ERR only EX/EXP/EXPIRE option supported";
            }

            try {
                ttl = Long.parseLong(args.get(3));
                if (ttl <= 0) {
                    return "ERR expiry must be > 0";
                }
            } catch (NumberFormatException e) {
                return "ERR invalid expiry seconds";
            }
        }

        // -------- WAL FIRST (very important) --------
        WriteAheadLog.log(command.getRaw());

        store.set(key, value, ttl);
        return "OK";
    }

    // ----------------------------------------------------
    // GET key
    // ----------------------------------------------------
    private String handleGet(Command command) {

        if (command.getArgs().size() != 1) {
            return "ERR GET requires key";
        }

        String value = store.get(command.getArgs().get(0));
        return value != null ? "VAL " + value : "NIL";
    }

    // ----------------------------------------------------
    // DEL key
    // ----------------------------------------------------
    private String handleDelete(Command command) {

        if (command.getArgs().size() != 1) {
            return "ERR DEL requires key";
        }

        // -------- WAL FIRST --------
        WriteAheadLog.log(command.getRaw());

        boolean deleted = store.delete(command.getArgs().get(0));
        return "INT " + (deleted ? 1 : 0);
    }

    // ----------------------------------------------------
    // EXPIRE key seconds
    // ----------------------------------------------------
    private String handleExpire(Command command) {

        if (command.getArgs().size() != 2) {
            return "ERR EXPIRE requires key and seconds";
        }

        try {
            long seconds = Long.parseLong(command.getArgs().get(1));
            if (seconds <= 0) {
                return "ERR seconds must be > 0";
            }

            // -------- WAL FIRST --------
            WriteAheadLog.log(command.getRaw());

            boolean success = store.expire(command.getArgs().get(0), seconds);
            return "INT " + (success ? 1 : 0);

        } catch (NumberFormatException e) {
            return "ERR invalid seconds value";
        }
    }

    // ----------------------------------------------------
    // TTL key
    // ----------------------------------------------------
    private String handleTTL(Command command) {

        if (command.getArgs().size() != 1) {
            return "ERR TTL requires key";
        }

        long ttl = store.ttl(command.getArgs().get(0));
        return "INT " + ttl;
    }
}