package com.zyra;

import com.zyra.scheduler.ExpiryScheduler;
import com.zyra.store.InMemoryStore;
import com.zyra.store.SnapshotManager;
import com.zyra.tcp.TCPServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ZyraDbApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZyraDbApplication.class, args);

        InMemoryStore store = InMemoryStore.getInstance();
        SnapshotManager.load(store);

        Thread schedulerThread = new Thread(new ExpiryScheduler(store));
        schedulerThread.setDaemon(true);
        schedulerThread.start();

        TCPServer server = new TCPServer(6379);
        server.start();
    }
}
