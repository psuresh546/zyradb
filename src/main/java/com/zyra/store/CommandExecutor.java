package com.zyra.store;

import com.zyra.parser.Command;
import com.zyra.parser.CommandParser;

import java.util.List;

public final class CommandExecutor {

    private static final CommandParser PARSER = new CommandParser();

    private CommandExecutor() {
    }

    public static void replay(String line, InMemoryStore store) {
        Command command = PARSER.parse(line);
        if (command == null || command.getName() == null) {
            return;
        }

        String name = command.getName().toUpperCase();
        List<String> args = command.getArgs();

        switch (name) {
            case "SET" -> replaySet(args, store);
            case "DEL" -> replayDelete(args, store);
            case "EXPIRE" -> replayExpire(args, store);
            default -> {
            }
        }
    }

    private static void replaySet(List<String> args, InMemoryStore store) {
        if (args.size() < 2) {
            return;
        }

        String key = args.get(0);
        String value = args.get(1);
        long ttl = -1;

        if (args.size() > 2) {
            if (args.size() != 4) {
                return;
            }

            String option = args.get(2).toUpperCase();
            if (!option.equals("EX")) {
                return;
            }

            try {
                ttl = Long.parseLong(args.get(3));
                if (ttl <= 0) {
                    return;
                }
            } catch (NumberFormatException e) {
                return;
            }
        }

        store.set(key, value, ttl);
    }

    private static void replayDelete(List<String> args, InMemoryStore store) {
        if (args.size() != 1) {
            return;
        }

        store.delete(args.get(0));
    }

    private static void replayExpire(List<String> args, InMemoryStore store) {
        if (args.size() != 2) {
            return;
        }

        try {
            long seconds = Long.parseLong(args.get(1));
            if (seconds <= 0) {
                return;
            }

            store.expire(args.get(0), seconds);
        } catch (NumberFormatException e) {
            // Ignore malformed WAL lines during replay.
        }
    }
}
