package com.zyra.store;

import com.zyra.parser.Command;
import com.zyra.parser.CommandParser;

import java.util.List;
import java.util.Locale;

/**
 * Replays raw command-text entries from the WAL into the store.
 *
 * <p>This path is only reached for WAL entries that were written as raw command
 * text (e.g. by older versions). The primary WAL replay path in
 * {@link WriteAheadLog#replay(InMemoryStore)} handles the binary-encoded format
 * and should be preferred.
 *
 * <p>Fix vs original: {@code replaySet()} now calls {@code store.restore()} with
 * an absolute expiry timestamp, NOT {@code store.set()} with a relative TTL.
 * For the modern encoded WAL format the absolute timestamp is stored directly.
 * For legacy raw command-text entries, relative TTLs are anchored to the WAL
 * file's last-modified time captured before replay. That keeps restart recovery
 * from granting keys a fresh full TTL, even though older raw WALs cannot
 * preserve per-entry timing perfectly.
 */
public final class CommandExecutor {

    private static final CommandParser PARSER = new CommandParser();

    private CommandExecutor() {}

    public static void replay(String line, InMemoryStore store) {
        replay(line, store, System.currentTimeMillis());
    }

    public static void replay(String line, InMemoryStore store, long replayReferenceTimeMillis) {
        Command command = PARSER.parse(line);
        if (command == null || command.getName() == null) {
            return;
        }

        String       name = command.getName().toUpperCase(Locale.ROOT);
        List<String> args = command.getArgs();

        switch (name) {
            case "SET"    -> replaySet(args, store, replayReferenceTimeMillis);
            case "DEL"    -> replayDelete(args, store);
            case "EXPIRE" -> replayExpire(args, store, replayReferenceTimeMillis);
            default       -> {} // Unknown commands are silently skipped during replay.
        }
    }

    // -------------------------------------------------------------------------
    // SET replay
    // -------------------------------------------------------------------------

    /**
     * Replays a SET command.
     *
     * <p>The WAL stores entries in the binary format:
     * {@code SET|BASE64(key)|BASE64(value)|absoluteExpiryMillis}
     *
     * <p>When this method is called with a raw command-text line (e.g. from
     * a legacy WAL), the EX argument is a relative number of seconds. We convert
     * it to an absolute expiry anchored to the WAL file's last-modified time and
     * call {@code store.restore()} so replay does not restart the TTL from "now".
     */
    private static void replaySet(List<String> args, InMemoryStore store, long replayReferenceTimeMillis) {
        if (args.size() < 2) {
            return;
        }

        String key   = args.get(0);
        String value = args.get(1);

        // Default: no expiry.
        long expiryTime = -1;

        if (args.size() > 2) {
            if (args.size() != 4) {
                return;
            }
            String option = args.get(2).toUpperCase(Locale.ROOT);
            if (!option.equals("EX")) {
                return;
            }
            try {
                long ttlSeconds = Long.parseLong(args.get(3));
                if (ttlSeconds <= 0) {
                    return;
                }
                expiryTime = toLegacyAbsoluteExpiryTimeMillis(ttlSeconds, replayReferenceTimeMillis);
            } catch (NumberFormatException e) {
                return;
            }
        }

        // Use restore() - not set() - so we pass the absolute expiryTime directly
        // rather than re-triggering a fresh TTL calculation inside set().
        store.restore(key, value, expiryTime);
    }

    // -------------------------------------------------------------------------
    // DEL replay
    // -------------------------------------------------------------------------

    private static void replayDelete(List<String> args, InMemoryStore store) {
        if (args.size() != 1) {
            return;
        }
        store.delete(args.get(0));
    }

    // -------------------------------------------------------------------------
    // EXPIRE replay
    // -------------------------------------------------------------------------

    private static void replayExpire(List<String> args, InMemoryStore store, long replayReferenceTimeMillis) {
        if (args.size() != 2) {
            return;
        }
        try {
            long seconds = Long.parseLong(args.get(1));
            if (seconds <= 0) {
                return;
            }

            String key = args.get(0);
            String currentValue = store.get(key);
            if (currentValue == null) {
                return;
            }

            long absoluteExpiryTime = toLegacyAbsoluteExpiryTimeMillis(seconds, replayReferenceTimeMillis);
            store.restore(key, currentValue, absoluteExpiryTime);
        } catch (NumberFormatException e) {
            // Ignore malformed WAL lines during replay.
        }
    }

    private static long toLegacyAbsoluteExpiryTimeMillis(long ttlSeconds, long replayReferenceTimeMillis) {
        return replayReferenceTimeMillis + (ttlSeconds * 1000L);
    }
}
