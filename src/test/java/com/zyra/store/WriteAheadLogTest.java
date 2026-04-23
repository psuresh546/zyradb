package com.zyra.store;

import com.zyra.parser.Command;
import com.zyra.service.KeyValueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteAheadLogTest {

    private static final Path WAL_PATH = Path.of("zyra.wal");

    private final InMemoryStore store = InMemoryStore.getInstance();
    private final KeyValueService service = new KeyValueService(store);

    @BeforeEach
    void setUp() {
        store.clear();
        WriteAheadLog.configure("always", 1000);
        WriteAheadLog.truncate();
    }

    @AfterEach
    void tearDown() throws Exception {
        store.clear();
        WriteAheadLog.configure("always", 1000);
        WriteAheadLog.truncate();
        Files.deleteIfExists(WAL_PATH);
    }

    @Test
    void replayDoesNotResurrectExpiredKeys() throws Exception {
        long expiredAt = System.currentTimeMillis() + 500;
        WriteAheadLog.logSet("shortlived", "x", expiredAt);

        Thread.sleep(700);

        store.clear();
        WriteAheadLog.replay(store);

        assertNull(store.get("shortlived"));
    }

    @Test
    void replayRestoresPersistentAndDeletedState() {
        WriteAheadLog.logSet("alive", "42", -1);
        WriteAheadLog.logSet("gone", "temp", -1);
        WriteAheadLog.logDelete("gone");

        store.clear();
        WriteAheadLog.replay(store);

        assertEquals("42", store.get("alive"));
        assertNull(store.get("gone"));
    }

    @Test
    void replaySkipsCorruptedLinesAndContinues() throws Exception {
        Files.writeString(WAL_PATH, "SET|%%%|%%%|oops\nSET|YWxpdmU=|NDI=|-1\n");

        store.clear();
        WriteAheadLog.replay(store);

        assertEquals("42", store.get("alive"));
    }

    @Test
    void legacyRawSetWithExpiredWalTimestampDoesNotGetFreshTtlOnReplay() throws Exception {
        Files.writeString(WAL_PATH, "SET legacy value EX 5\n");
        Files.setLastModifiedTime(WAL_PATH, FileTime.fromMillis(System.currentTimeMillis() - 10_000));

        store.clear();
        WriteAheadLog.replay(store);

        assertNull(store.get("legacy"));
    }

    @Test
    void legacyRawExpireWithExpiredWalTimestampDoesNotGetFreshTtlOnReplay() throws Exception {
        Files.writeString(WAL_PATH, "SET session active\nEXPIRE session 5\n");
        Files.setLastModifiedTime(WAL_PATH, FileTime.fromMillis(System.currentTimeMillis() - 10_000));

        store.clear();
        WriteAheadLog.replay(store);

        assertNull(store.get("session"));
    }

    @Test
    void periodicModeStillPersistsWritesOnShutdown() {
        WriteAheadLog.configure("periodic", 60_000);
        WriteAheadLog.logSet("alive", "42", -1);
        WriteAheadLog.shutdown();

        store.clear();
        WriteAheadLog.replay(store);

        assertEquals("42", store.get("alive"));
    }

    @Test
    void periodicModeReplayFlushesBufferedWritesBeforeIntervalElapses() {
        WriteAheadLog.configure("periodic", 60_000);
        WriteAheadLog.logSet("buffered", "42", -1);

        store.clear();
        WriteAheadLog.replay(store);

        assertEquals("42", store.get("buffered"));
    }

    @Test
    void periodicModeFlushesBufferedWritesWithoutShutdown() throws Exception {
        WriteAheadLog.configure("periodic", 10);
        WriteAheadLog.logSet("async", "99", -1);

        Thread.sleep(80);
        WriteAheadLog.close();

        store.clear();
        WriteAheadLog.replay(store);

        assertEquals("99", store.get("async"));
    }

    @Test
    void concurrentPeriodicWritesRemainReplayable() throws Exception {
        WriteAheadLog.configure("periodic", 25);

        int writers = 8;
        int writesPerWriter = 100;
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int writerIndex = 0; writerIndex < writers; writerIndex++) {
            final int workerId = writerIndex;
            futures.add(executor.submit(() -> {
                start.await();
                for (int writeIndex = 0; writeIndex < writesPerWriter; writeIndex++) {
                    WriteAheadLog.logSet(
                            "k-" + workerId + "-" + writeIndex,
                            "v-" + workerId + "-" + writeIndex,
                            -1
                    );
                }
                return null;
            }));
        }

        start.countDown();

        for (Future<?> future : futures) {
            future.get();
        }

        executor.shutdownNow();
        WriteAheadLog.shutdown();

        store.clear();
        WriteAheadLog.replay(store);

        for (int writerIndex = 0; writerIndex < writers; writerIndex++) {
            for (int writeIndex = 0; writeIndex < writesPerWriter; writeIndex++) {
                assertEquals(
                        "v-" + writerIndex + "-" + writeIndex,
                        store.get("k-" + writerIndex + "-" + writeIndex)
                );
            }
        }
    }

    @Test
    void replayDoesNotDeadlockAgainstMutationHoldingKeyLock() throws Exception {
        for (int i = 0; i < 2_000; i++) {
            WriteAheadLog.logSet("seed-" + i, "value-" + i, -1);
        }

        ReentrantLock keyLock = (ReentrantLock) store.keyLock("hot");
        CountDownLatch keyHeld = new CountDownLatch(1);
        CountDownLatch replayStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> mutation = executor.submit(() -> {
                keyLock.lock();
                try {
                    keyHeld.countDown();
                    assertTrue(replayStarted.await(1, TimeUnit.SECONDS));
                    assertEquals("OK", service.execute(new Command("SET", List.of("hot", "value"))));
                } finally {
                    keyLock.unlock();
                }
                return null;
            });

            assertTrue(keyHeld.await(1, TimeUnit.SECONDS));

            Future<?> replay = executor.submit(() -> {
                replayStarted.countDown();
                WriteAheadLog.replay(store);
                return null;
            });

            Thread.sleep(100);

            mutation.get(2, TimeUnit.SECONDS);
            replay.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals("value", store.get("hot"));
    }
}
