package com.zyra.service;

import com.zyra.parser.Command;
import com.zyra.scheduler.ExpiryScheduler;
import com.zyra.store.InMemoryStore;
import com.zyra.store.WriteAheadLog;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.Lock;

@Service
public class KeyValueService {

    private final InMemoryStore store;

    public KeyValueService(InMemoryStore store) {
        this.store = store;
    }

    public String execute(Command command) {
        if (command == null || command.getName() == null) {
            return "ERR empty command";
        }

        String name = command.getName();
        if (!name.isEmpty()) {
            char first = name.charAt(0);
            if (first >= 'a' && first <= 'z') {
                name = name.toUpperCase(Locale.ROOT);
            }
        }

        return switch (name) {
            case "SET" -> handleSet(command);
            case "GET" -> handleGet(command);
            case "DEL" -> handleDelete(command);
            case "EXPIRE" -> handleExpire(command);
            case "TTL" -> handleTTL(command);
            case "INFO" -> handleInfo(command);
            case "QUIT" -> "BYE";
            default -> "ERR unknown command";
        };
    }

    private String handleSet(Command command) {
        List<String> args = command.getArgs();

        if (args.size() < 2) {
            return "ERR SET requires key and value";
        }
        if (args.size() != 2 && args.size() != 4) {
            return "ERR invalid SET syntax. Use: SET key value [EX seconds]";
        }

        String key = args.get(0);
        String value = args.get(1);
        long ttl = -1;

        if (args.size() == 4) {
            String option = args.get(2).toUpperCase(Locale.ROOT);
            if (!option.equals("EX")) {
                return "ERR only EX option supported";
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

        long ttlSeconds = ttl;
        return withKeyMutation(key, () -> {
            long expiryTime = ttlSeconds > 0
                    ? System.currentTimeMillis() + (ttlSeconds * 1000L)
                    : -1;
            WriteAheadLog.logSet(key, value, expiryTime);
            store.restoreWhileHoldingKeyLock(key, value, expiryTime);
            return "OK";
        });
    }

    private String handleGet(Command command) {
        if (command.getArgs().size() != 1) {
            return "ERR GET requires key";
        }

        String value = store.get(command.getArgs().get(0));
        return value != null ? "VAL " + value : "NIL";
    }

    private String handleDelete(Command command) {
        if (command.getArgs().size() != 1) {
            return "ERR DEL requires key";
        }

        String key = command.getArgs().get(0);
        return withKeyMutation(key, () -> {
            WriteAheadLog.logDelete(key);
            boolean deleted = store.deleteWhileHoldingKeyLock(key);
            return "INT " + (deleted ? 1 : 0);
        });
    }

    private String handleExpire(Command command) {
        if (command.getArgs().size() != 2) {
            return "ERR EXPIRE requires key and seconds";
        }

        long seconds;
        try {
            seconds = Long.parseLong(command.getArgs().get(1));
            if (seconds <= 0) {
                return "ERR seconds must be > 0";
            }
        } catch (NumberFormatException e) {
            return "ERR invalid seconds value";
        }

        String key = command.getArgs().get(0);
        long ttlSeconds = seconds;

        return withKeyMutation(key, () -> {
            String currentValue = store.getWhileHoldingKeyLock(key);
            if (currentValue == null) {
                return "INT 0";
            }

            long absoluteExpiry = System.currentTimeMillis() + (ttlSeconds * 1000L);
            WriteAheadLog.logSet(key, currentValue, absoluteExpiry);
            store.restoreWhileHoldingKeyLock(key, currentValue, absoluteExpiry);
            return "INT 1";
        });
    }

    private String handleTTL(Command command) {
        if (command.getArgs().size() != 1) {
            return "ERR TTL requires key";
        }

        long ttl = store.ttl(command.getArgs().get(0));
        return "INT " + ttl;
    }

    private String handleInfo(Command command) {
        if (!command.getArgs().isEmpty()) {
            return "ERR INFO does not accept arguments";
        }

        return "INFO keys=" + store.size() + " uptime=" + ExpiryScheduler.uptimeSeconds();
    }

    private String withKeyMutation(String key, CommandAction action) {
        Lock keyLock = store.keyLock(key);
        keyLock.lock();
        try {
            return action.run();
        } finally {
            keyLock.unlock();
        }
    }

    @FunctionalInterface
    private interface CommandAction {
        String run();
    }

}
