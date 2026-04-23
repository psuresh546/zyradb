package com.zyra.store;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class InMemoryStore {

    private static final InMemoryStore INSTANCE = new InMemoryStore();
    private static final int KEY_LOCK_STRIPES = 64;

    private final ConcurrentMap<String, ValueWrapper> store = new ConcurrentHashMap<>();
    private final ReentrantLock[] keyLocks = new ReentrantLock[KEY_LOCK_STRIPES];
    private final ReentrantLock storeWriteMutex = new ReentrantLock();
    private final Lock storeWriteLock = new StoreWriteLock();

    private InMemoryStore() {
        for (int i = 0; i < KEY_LOCK_STRIPES; i++) {
            keyLocks[i] = new ReentrantLock();
        }
    }

    public static InMemoryStore getInstance() {
        return INSTANCE;
    }

    public static final class ValueWrapper {
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
            return isExpiredAt(System.currentTimeMillis());
        }

        public boolean isExpiredAt(long currentTimeMillis) {
            return expiryTime != -1 && currentTimeMillis >= expiryTime;
        }
    }

    public Lock writeLock() {
        return storeWriteLock;
    }

    public Lock keyLock(String key) {
        int stripeIndex = Math.floorMod(key.hashCode(), KEY_LOCK_STRIPES);
        return keyLocks[stripeIndex];
    }

    public void set(String key, String value, long ttlSeconds) {
        if (ttlSeconds < 0 && ttlSeconds != -1) {
            throw new IllegalArgumentException(
                    "ttlSeconds must be >= 0 or -1 for no expiry. Got: " + ttlSeconds);
        }

        long expiryTime = ttlSeconds > 0
                ? System.currentTimeMillis() + (ttlSeconds * 1000L)
                : -1;
        restore(key, value, expiryTime);
    }

    public String get(String key) {
        Lock lock = keyLock(key);
        lock.lock();
        try {
            return getWhileHoldingKeyLock(key);
        } finally {
            lock.unlock();
        }
    }

    public boolean delete(String key) {
        Lock lock = keyLock(key);
        lock.lock();
        try {
            return deleteWhileHoldingKeyLock(key);
        } finally {
            lock.unlock();
        }
    }

    public boolean expire(String key, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be > 0");
        }

        Lock lock = keyLock(key);
        lock.lock();
        try {
            ValueWrapper existing = store.get(key);
            long now = System.currentTimeMillis();
            if (existing == null || existing.isExpiredAt(now)) {
                return false;
            }

            long expiryTime = now + (ttlSeconds * 1000L);
            store.put(key, new ValueWrapper(existing.getValue(), expiryTime));
            return true;
        } finally {
            lock.unlock();
        }
    }

    public long ttl(String key) {
        Lock lock = keyLock(key);
        lock.lock();
        try {
            ValueWrapper wrapper = store.get(key);
            if (wrapper == null) {
                return -2;
            }

            long now = System.currentTimeMillis();
            if (wrapper.isExpiredAt(now)) {
                store.remove(key, wrapper);
                return -2;
            }

            if (wrapper.getExpiryTime() == -1) {
                return -1;
            }

            long remainingMillis = wrapper.getExpiryTime() - now;
            return remainingMillis <= 0 ? -2 : (remainingMillis + 999) / 1000;
        } finally {
            lock.unlock();
        }
    }

    public int cleanupExpiredKeys() {
        int removed = 0;
        long now = System.currentTimeMillis();

        for (Map.Entry<String, ValueWrapper> entry : store.entrySet()) {
            ValueWrapper wrapper = entry.getValue();
            if (!wrapper.isExpiredAt(now)) {
                continue;
            }

            Lock lock = keyLock(entry.getKey());
            lock.lock();
            try {
                ValueWrapper current = store.get(entry.getKey());
                if (current != null
                        && current.isExpiredAt(now)
                        && store.remove(entry.getKey(), current)) {
                    removed++;
                }
            } finally {
                lock.unlock();
            }
        }

        return removed;
    }

    public Map<String, ValueWrapper> snapshot() {
        long now = System.currentTimeMillis();
        Map<String, ValueWrapper> snapshot = new HashMap<>();
        for (Map.Entry<String, ValueWrapper> entry : store.entrySet()) {
            if (!entry.getValue().isExpiredAt(now)) {
                snapshot.put(entry.getKey(), entry.getValue());
            }
        }
        return snapshot;
    }

    public int size() {
        int size = 0;
        long now = System.currentTimeMillis();
        for (ValueWrapper wrapper : store.values()) {
            if (!wrapper.isExpiredAt(now)) {
                size++;
            }
        }
        return size;
    }

    public void clear() {
        Lock lock = writeLock();
        lock.lock();
        try {
            store.clear();
        } finally {
            lock.unlock();
        }
    }

    public void restore(String key, String value, long expiryTime) {
        Lock lock = keyLock(key);
        lock.lock();
        try {
            restoreWhileHoldingKeyLock(key, value, expiryTime);
        } finally {
            lock.unlock();
        }
    }

    public String getWhileHoldingKeyLock(String key) {
        ValueWrapper wrapper = store.get(key);
        if (wrapper == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        if (wrapper.isExpiredAt(now)) {
            store.remove(key, wrapper);
            return null;
        }

        return wrapper.getValue();
    }

    public boolean deleteWhileHoldingKeyLock(String key) {
        ValueWrapper wrapper = store.get(key);
        if (wrapper == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (wrapper.isExpiredAt(now)) {
            store.remove(key, wrapper);
            return false;
        }

        return store.remove(key, wrapper);
    }

    public void restoreWhileHoldingKeyLock(String key, String value, long expiryTime) {
        if (expiryTime != -1 && expiryTime <= System.currentTimeMillis()) {
            store.remove(key);
            return;
        }

        store.put(key, new ValueWrapper(value, expiryTime));
    }

    private void lockAllKeyStripes() {
        for (ReentrantLock keyLock : keyLocks) {
            keyLock.lock();
        }
    }

    private void lockAllKeyStripesInterruptibly() throws InterruptedException {
        int locked = 0;
        try {
            for (; locked < keyLocks.length; locked++) {
                keyLocks[locked].lockInterruptibly();
            }
        } catch (InterruptedException | RuntimeException e) {
            unlockKeyStripes(locked - 1);
            throw e;
        }
    }

    private boolean tryLockAllKeyStripes() {
        int locked = 0;
        try {
            for (; locked < keyLocks.length; locked++) {
                if (!keyLocks[locked].tryLock()) {
                    unlockKeyStripes(locked - 1);
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            unlockKeyStripes(locked - 1);
            throw e;
        }
    }

    private boolean tryLockAllKeyStripes(long deadlineNanos) throws InterruptedException {
        int locked = 0;
        try {
            for (; locked < keyLocks.length; locked++) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0
                        || !keyLocks[locked].tryLock(remainingNanos, TimeUnit.NANOSECONDS)) {
                    unlockKeyStripes(locked - 1);
                    return false;
                }
            }
            return true;
        } catch (InterruptedException | RuntimeException e) {
            unlockKeyStripes(locked - 1);
            throw e;
        }
    }

    private void unlockAllKeyStripes() {
        unlockKeyStripes(keyLocks.length - 1);
    }

    private void unlockKeyStripes(int lastLockedIndex) {
        for (int i = lastLockedIndex; i >= 0; i--) {
            keyLocks[i].unlock();
        }
    }

    private final class StoreWriteLock implements Lock {

        @Override
        public void lock() {
            storeWriteMutex.lock();
            if (storeWriteMutex.getHoldCount() == 1) {
                lockAllKeyStripes();
            }
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            storeWriteMutex.lockInterruptibly();
            if (storeWriteMutex.getHoldCount() > 1) {
                return;
            }

            try {
                lockAllKeyStripesInterruptibly();
            } catch (InterruptedException | RuntimeException e) {
                storeWriteMutex.unlock();
                throw e;
            }
        }

        @Override
        public boolean tryLock() {
            if (!storeWriteMutex.tryLock()) {
                return false;
            }
            if (storeWriteMutex.getHoldCount() > 1) {
                return true;
            }

            if (tryLockAllKeyStripes()) {
                return true;
            }

            storeWriteMutex.unlock();
            return false;
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + unit.toNanos(time);
            if (!storeWriteMutex.tryLock(time, unit)) {
                return false;
            }
            if (storeWriteMutex.getHoldCount() > 1) {
                return true;
            }

            try {
                if (tryLockAllKeyStripes(deadlineNanos)) {
                    return true;
                }
            } catch (InterruptedException | RuntimeException e) {
                storeWriteMutex.unlock();
                throw e;
            }

            storeWriteMutex.unlock();
            return false;
        }

        @Override
        public void unlock() {
            if (!storeWriteMutex.isHeldByCurrentThread()) {
                throw new IllegalMonitorStateException("Current thread does not hold store write lock");
            }

            if (storeWriteMutex.getHoldCount() == 1) {
                unlockAllKeyStripes();
            }
            storeWriteMutex.unlock();
        }

        @Override
        public Condition newCondition() {
            throw new UnsupportedOperationException("Conditions are not supported on the store write lock");
        }
    }
}
