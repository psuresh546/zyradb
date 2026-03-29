package com.zyra;

import com.zyra.scheduler.ExpiryScheduler;
import com.zyra.store.InMemoryStore;
import com.zyra.store.SnapshotManager;
import com.zyra.store.WriteAheadLog;
import com.zyra.tcp.TCPServer;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ZyraDbApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZyraDbApplication.class, args);
    }

    @Bean
    public CommandLineRunner init() {
        return args -> {
            InMemoryStore store = InMemoryStore.getInstance();

            // 1. Restore data before accepting traffic
            SnapshotManager.load(store);
            WriteAheadLog.replay(store);

            // 2. Start expiry scheduler
            ExpiryScheduler.start(store);

            // 3. Start TCP server
            new Thread(() -> new TCPServer(6379).start()).start();
        };
    }
}