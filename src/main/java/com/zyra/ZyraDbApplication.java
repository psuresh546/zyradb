package com.zyra;

import com.zyra.scheduler.ExpiryScheduler;
import com.zyra.store.InMemoryStore;
import com.zyra.store.SnapshotManager;
import com.zyra.store.WriteAheadLog;
import com.zyra.tcp.TCPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicBoolean;

@SpringBootApplication
public class ZyraDbApplication {

    private static final Logger log = LoggerFactory.getLogger(ZyraDbApplication.class);

    public static void main(String[] args) {
        var context = SpringApplication.run(ZyraDbApplication.class, args);
        InMemoryStore store = context.getBean(InMemoryStore.class);
        TCPServer tcpServer = context.getBean(TCPServer.class);
        AtomicBoolean shutdownExecuted = new AtomicBoolean(false);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!shutdownExecuted.compareAndSet(false, true)) {
                return;
            }

            log.info("ZyraDB shutting down safely...");

            ExpiryScheduler.shutdown();
            tcpServer.shutdown();

            boolean snapshotSaved = SnapshotManager.save(store);
            if (!snapshotSaved) {
                log.warn("Snapshot save failed. Preserving WAL for recovery.");
            }

            WriteAheadLog.shutdown();

            log.info("Shutdown complete.");
        }, "zyra-shutdown"));
    }

    @Bean
    public InMemoryStore inMemoryStore() {
        return InMemoryStore.getInstance();
    }

    @Bean
    public CommandLineRunner init(InMemoryStore store, TCPServer tcpServer) {
        return args -> {
            SnapshotManager.load(store);
            WriteAheadLog.replay(store);
            ExpiryScheduler.start(store);
            tcpServer.start();
        };
    }
}
