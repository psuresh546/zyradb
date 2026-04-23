package com.zyra.store;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Component
public class WriteAheadLog {

    private static final int PERIODIC_BUFFER_INITIAL_CAPACITY = 8_192;

    public enum SyncMode {
        ALWAYS,
        PERIODIC
    }

    private static final Logger log = LoggerFactory.getLogger(WriteAheadLog.class);

    private static final String WAL_FILE = "zyra.wal";
    private static final Path WAL_PATH = Path.of(WAL_FILE);
    private static final Encoder BASE64_ENCODER = Base64.getEncoder();
    private static final Decoder BASE64_DECODER = Base64.getDecoder();

    private static FileOutputStream fos;
    private static BufferedWriter writer;
    private static FileChannel channel;
    private static volatile SyncMode syncMode = SyncMode.ALWAYS;
    private static volatile long forceIntervalMillis = 1000;
    private static ScheduledExecutorService periodicSyncExecutor;
    private static StringBuilder pendingPeriodicEntries = new StringBuilder(PERIODIC_BUFFER_INITIAL_CAPACITY);
    private static boolean forcePending = false;

    private record ReplaySnapshot(List<String> lines, long legacyReplayReferenceTimeMillis) {
    }

    public static synchronized void configure(String syncModeValue, long intervalMillis) {
        syncMode = parseSyncMode(syncModeValue);
        forceIntervalMillis = Math.max(1, intervalMillis);
        restartPeriodicSyncTask();
    }

