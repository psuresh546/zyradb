package com.zyra.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.locks.Lock;

/**
 * Handles point-in-time snapshot persistence for the {@link InMemoryStore}.
 *
 * <p>Snapshot format: one entry per line, pipe-delimited:
 * <pre>BASE64(key)|BASE64(value)|absoluteExpiryTimeMillis</pre>
 * An expiry of {@code -1} means the key has no TTL.
 *
 * <p>Writes are atomic: the snapshot is written to a temp file first, then
 * moved into place. A 3-attempt retry handles transient filesystem errors.
 *
 * <p>Thread safety: {@code save()} and {@code load()} each acquire the
 * store-wide write lock (all key stripes) to guarantee a consistent view.
 */
public class SnapshotManager {

    private static final Logger log = LoggerFactory.getLogger(SnapshotManager.class);

    private static final String SNAPSHOT_FILE = "zyra.snapshot";
    private static final String TEMP_FILE     = "zyra.snapshot.tmp";
    private static final Path   TEMP_PATH     = Paths.get(TEMP_FILE);
    private static final Path   FINAL_PATH    = Paths.get(SNAPSHOT_FILE);

    private SnapshotManager() {}

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    /**
     * Saves a consistent snapshot of the store to disk, then resets the WAL.
     *
     * @return {@code true} if the snapshot was saved and the WAL reset successfully
     */
    public static synchronized boolean save(InMemoryStore store) {
        Lock writeLock = store.writeLock();
        writeLock.lock();
        try {
            Map<String, InMemoryStore.ValueWrapper> data = store.snapshot();

            try (FileChannel snapshotChannel = FileChannel.open(
                    TEMP_PATH,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
                 BufferedWriter writer = new BufferedWriter(
                         Channels.newWriter(snapshotChannel, StandardCharsets.UTF_8))) {

                long now = System.currentTimeMillis();

                for (Map.Entry<String, InMemoryStore.ValueWrapper> entry : data.entrySet()) {
                    InMemoryStore.ValueWrapper value = entry.getValue();

                    if (value.getExpiryTime() != -1 && value.getExpiryTime() <= now) {
                        continue;
                    }

                    writer.write(encode(entry.getKey())
                            + "|" + encode(value.getValue())
                            + "|" + value.getExpiryTime());
                    writer.newLine();
                }

                writer.flush();
                snapshotChannel.force(true);

            } catch (IOException e) {
                log.error("Snapshot write failed", e);
                deleteTempFileQuietly();
                return false;
            }

            try {
                moveSnapshotIntoPlace();
                forceSnapshotFile();
                WriteAheadLog.reset();
                return true;
            } catch (IOException | RuntimeException e) {
                log.error("Snapshot move/reset failed", e);
                deleteTempFileQuietly();
                return false;
            }

        } finally {
            writeLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    /**
     * Loads a previously saved snapshot into the store.
     *
     * <p>Malformed lines are skipped — a partial snapshot is always better than
     * no snapshot. The write lock is held for the entire load to prevent reads
     * from observing a half-populated store.
     *
     * <p>Bug fix vs original: the write lock is acquired <em>inside</em> a
     * try/finally so that an {@link IOException} from {@code newBufferedReader}
     * (e.g. permissions, file corrupt) does not leak the lock permanently.
     */
    public static void load(InMemoryStore store) {
        if (!Files.exists(FINAL_PATH)) {
            return;
        }

        Lock writeLock = store.writeLock();
        writeLock.lock();
        try (BufferedReader reader = Files.newBufferedReader(FINAL_PATH, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", 3);
                if (parts.length != 3) {
                    continue;
                }
                try {
                    store.restore(
                            decode(parts[0]),
                            decode(parts[1]),
                            Long.parseLong(parts[2]));
                } catch (RuntimeException e) {
                    // Skip malformed lines and continue loading the rest.
                }
            }
        } catch (IOException e) {
            log.error("Snapshot load failed", e);
        } finally {
            // Always unlock — even if newBufferedReader() threw before the
            // try-with-resources could take ownership of the reader.
            writeLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

    private static void forceSnapshotFile() throws IOException {
        try (FileChannel snapshotChannel = FileChannel.open(FINAL_PATH, StandardOpenOption.WRITE)) {
            snapshotChannel.force(true);
        }
        bestEffortForceParentDirectory(FINAL_PATH);
    }

    private static void bestEffortForceParentDirectory(Path path) {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }

        try (FileChannel directoryChannel = FileChannel.open(parent, StandardOpenOption.READ)) {
            directoryChannel.force(true);
        } catch (IOException ignored) {
            // Some platforms do not allow opening directories as channels.
        }
    }

    /**
     * Moves the temp snapshot into its final location.
     * Tries {@code ATOMIC_MOVE} first; falls back to a plain replace on
     * filesystems that don't support atomicity. Retries up to 3 times
     * with a 25 ms pause between attempts.
     */
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
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw replaceFailure;
                        }
                    }
                }
            }
        }

        throw lastFailure != null
                ? lastFailure
                : new IOException("Failed to move snapshot into place after 3 attempts");
    }
}
