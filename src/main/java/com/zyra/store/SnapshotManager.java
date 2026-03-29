package com.zyra.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

public class SnapshotManager {

    private static final Logger log = LoggerFactory.getLogger(SnapshotManager.class);
    private static final String SNAPSHOT_FILE = "zyra.snapshot";

    private SnapshotManager() {
    }

    public static void save(Map<String, InMemoryStore.ValueWrapper> data) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(SNAPSHOT_FILE))) {
            for (Map.Entry<String, InMemoryStore.ValueWrapper> entry : data.entrySet()) {
                String key = encode(entry.getKey());
                String value = encode(entry.getValue().getValue());
                long expiryTime = entry.getValue().getExpiryTime();

                writer.write(key + "|" + value + "|" + expiryTime);
                writer.newLine();
            }

            log.info("Snapshot saved with {} keys", data.size());
        } catch (IOException e) {
            log.error("Error saving snapshot", e);
        }
    }

    public static void load(InMemoryStore store) {
        File file = new File(SNAPSHOT_FILE);
        if (!file.exists()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", 3);
                if (parts.length != 3) {
                    log.warn("Skipping malformed snapshot entry");
                    continue;
                }

                String key = decode(parts[0]);
                String value = decode(parts[1]);
                long expiry = Long.parseLong(parts[2]);

                store.restore(key, value, expiry);
            }

            log.info("Snapshot loaded");
        } catch (IOException | IllegalArgumentException e) {
            log.error("Error loading snapshot", e);
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
    }
}
