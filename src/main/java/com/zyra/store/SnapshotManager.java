package com.zyra.store;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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

    public static boolean save(InMemoryStore store) {
        Path tempPath = Paths.get(TEMP_FILE);
        Path finalPath = Paths.get(SNAPSHOT_FILE);
        Map<String, InMemoryStore.ValueWrapper> data = store.snapshot();

        try (BufferedWriter writer = Files.newBufferedWriter(
                tempPath,
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
            return false;
        }

        try {
            Files.move(tempPath, finalPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            WriteAheadLog.reset();
            return true;
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
                WriteAheadLog.reset();
                return true;
            } catch (IOException | RuntimeException moveException) {
                moveException.printStackTrace();
                return false;
            }
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void load(InMemoryStore store) {
        Path path = Paths.get(SNAPSHOT_FILE);
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
}
