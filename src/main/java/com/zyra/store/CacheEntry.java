package com.zyra.store;

/*
    Intuition:
    ----------
    Wraps value for future extensibility.

    Later we will add:
        - expiry time
        - metadata
*/

public class CacheEntry {

    private final String value;

    public CacheEntry(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}