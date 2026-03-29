package com.zyra.store;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryStore {

    private static final InMemoryStore INSTANCE = new InMemoryStore();

    private final Map<String, ValueWrapper> store = new ConcurrentHashMap<>();

    private InMemoryStore() {
    }

    public static InMemoryStore getInstance() {
        return INSTANCE;
    }

    public static class ValueWrapper {
        private final String value;
        private final long expiryTime;

        public ValueWrapper(String value, long expiryTime) {
            this.value = value;
            this.expiryTime = expiryTime;
        }

        public String getValue() {
            return value;
        }

        public long getExpiryTime() {
            return expiryTime;
        }

        public boolean isExpired() {
            return expiryTime != -1 && System.currentTimeMillis() > expiryTime;
        }
    }

    public void set(String key, String value, long ttlSeconds) {
        long expiryTime = ttlSeconds > 0
                ? System.currentTimeMillis() + (ttlSeconds * 1000)
                : -1;

        store.put(key, new ValueWrapper(value, expiryTime));
    }

    public String get(String key) {
        ValueWrapper wrapper = store.get(key);
        if (wrapper == null) {
            return null;
        }

        if (wrapper.isExpired()) {
            store.remove(key, wrapper);
            return null;
        }

        return wrapper.getValue();
    }

    public boolean delete(String key) {
        return store.remove(key) != null;
    }

    public boolean expire(String key, long ttlSeconds) {
        return store.computeIfPresent(key, (ignored, existing) -> {
            if (existing.isExpired()) {
                return null;
            }

            long expiryTime = System.currentTimeMillis() + (ttlSeconds * 1000);
            return new ValueWrapper(existing.getValue(), expiryTime);
        }) != null;
    }

    public long ttl(String key) {
        ValueWrapper wrapper = store.get(key);
        if (wrapper == null) {
            return -2;
        }

        if (wrapper.isExpired()) {
            store.remove(key, wrapper);
            return -2;
        }

        if (wrapper.getExpiryTime() == -1) {
            return -1;
        }

        long remainingMillis = wrapper.getExpiryTime() - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            store.remove(key, wrapper);
            return -2;
        }

        return (remainingMillis + 999) / 1000;
    }

    public int cleanupExpiredKeys() {
        int removed = 0;

        for (Map.Entry<String, ValueWrapper> entry : store.entrySet()) {
            if (entry.getValue().isExpired() && store.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }

        return removed;
    }

    public Map<String, ValueWrapper> snapshot() {
        cleanupExpiredKeys();
        return new HashMap<>(store);
    }

    public int size() {
        cleanupExpiredKeys();
        return store.size();
    }

    public void clear() {
        store.clear();
    }

    public void restore(String key, String value, long expiryTime) {
        if (expiryTime != -1 && expiryTime <= System.currentTimeMillis()) {
            store.remove(key);
            return;
        }

        store.put(key, new ValueWrapper(value, expiryTime));
    }
}
