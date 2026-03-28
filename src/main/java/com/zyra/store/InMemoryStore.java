package com.zyra.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryStore {

    private static final InMemoryStore INSTANCE = new InMemoryStore();

    private final Map<String, String> data = new ConcurrentHashMap<>();
    private final Map<String, Long> expiry = new ConcurrentHashMap<>();

    private InMemoryStore() {}

    public static InMemoryStore getInstance() {
        return INSTANCE;
    }

    // ----------------------------------------------------
    // SET key value ttlSeconds (-1 means no expiry)
    // ----------------------------------------------------
    public void set(String key, String value, long ttlSeconds) {
        data.put(key, value);

        if (ttlSeconds > 0) {
            long expireAt = System.currentTimeMillis() + (ttlSeconds * 1000);
            expiry.put(key, expireAt);
        } else {
            expiry.remove(key);
        }
    }

    // ----------------------------------------------------
    // GET key (auto-remove if expired)
    // ----------------------------------------------------
    public String get(String key) {
        if (isExpired(key)) {
            delete(key);
            return null;
        }
        return data.get(key);
    }

    // ----------------------------------------------------
    // DELETE key
    // ----------------------------------------------------
    public boolean delete(String key) {
        expiry.remove(key);
        return data.remove(key) != null;
    }

    // ----------------------------------------------------
    // EXPIRE key seconds
    // ----------------------------------------------------
    public boolean expire(String key, long seconds) {
        if (!data.containsKey(key)) {
            return false;
        }

        long expireAt = System.currentTimeMillis() + (seconds * 1000);
        expiry.put(key, expireAt);
        return true;
    }

    // ----------------------------------------------------
    // TTL key  (Redis semantics)
    // ----------------------------------------------------
    public long ttl(String key) {

        if (!data.containsKey(key)) {
            return -2; // key not exist
        }

        if (!expiry.containsKey(key)) {
            return -1; // no expiry
        }

        long remaining = (expiry.get(key) - System.currentTimeMillis()) / 1000;

        if (remaining <= 0) {
            delete(key);
            return -2;
        }

        return remaining;
    }

    // ----------------------------------------------------
    private boolean isExpired(String key) {
        if (!expiry.containsKey(key)) return false;
        return System.currentTimeMillis() > expiry.get(key);
    }

    // ----------------------------------------------------
// ACTIVE CLEANUP (used by ExpiryScheduler)
// ----------------------------------------------------
    public int cleanupExpiredKeys() {
        int cleaned = 0;

        long now = System.currentTimeMillis();

        for (String key : expiry.keySet()) {
            Long expireAt = expiry.get(key);

            if (expireAt != null && now > expireAt) {
                data.remove(key);
                expiry.remove(key);
                cleaned++;
            }
        }

        return cleaned;
    }
}