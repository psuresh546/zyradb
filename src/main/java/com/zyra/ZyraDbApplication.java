package com.zyra;

import com.zyra.scheduler.ExpiryScheduler;
import com.zyra.store.InMemoryStore;
import com.zyra.store.SnapshotManager;
import com.zyra.store.WriteAheadLog;
import com.zyra.tcp.TCPServer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class ZyraDbApplication {

    private static final Logger log = LoggerFactory.getLogger(ZyraDbApplication.class);

    private final InMemoryStore store;
    private final TCPServer tcpServer;

    @Value("${zyra.snapshot.interval-seconds:300}")
    private long snapshotIntervalSeconds;

    private ScheduledExecutorService snapshotScheduler;

    public ZyraDbApplication(InMemoryStore store, TCPServer tcpServer) {
        this.store = store;
        this.tcpServer = tcpServer;
    }

    public static void main(String[] args) {
        SpringApplication.run(ZyraDbApplication.class, args);
    }

    @Bean
    public static InMemoryStore inMemoryStore() {
        return InMemoryStore.getInstance();
    }

    @Bean
    public CommandLineRunner init(
            @Value("${zyra.wal.sync-mode:always}") String walSyncMode,
            @Value("${zyra.wal.force-interval-ms:1000}") long walForceIntervalMs) {

        return args -> {
            WriteAheadLog.configure(walSyncMode, walForceIntervalMs);

            log.info("Loading snapshot...");
            SnapshotManager.load(store);

            log.info("Replaying WAL...");
            WriteAheadLog.replay(store);

            ExpiryScheduler.start(store);
            tcpServer.start();

            startPeriodicSnapshotScheduler();

            log.info("ZyraDB started");
        };
    }

    private void startPeriodicSnapshotScheduler() {
        snapshotScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zyra-snapshot-scheduler");
            t.setDaemon(true);
            return t;
        });
        snapshotScheduler.scheduleAtFixedRate(
                this::runPeriodicSnapshot,
                snapshotIntervalSeconds,
                snapshotIntervalSeconds,
                TimeUnit.SECONDS);
        log.info("Periodic snapshot scheduler started (interval: {}s)", snapshotIntervalSeconds);
    }

    private void runPeriodicSnapshot() {
        log.info("Running periodic snapshot...");
        boolean saved = SnapshotManager.save(store);
        if (saved) {
            log.info("Periodic snapshot saved successfully");
        } else {
            log.warn("Periodic snapshot failed - WAL preserved for recovery");
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("ZyraDB shutting down...");

        if (snapshotScheduler != null) {
            snapshotScheduler.shutdown();
            try {
                snapshotScheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        ExpiryScheduler.shutdown();
        tcpServer.shutdown();

        boolean snapshotSaved = SnapshotManager.save(store);
        if (!snapshotSaved) {
            log.warn("Final snapshot save failed - WAL preserved for recovery on next start");
        }

        WriteAheadLog.shutdown();

        log.info("ZyraDB shutdown complete");
    }
}
