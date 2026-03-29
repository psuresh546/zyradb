package com.zyra.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
