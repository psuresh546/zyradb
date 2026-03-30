package com.zyra.store;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class InMemoryStore {

    private static final InMemoryStore INSTANCE = new InMemoryStore();

    private final Map<String, ValueWrapper> store = new HashMap<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

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

    public ReentrantReadWriteLock.ReadLock readLock() {
        return rwLock.readLock();
    }

    public ReentrantReadWriteLock.WriteLock writeLock() {
        return rwLock.writeLock();
    }

    public void set(String key, String value, long ttlSeconds) {
        writeLock().lock();
        try {
            long expiryTime = ttlSeconds > 0
                    ? System.currentTimeMillis() + (ttlSeconds * 1000)
                    : -1;

            store.put(key, new ValueWrapper(value, expiryTime));
        } finally {
            writeLock().unlock();
        }
    }

    public String get(String key) {
        readLock().lock();
        try {
            ValueWrapper wrapper = store.get(key);
            if (wrapper == null || wrapper.isExpired()) {
                return null;
            }

            return wrapper.getValue();
        } finally {
            readLock().unlock();
        }
    }

    public boolean delete(String key) {
        writeLock().lock();
        try {
            return store.remove(key) != null;
        } finally {
            writeLock().unlock();
        }
    }

    public boolean expire(String key, long ttlSeconds) {
        writeLock().lock();
        try {
            ValueWrapper existing = store.get(key);
            if (existing == null || existing.isExpired()) {
                return false;
            }

            long expiryTime = System.currentTimeMillis() + (ttlSeconds * 1000);
            store.put(key, new ValueWrapper(existing.getValue(), expiryTime));
            return true;
        } finally {
            writeLock().unlock();
        }
    }

    public long ttl(String key) {
        readLock().lock();
        try {
            ValueWrapper wrapper = store.get(key);
            if (wrapper == null || wrapper.isExpired()) {
                return -2;
            }

            if (wrapper.getExpiryTime() == -1) {
                return -1;
            }

            long remainingMillis = wrapper.getExpiryTime() - System.currentTimeMillis();
            return remainingMillis <= 0 ? -2 : (remainingMillis + 999) / 1000;
        } finally {
            readLock().unlock();
        }
    }

    public int cleanupExpiredKeys() {
        writeLock().lock();
        try {
            int removed = 0;

            Iterator<Map.Entry<String, ValueWrapper>> iterator = store.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, ValueWrapper> entry = iterator.next();
                if (entry.getValue().isExpired()) {
                    iterator.remove();
                    removed++;
                }
            }

            return removed;
        } finally {
            writeLock().unlock();
        }
    }

    public Map<String, ValueWrapper> snapshot() {
        readLock().lock();
        try {
            Map<String, ValueWrapper> snapshot = new HashMap<>();
            for (Map.Entry<String, ValueWrapper> entry : store.entrySet()) {
                if (!entry.getValue().isExpired()) {
                    snapshot.put(entry.getKey(), entry.getValue());
                }
            }
            return snapshot;
        } finally {
            readLock().unlock();
        }
    }

    public int size() {
        readLock().lock();
        try {
            int size = 0;
            for (ValueWrapper wrapper : store.values()) {
                if (!wrapper.isExpired()) {
                    size++;
                }
            }
            return size;
        } finally {
            readLock().unlock();
        }
    }

    public void clear() {
        writeLock().lock();
        try {
            store.clear();
        } finally {
            writeLock().unlock();
        }
    }

    public void restore(String key, String value, long expiryTime) {
        writeLock().lock();
        try {
            if (expiryTime != -1 && expiryTime <= System.currentTimeMillis()) {
                store.remove(key);
                return;
            }

            store.put(key, new ValueWrapper(value, expiryTime));
        } finally {
            writeLock().unlock();
        }
    }
}
