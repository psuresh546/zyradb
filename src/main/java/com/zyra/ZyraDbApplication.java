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
