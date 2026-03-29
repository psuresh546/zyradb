package com.zyra.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SnapshotManagerTest {

    private static final Path SNAPSHOT_PATH = Path.of("zyra.snapshot");

    private final InMemoryStore store = InMemoryStore.getInstance();

    @BeforeEach
    void setUp() throws Exception {
        store.clear();
        Files.deleteIfExists(SNAPSHOT_PATH);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.clear();
        Files.deleteIfExists(SNAPSHOT_PATH);
    }

    @Test
    void snapshotRoundTripPreservesSpecialCharacters() {
        store.set("key|1", "value|with|pipes", -1);

        SnapshotManager.save(store.snapshot());
        store.clear();
        SnapshotManager.load(store);

        assertEquals("value|with|pipes", store.get("key|1"));
    }

    @Test
    void expiredSnapshotEntriesAreIgnoredOnLoad() {
        long expiredAt = System.currentTimeMillis() - 1_000;
        store.restore("old", "value", expiredAt);

        SnapshotManager.save(store.snapshot());
        store.clear();
        SnapshotManager.load(store);

        assertNull(store.get("old"));
    }
}
