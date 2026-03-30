package com.zyra.store;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

public class WriteAheadLog {

    private static final String WAL_FILE = "zyra.wal";
    private static final Path WAL_PATH = Path.of(WAL_FILE);

    private static FileOutputStream fos;
    private static BufferedWriter writer;
    private static FileChannel channel;

    private static synchronized void init() throws IOException {
        if (writer != null) {
            return;
        }

        fos = new FileOutputStream(WAL_PATH.toFile(), true);
        channel = fos.getChannel();
        writer = new BufferedWriter(
                new OutputStreamWriter(fos, StandardCharsets.UTF_8)
        );
    }

    public static synchronized void logSet(String key, String value, long expiryTime) {
        try {
            init();
            writer.write("SET|" + encode(key) + "|" + encode(value) + "|" + expiryTime);
            writer.newLine();
            writer.flush();
            channel.force(true);
        } catch (IOException e) {
            throw new RuntimeException("WAL write failed", e);
        }
    }

    public static synchronized void logDelete(String key) {
        try {
            init();
            writer.write("DEL|" + encode(key));
            writer.newLine();
            writer.flush();
            channel.force(true);
        } catch (IOException e) {
            throw new RuntimeException("WAL write failed", e);
        }
    }

    public static synchronized void replay(InMemoryStore store) {
        if (!Files.exists(WAL_PATH)) {
            return;
        }

        store.writeLock().lock();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(WAL_PATH.toFile()),
                        StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    replayLine(line, store);
                } catch (Exception e) {
                    System.err.println("Skipping corrupted WAL entry: " + line);
                }
            }

        } catch (IOException e) {
            System.err.println("WAL replay failed, skipping recovery from WAL: " + e.getMessage());
        } finally {
            store.writeLock().unlock();
        }
    }

    public static synchronized void reset() {
        close();

        try {
            Files.deleteIfExists(WAL_PATH);
            Files.writeString(
                    WAL_PATH,
                    "",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            throw new RuntimeException("WAL truncate failed", e);
        }
    }

    public static synchronized void truncate() {
        reset();
    }

    private static void replayLine(String line, InMemoryStore store) {
        String[] parts = line.split("\\|", 4);
        if (parts.length == 0) {
            return;
        }

        switch (parts[0]) {
            case "SET" -> replaySet(parts, store);
            case "DEL" -> replayDelete(parts, store);
            default -> CommandExecutor.replay(line, store);
        }
    }

    private static void replaySet(String[] parts, InMemoryStore store) {
        if (parts.length != 4) {
            return;
        }

        try {
            store.restore(
                    decode(parts[1]),
                    decode(parts[2]),
                    Long.parseLong(parts[3])
            );
        } catch (IllegalArgumentException e) {
            // Ignore malformed WAL lines and continue replaying the rest.
        }
    }

    private static void replayDelete(String[] parts, InMemoryStore store) {
        if (parts.length < 2) {
            return;
        }

        try {
            store.delete(decode(parts[1]));
        } catch (IllegalArgumentException e) {
            // Ignore malformed WAL lines and continue replaying the rest.
        }
    }

    public static synchronized void close() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (fos != null) {
                fos.close();
            }
        } catch (IOException ignored) {
        }
        writer = null;
        channel = null;
        fos = null;
    }

    public static synchronized void shutdown() {
        close();
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
