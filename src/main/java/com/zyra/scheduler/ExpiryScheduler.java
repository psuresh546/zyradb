package com.zyra.scheduler;

import com.zyra.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryScheduler.class);
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile ScheduledExecutorService scheduler;
    private static volatile long startedAtMillis;

    public static void start(InMemoryStore store) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        startedAtMillis = System.currentTimeMillis();
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "zyra-expiry-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(() -> runCleanup(store), 5, 5, TimeUnit.SECONDS);
    }

    public static long uptimeSeconds() {
        if (!STARTED.get() || startedAtMillis == 0L) {
            return 0L;
        }

        return Math.max(0L, (System.currentTimeMillis() - startedAtMillis) / 1000);
    }

    private static void runCleanup(InMemoryStore store) {
        try {
            int cleaned = store.cleanupExpiredKeys();
            if (cleaned > 0) {
                log.info("[SCHEDULER] Cleaned {} expired keys", cleaned);
            }
        } catch (RuntimeException e) {
            log.error("ExpiryScheduler cleanup failed", e);
        }
    }

    public static void stop() {
        shutdown();
    }

    public static void shutdown() {
        if (!STARTED.compareAndSet(true, false)) {
            return;
        }

        ScheduledExecutorService localScheduler = scheduler;
        scheduler = null;
        startedAtMillis = 0L;

        if (localScheduler != null) {
            localScheduler.shutdownNow();
        }
    }
}
