package com.zyra.store;

import com.zyra.parser.Command;
import com.zyra.service.KeyValueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreConcurrencyTest {

    private static final Path SNAPSHOT_PATH = Path.of("zyra.snapshot");

    private final InMemoryStore store = InMemoryStore.getInstance();
    private final KeyValueService service = new KeyValueService(store);

    @BeforeEach
    void setUp() throws Exception {
        store.clear();
        WriteAheadLog.truncate();
        Files.deleteIfExists(SNAPSHOT_PATH);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.clear();
        WriteAheadLog.truncate();
        Files.deleteIfExists(SNAPSHOT_PATH);
    }

    @Test
    void concurrentSetCommandsOnSameKeyLeaveReadableFinalState() throws Exception {
        int writers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        List<String> expectedValues = new ArrayList<>();

        for (int i = 0; i < writers; i++) {
            String value = "value-" + i;
            expectedValues.add(value);
            futures.add(executor.submit(() -> {
                start.await();
                return service.execute(new Command("SET", List.of("shared", value)));
            }));
        }

        start.countDown();

        for (Future<String> future : futures) {
            assertEquals("OK", future.get());
        }

        executor.shutdownNow();

        String finalValue = store.get("shared");
        assertNotNull(finalValue);
        assertTrue(expectedValues.contains(finalValue));
        assertEquals(1, store.size());
        assertEquals(-1, store.ttl("shared"));
    }

    @Test
    void snapshotSaveRemainsLoadableWhileWritesHappenConcurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successfulSaves = new AtomicInteger();

        Future<?> writer = executor.submit(() -> {
            start.await();
            for (int i = 0; i < 200; i++) {
                service.execute(new Command("SET", List.of("k-" + i, "v-" + i)));
            }
            return null;
        });

        Future<?> snapshotter = executor.submit(() -> {
            start.await();
            for (int i = 0; i < 20; i++) {
                if (SnapshotManager.save(store)) {
                    successfulSaves.incrementAndGet();
                }
            }
            return null;
        });

        start.countDown();
        writer.get();
        snapshotter.get();

        executor.shutdownNow();

        assertEquals(20, successfulSaves.get());
        assertTrue(SnapshotManager.save(store));
        assertTrue(Files.exists(SNAPSHOT_PATH));

        store.clear();
        SnapshotManager.load(store);

        for (int i = 0; i < 200; i++) {
            String key = "k-" + i;
            String value = store.get(key);
            assertEquals("v-" + i, value);
        }
    }

    @Test
    void cleanupCanRunAlongsideReadsAndWritesWithoutLeavingExpiredKeysVisible() throws Exception {
        for (int i = 0; i < 50; i++) {
            store.set("exp-" + i, "old-" + i, 1);
            store.set("live-" + i, "live-" + i, -1);
        }

        Thread.sleep(1100);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);

        Future<?> cleaner = executor.submit(() -> {
            start.await();
            for (int i = 0; i < 10; i++) {
                store.cleanupExpiredKeys();
            }
            return null;
        });

        Future<?> reader = executor.submit(() -> {
            start.await();
            for (int i = 0; i < 50; i++) {
                assertEquals("live-" + i, store.get("live-" + i));
                assertEquals(-2, store.ttl("exp-" + i));
            }
            return null;
        });

        Future<?> writer = executor.submit(() -> {
            start.await();
            for (int i = 0; i < 50; i++) {
                store.set("fresh-" + i, "fresh-" + i, -1);
            }
            return null;
        });

        start.countDown();
        cleaner.get();
        reader.get();
        writer.get();

        executor.shutdownNow();

        for (int i = 0; i < 50; i++) {
            assertEquals(null, store.get("exp-" + i));
            assertEquals("live-" + i, store.get("live-" + i));
            assertEquals("fresh-" + i, store.get("fresh-" + i));
        }
    }
}
