package com.zyra.store;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Map;

public class SnapshotManager {

    private static final String SNAPSHOT_FILE = "zyra.snapshot";
    private static final String TEMP_FILE = "zyra.snapshot.tmp";
    private static final Path TEMP_PATH = Paths.get(TEMP_FILE);
    private static final Path FINAL_PATH = Paths.get(SNAPSHOT_FILE);

    public static synchronized boolean save(InMemoryStore store) {
        store.writeLock().lock();
        try {
            Map<String, InMemoryStore.ValueWrapper> data = store.snapshot();

            try (BufferedWriter writer = Files.newBufferedWriter(
                    TEMP_PATH,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                long now = System.currentTimeMillis();

                for (Map.Entry<String, InMemoryStore.ValueWrapper> entry : data.entrySet()) {
                    InMemoryStore.ValueWrapper value = entry.getValue();

                    if (value.getExpiryTime() != -1 && value.getExpiryTime() <= now) {
                        continue;
                    }

                    writer.write(encode(entry.getKey()) + "|" +
                            encode(value.getValue()) + "|" +
                            value.getExpiryTime());
                    writer.newLine();
                }

                writer.flush();
            } catch (IOException e) {
                e.printStackTrace();
                deleteTempFileQuietly();
                return false;
            }

            try {
                moveSnapshotIntoPlace();
                WriteAheadLog.reset();
                return true;
            } catch (IOException | RuntimeException e) {
                e.printStackTrace();
                deleteTempFileQuietly();
                return false;
            }
        } finally {
            store.writeLock().unlock();
        }
    }

    public static void load(InMemoryStore store) {
        Path path = FINAL_PATH;
        if (!Files.exists(path)) return;

        store.writeLock().lock();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", 3);
                if (parts.length != 3) continue;

                try {
                    store.restore(
                            decode(parts[0]),
                            decode(parts[1]),
                            Long.parseLong(parts[2])
                    );
                } catch (RuntimeException e) {
                    // Skip malformed snapshot lines and continue loading the rest.
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            store.writeLock().unlock();
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void deleteTempFileQuietly() {
        try {
            Files.deleteIfExists(TEMP_PATH);
        } catch (IOException ignored) {
        }
    }

    private static void moveSnapshotIntoPlace() throws IOException {
        IOException lastFailure = null;

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.move(TEMP_PATH, FINAL_PATH,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException atomicMoveFailure) {
                lastFailure = atomicMoveFailure;

                try {
                    Files.move(TEMP_PATH, FINAL_PATH, StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (IOException replaceFailure) {
                    lastFailure = replaceFailure;
                    if (attempt < 2) {
                        try {
                            Thread.sleep(25L);
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                            throw replaceFailure;
                        }
                    }
                }
            }
        }

        throw lastFailure;
    }
}
