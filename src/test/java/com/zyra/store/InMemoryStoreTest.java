package com.zyra.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
    void valueWrapperTreatsExactExpiryInstantAsExpired() {
        InMemoryStore.ValueWrapper wrapper = new InMemoryStore.ValueWrapper("value", 1_000L);

        assertTrue(wrapper.isExpiredAt(1_000L));
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
    void expireDoesNotDeleteExpiredKeyAsSideEffect() throws InterruptedException {
        store.set("stale", "value", 1);
        Thread.sleep(1_100);

        assertFalse(store.expire("stale", 5));
        assertEquals(1, store.cleanupExpiredKeys());
    }

    @Test
    void setWaitsForExternallyHeldKeyLock() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        ReentrantLock keyLock = (ReentrantLock) store.keyLock("shared");

        keyLock.lock();
        try {
            Future<?> future = executor.submit(() -> {
                started.countDown();
                store.set("shared", "value", -1);
                return null;
            });

            assertTrue(started.await(1, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertFalse(future.isDone());

            keyLock.unlock();
            future.get(1, TimeUnit.SECONDS);
        } finally {
            if (keyLock.isHeldByCurrentThread()) {
                keyLock.unlock();
            }
            executor.shutdownNow();
        }

        assertEquals("value", store.get("shared"));
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
