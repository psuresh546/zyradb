package com.zyra.service;

import com.zyra.parser.Command;
import com.zyra.store.InMemoryStore;
import com.zyra.store.WriteAheadLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyValueServiceTest {

    private static final Path WAL_PATH = Path.of("zyra.wal");

    private final InMemoryStore store = InMemoryStore.getInstance();
    private final KeyValueService service = new KeyValueService(store);

    @BeforeEach
    void setUp() throws Exception {
        store.clear();
        WriteAheadLog.shutdown();
        deleteWalArtifact();
        WriteAheadLog.configure("always", 1000);
        WriteAheadLog.truncate();
    }

    @AfterEach
    void tearDown() throws Exception {
        store.clear();
        WriteAheadLog.shutdown();
        deleteWalArtifact();
    }

    @Test
    void setAndGetValue() {
        assertEquals("OK", service.execute(new Command("SET", List.of("name", "zyra"))));
        assertEquals("VAL zyra", service.execute(new Command("GET", List.of("name"))));
    }

    @Test
    void ttlReflectsExpiryAndExpiredKeysDisappear() throws InterruptedException {
        assertEquals("OK", service.execute(new Command("SET", List.of("temp", "42", "EX", "1"))));
        assertEquals("INT 1", service.execute(new Command("TTL", List.of("temp"))));

        Thread.sleep(1100);

        assertEquals("NIL", service.execute(new Command("GET", List.of("temp"))));
        assertEquals("INT -2", service.execute(new Command("TTL", List.of("temp"))));
    }

    @Test
    void deleteTreatsExpiredKeyAsMissing() throws InterruptedException {
        assertEquals("OK", service.execute(new Command("SET", List.of("temp", "42", "EX", "1"))));

        Thread.sleep(1100);

        assertEquals("INT 0", service.execute(new Command("DEL", List.of("temp"))));
        assertEquals("INT -2", service.execute(new Command("TTL", List.of("temp"))));
    }

    @Test
    void expireUpdatesExistingKey() {
        service.execute(new Command("SET", List.of("session", "active")));

        assertEquals("INT 1", service.execute(new Command("EXPIRE", List.of("session", "5"))));
    }

    @Test
    void expireRemainsDurableAcrossWalReplay() {
        service.execute(new Command("SET", List.of("session", "active")));
        assertEquals("INT 1", service.execute(new Command("EXPIRE", List.of("session", "5"))));

        store.clear();
        WriteAheadLog.shutdown();
        WriteAheadLog.replay(store);

        assertEquals("active", store.get("session"));
        long ttl = store.ttl("session");
        assertTrue(ttl >= 1 && ttl <= 5, "TTL out of range after WAL replay: " + ttl);
    }

    @Test
    void expireDoesNotChangeStateWhenWalWriteFails() throws Exception {
        assertEquals("OK", service.execute(new Command("SET", List.of("session", "active"))));

        WriteAheadLog.shutdown();
        deleteWalArtifact();
        Files.createDirectory(WAL_PATH);

        try {
            assertThrows(RuntimeException.class,
                    () -> service.execute(new Command("EXPIRE", List.of("session", "5"))));

            assertEquals("VAL active", service.execute(new Command("GET", List.of("session"))));
            assertEquals("INT -1", service.execute(new Command("TTL", List.of("session"))));
        } finally {
            deleteWalArtifact();
            WriteAheadLog.configure("always", 1000);
        }
    }

    @Test
    void invalidSetSyntaxReturnsHelpfulError() {
        assertEquals(
                "ERR invalid SET syntax. Use: SET key value [EX seconds]",
                service.execute(new Command("SET", List.of("a", "b", "EX")))
        );
    }

    @Test
    void infoReportsKeyCountAndUptime() {
        service.execute(new Command("SET", List.of("name", "zyra")));

        String info = service.execute(new Command("INFO", List.of()));

        assertTrue(info.startsWith("INFO keys=1 uptime="));
    }

    private void deleteWalArtifact() throws Exception {
        if (Files.isDirectory(WAL_PATH)) {
            Files.deleteIfExists(WAL_PATH);
            return;
        }

        Files.deleteIfExists(WAL_PATH);
    }
}
