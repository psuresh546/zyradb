package com.zyra.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryStoreTest {

    private final InMemoryStore store = InMemoryStore.getInstance();

    @BeforeEach
    void setUp() {
        store.clear();
    }

    @Test
    void restoreIgnoresExpiredEntries() {
        store.restore("old", "value", System.currentTimeMillis() - 1000);

        assertNull(store.get("old"));
    }

    @Test
    void expireMarksExistingKeyAndTtlReflectsRemainingTime() {
        store.set("session", "active", -1);

        assertTrue(store.expire("session", 5));
        assertTrue(store.ttl("session") <= 5);
        assertTrue(store.ttl("session") >= 1);
    }

    @Test
    void expireFailsForMissingKey() {
        assertFalse(store.expire("missing", 5));
        assertEquals(-2, store.ttl("missing"));
    }

    @Test
    void concurrentReadsAndWritesDoNotCorruptStore() throws Exception {
        int threads = 8;
        int keysPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int thread = 0; thread < threads; thread++) {
            final int workerId = thread;
            futures.add(executor.submit(() -> {
                start.await();

                for (int key = 0; key < keysPerThread; key++) {
                    String storeKey = "k-" + workerId + "-" + key;
                    String storeValue = "v-" + workerId + "-" + key;
                    store.set(storeKey, storeValue, -1);
                    assertEquals(storeValue, store.get(storeKey));
                    assertEquals(-1, store.ttl(storeKey));
                }

                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }

        executor.shutdownNow();

        assertEquals(threads * keysPerThread, store.size());
    }
}
