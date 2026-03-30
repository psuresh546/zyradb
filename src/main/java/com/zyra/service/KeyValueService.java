package com.zyra.service;

import com.zyra.parser.Command;
import com.zyra.scheduler.ExpiryScheduler;
import com.zyra.store.InMemoryStore;
import com.zyra.store.WriteAheadLog;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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

        String name = command.getName().toUpperCase();

        return switch (name) {
            case "SET" -> handleSet(command);
            case "GET" -> handleGet(command);
            case "DEL", "DELETE" -> handleDelete(command);
            case "EXPIRE", "EXP", "EX" -> handleExpire(command);
            case "TTL" -> handleTTL(command);
            case "INFO" -> handleInfo(command);
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

        long expiryTime = ttl > 0
                ? System.currentTimeMillis() + (ttl * 1000)
                : -1;

        ReentrantReadWriteLock.WriteLock writeLock = store.writeLock();
        writeLock.lock();
        try {
            WriteAheadLog.logSet(key, value, expiryTime);
            store.restore(key, value, expiryTime);
            return "OK";
        } finally {
            writeLock.unlock();
        }
    }

    // ----------------------------------------------------
    // GET key
    // ----------------------------------------------------
    private String handleGet(Command command) {

        if (command.getArgs().size() != 1) {
            return "ERR GET requires key";
        }

        ReentrantReadWriteLock.ReadLock readLock = store.readLock();
        readLock.lock();
        try {
            String value = store.get(command.getArgs().get(0));
            return value != null ? "VAL " + value : "NIL";
        } finally {
            readLock.unlock();
        }
    }

    // ----------------------------------------------------
    // DEL key
    // ----------------------------------------------------
    private String handleDelete(Command command) {

        if (command.getArgs().size() != 1) {
            return "ERR DEL requires key";
        }

        String key = command.getArgs().get(0);
        ReentrantReadWriteLock.WriteLock writeLock = store.writeLock();
        writeLock.lock();
        try {
            WriteAheadLog.logDelete(key);
            boolean deleted = store.delete(key);
            return "INT " + (deleted ? 1 : 0);
        } finally {
            writeLock.unlock();
        }
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

            String key = command.getArgs().get(0);
            long expiryTime = System.currentTimeMillis() + (seconds * 1000);
            ReentrantReadWriteLock.WriteLock writeLock = store.writeLock();
            writeLock.lock();
            try {
                String value = store.get(key);
                if (value == null) {
                    return "INT 0";
                }

                WriteAheadLog.logSet(key, value, expiryTime);
                store.restore(key, value, expiryTime);
                return "INT 1";
            } finally {
                writeLock.unlock();
            }

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

        ReentrantReadWriteLock.ReadLock readLock = store.readLock();
        readLock.lock();
        try {
            long ttl = store.ttl(command.getArgs().get(0));
            return "INT " + ttl;
        } finally {
            readLock.unlock();
        }
    }

    private String handleInfo(Command command) {
        if (!command.getArgs().isEmpty()) {
            return "ERR INFO does not accept arguments";
        }

        return "INFO keys=" + store.size() + " uptime=" + ExpiryScheduler.uptimeSeconds();
    }
}
