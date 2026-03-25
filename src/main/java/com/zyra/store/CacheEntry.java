package com.zyra.store;

/*
    Intuition:
    ----------
    Holds value + expiry metadata.

    expiryTime:
        - -1 → no expiration
        - otherwise → epoch time in ms
*/

public class CacheEntry {

    private final String value;
    private long expiryTime; // in milliseconds

    public CacheEntry(String value) {
        this.value = value;
        this.expiryTime = -1; // no expiry by default
    }

    public String getValue() {
        return value;
    }

    public long getExpiryTime() {
        return expiryTime;
    }

    public void setExpiryTime(long expiryTime) {
        this.expiryTime = expiryTime;
    }

    public boolean isExpired() {
        return expiryTime != -1 && System.currentTimeMillis() > expiryTime;
    }
}