    private static synchronized void init() throws IOException {
        if (writer != null) {
            return;
        }

        fos = new FileOutputStream(WAL_PATH.toFile(), true);
        channel = fos.getChannel();
        writer = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));
        forcePending = false;
        restartPeriodicSyncTask();
    }

    public static void logSet(String key, String value, long expiryTime) {
        appendSet(encode(key), encode(value), expiryTime);
    }

    public static void logDelete(String key) {
        appendDelete(encode(key));
    }

    private static synchronized void appendSet(String encodedKey, String encodedValue, long expiryTime) {
        try {
            init();
            appendSetEntry(encodedKey, encodedValue, expiryTime);
        } catch (IOException e) {
            throw new RuntimeException("WAL write failed", e);
        }
    }

    private static synchronized void appendDelete(String encodedKey) {
        try {
            init();
            appendDeleteEntry(encodedKey);
        } catch (IOException e) {
            throw new RuntimeException("WAL write failed", e);
        }
    }

    public static void replay(InMemoryStore store) {
        ReplaySnapshot replaySnapshot = snapshotReplayLines();
        if (replaySnapshot.lines().isEmpty()) {
            return;
        }

        var writeLock = store.writeLock();
        writeLock.lock();
        try {
            for (String line : replaySnapshot.lines()) {
                try {
                    replayLine(line, store, replaySnapshot.legacyReplayReferenceTimeMillis());
                } catch (Exception e) {
                    log.warn("Skipping corrupted WAL entry: {}", line);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    public static synchronized void reset() {
        close();

        try {
            truncateWalFile();
        } catch (IOException e) {
            throw new RuntimeException("WAL truncate failed", e);
        }
    }

    public static synchronized void truncate() {
        reset();
    }

    public static synchronized void force() {
        try {
            forcePendingWrites();
        } catch (IOException e) {
            throw new RuntimeException("WAL force failed", e);
        }
    }

    public static synchronized void shutdown() {
        close();
    }

    @PreDestroy
    public void destroy() {
        shutdown();
    }

    public static synchronized void close() {
        stopPeriodicSyncTask();
        try {
            forcePendingWrites();
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
        pendingPeriodicEntries = new StringBuilder(PERIODIC_BUFFER_INITIAL_CAPACITY);
        forcePending = false;
    }

    private static void appendSetEntry(String encodedKey, String encodedValue, long expiryTime) throws IOException {
        if (syncMode == SyncMode.PERIODIC) {
            pendingPeriodicEntries.append("SET|")
                    .append(encodedKey)
                    .append('|')
                    .append(encodedValue)
                    .append('|')
                    .append(expiryTime)
                    .append('\n');
            forcePending = true;
            return;
        }

        writer.write("SET|");
        writer.write(encodedKey);
        writer.write('|');
        writer.write(encodedValue);
        writer.write('|');
        writer.write(Long.toString(expiryTime));
        writer.write('\n');
        forcePending = true;
        forcePendingWrites();
    }

    private static void appendDeleteEntry(String encodedKey) throws IOException {
        if (syncMode == SyncMode.PERIODIC) {
            pendingPeriodicEntries.append("DEL|")
                    .append(encodedKey)
                    .append('\n');
            forcePending = true;
            return;
        }

        writer.write("DEL|");
        writer.write(encodedKey);
        writer.write('\n');
        forcePending = true;
        forcePendingWrites();
    }

    private static void forcePendingWrites() throws IOException {
        if (!forcePending || writer == null || channel == null) {
            return;
        }

        flushPendingPeriodicEntries();
        writer.flush();
        channel.force(true);
        forcePending = false;
    }

    private static void flushPendingPeriodicEntries() throws IOException {
        if (pendingPeriodicEntries.length() == 0) {
            return;
        }

        writer.append(pendingPeriodicEntries);
        pendingPeriodicEntries.setLength(0);
    }

    private static void replayLine(String line, InMemoryStore store, long legacyReplayReferenceTimeMillis) {
        String[] parts = line.split("\\|", 4);
        if (parts.length == 0) {
            return;
        }

        switch (parts[0]) {
            case "SET" -> replaySet(parts, store);
            case "DEL" -> replayDelete(parts, store);
            default -> CommandExecutor.replay(line, store, legacyReplayReferenceTimeMillis);
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

    private static void restartPeriodicSyncTask() {
        stopPeriodicSyncTask();

        if (syncMode != SyncMode.PERIODIC) {
            return;
        }

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "zyra-wal-sync");
            thread.setDaemon(true);
            return thread;
        };

        periodicSyncExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);
        periodicSyncExecutor.scheduleAtFixedRate(
                WriteAheadLog::runPeriodicSync,
                forceIntervalMillis,
                forceIntervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    private static synchronized void runPeriodicSync() {
        if (syncMode != SyncMode.PERIODIC) {
            return;
        }

        try {
            forcePendingWrites();
        } catch (IOException e) {
            log.error("Periodic WAL sync failed", e);
        }
    }

    private static void stopPeriodicSyncTask() {
        ScheduledExecutorService executor = periodicSyncExecutor;
        periodicSyncExecutor = null;
        if (executor == null) {
            return;
        }

        // Do not interrupt an in-flight channel.force(): on Windows/Java this can
        // close the channel underneath later WAL calls and surface as
        // ClosedByInterruptException / Stream Closed in unrelated writers.
        executor.shutdown();
    }

    private static SyncMode parseSyncMode(String value) {
        if (value == null) {
            return SyncMode.ALWAYS;
        }

        try {
            return SyncMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown WAL sync mode '{}', falling back to ALWAYS", value);
            return SyncMode.ALWAYS;
        }
    }

    private static String encode(String value) {
        return BASE64_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(BASE64_DECODER.decode(value), StandardCharsets.UTF_8);
    }

    private static void truncateWalFile() throws IOException {
        try (FileChannel truncateChannel = FileChannel.open(
                WAL_PATH,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            truncateChannel.force(true);
        }
        bestEffortForceParentDirectory(WAL_PATH);
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

    private static ReplaySnapshot snapshotReplayLines() {
        synchronized (WriteAheadLog.class) {
            if (!Files.exists(WAL_PATH)) {
                return new ReplaySnapshot(List.of(), System.currentTimeMillis());
            }

            long legacyReplayReferenceTimeMillis = readLegacyReplayReferenceTimeMillis();

            try {
                forcePendingWrites();
            } catch (IOException e) {
                log.warn("WAL flush before replay failed: {}", e.getMessage());
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(WAL_PATH.toFile()), StandardCharsets.UTF_8))) {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
                return new ReplaySnapshot(lines, legacyReplayReferenceTimeMillis);
            } catch (IOException e) {
                log.warn("WAL replay failed, skipping recovery from WAL: {}", e.getMessage());
                return new ReplaySnapshot(List.of(), legacyReplayReferenceTimeMillis);
            }
        }
    }

    private static long readLegacyReplayReferenceTimeMillis() {
        try {
            long lastModifiedTimeMillis = Files.getLastModifiedTime(WAL_PATH).toMillis();
            return Math.min(System.currentTimeMillis(), lastModifiedTimeMillis);
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }
}
