package com.zyra.service;

import com.zyra.parser.Command;
import com.zyra.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyValueServiceTest {

    private final KeyValueService service = new KeyValueService();
    private final InMemoryStore store = InMemoryStore.getInstance();

    @BeforeEach
    void setUp() {
        store.clear();
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
    void expireUpdatesExistingKey() {
        service.execute(new Command("SET", List.of("session", "active")));

        assertEquals("INT 1", service.execute(new Command("EXPIRE", List.of("session", "5"))));
    }

    @Test
    void invalidSetSyntaxReturnsHelpfulError() {
        assertEquals(
                "ERR invalid SET syntax. Use: SET key value EX/EXP/EXPIRE seconds",
                service.execute(new Command("SET", List.of("a", "b", "EX")))
        );
    }
}
