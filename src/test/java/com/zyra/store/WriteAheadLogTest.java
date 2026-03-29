package com.zyra.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WriteAheadLogTest {

    private static final Path WAL_PATH = Path.of("zyra.wal");

    private final InMemoryStore store = InMemoryStore.getInstance();

    @BeforeEach
    void setUp() {
        store.clear();
        WriteAheadLog.truncate();
    }

    @AfterEach
    void tearDown() throws Exception {
        store.clear();
        WriteAheadLog.truncate();
        Files.deleteIfExists(WAL_PATH);
    }

    @Test
    void replayDoesNotResurrectExpiredKeys() throws Exception {
        long expiredAt = System.currentTimeMillis() + 500;
        WriteAheadLog.logSet("shortlived", "x", expiredAt);

        Thread.sleep(700);

        store.clear();
        WriteAheadLog.replay(store);

        assertNull(store.get("shortlived"));
    }

    @Test
    void replayRestoresPersistentAndDeletedState() {
        WriteAheadLog.logSet("alive", "42", -1);
        WriteAheadLog.logSet("gone", "temp", -1);
        WriteAheadLog.logDelete("gone");

        store.clear();
        WriteAheadLog.replay(store);

        assertEquals("42", store.get("alive"));
        assertNull(store.get("gone"));
    }

    @Test
    void replaySkipsCorruptedLinesAndContinues() throws Exception {
        Files.writeString(WAL_PATH, "SET|%%%|%%%|oops\nSET|YWxpdmU=|NDI=|-1\n");

        store.clear();
        WriteAheadLog.replay(store);

        assertEquals("42", store.get("alive"));
    }
}
