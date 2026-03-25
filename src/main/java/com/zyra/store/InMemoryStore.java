package com.zyra.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/*
    Intuition:
    ----------
    Central storage for all key-value pairs.

    Now enhanced with:
        - Structured logging (SLF4J)
        - TTL lifecycle visibility
*/

public class InMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryStore.class);

    private final Map<String, CacheEntry> store = new HashMap<>();

    private static final InMemoryStore INSTANCE = new InMemoryStore();

    private InMemoryStore() {}

    public static InMemoryStore getInstance() {
        return INSTANCE;
    }

    public void set(String key, String value) {
        store.put(key, new CacheEntry(value));

        log.info("[STORE] SET key={} value={} ttl=none", key, value);
    }

    public void setWithExpiry(String key, String value, long seconds) {

        CacheEntry entry = new CacheEntry(value);

        long expiryTime = System.currentTimeMillis() + (seconds * 1000);
        entry.setExpiryTime(expiryTime);

        store.put(key, entry);

        log.info("[STORE] SET key={} value={} ttl={}s", key, value, seconds);
    }

    public String get(String key) {

        CacheEntry entry = store.get(key);

        if (entry == null) {
            log.info("[STORE] GET key={} → MISS", key);
            return null;
        }

        // Lazy expiration
        if (entry.isExpired()) {
            store.remove(key);
            log.info("[STORE] EXPIRED key={} (lazy, ttl reached)", key);
            return null;
        }

        log.info("[STORE] GET key={} → HIT", key);
        return entry.getValue();
    }

    public boolean delete(String key) {
        boolean removed = store.remove(key) != null;

        log.info("[STORE] DELETE key={} → {}", key, removed ? "SUCCESS" : "NOT_FOUND");

        return removed;
    }

    public boolean expire(String key, long seconds) {

        CacheEntry entry = store.get(key);

        if (entry == null) {
            log.info("[STORE] EXPIRE key={} → NOT_FOUND", key);
            return false;
        }

        long expiryTime = System.currentTimeMillis() + (seconds * 1000);
        entry.setExpiryTime(expiryTime);

        log.info("[STORE] EXPIRE key={} ttl={}s", key, seconds);

        return true;
    }
}