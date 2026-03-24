package com.zyra.store;

/*
    Intuition:
    ----------
    Central storage for all key-value pairs.

    Shared across all clients.

    Current:
        - Basic SET/GET

    Future:
        - TTL
        - LRU
        - Thread safety
*/

import java.util.HashMap;
import java.util.Map;

public class InMemoryStore {

    private final Map<String, CacheEntry> store = new HashMap<>();

    // Singleton instance
    private static final InMemoryStore INSTANCE = new InMemoryStore();

    private InMemoryStore() {}

    public static InMemoryStore getInstance() {
        return INSTANCE;
    }

    public void set(String key, String value) {
        store.put(key, new CacheEntry(value));
    }

    public String get(String key) {
        CacheEntry entry = store.get(key);
        return entry != null ? entry.getValue() : null;
    }
}