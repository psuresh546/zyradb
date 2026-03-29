package com.zyra.scheduler;

import com.zyra.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public class ExpiryScheduler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ExpiryScheduler.class);
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile long startedAtMillis;
    private final InMemoryStore store;

    public ExpiryScheduler(InMemoryStore store) {
        this.store = store;
    }

    public static void start(InMemoryStore store) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        startedAtMillis = System.currentTimeMillis();
        Thread schedulerThread = new Thread(new ExpiryScheduler(store), "zyra-expiry-scheduler");
        schedulerThread.setDaemon(true);
        schedulerThread.start();
    }

    public static long uptimeSeconds() {
        if (!STARTED.get() || startedAtMillis == 0L) {
            return 0L;
        }

        return Math.max(0L, (System.currentTimeMillis() - startedAtMillis) / 1000);
    }

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(5000); // run every 5 seconds
                int cleaned = store.cleanupExpiredKeys();
                if (cleaned > 0) {
                    log.info("[SCHEDULER] Cleaned {} expired keys", cleaned);
                }
            } catch (InterruptedException e) {
                log.error("ExpiryScheduler interrupted", e);
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
