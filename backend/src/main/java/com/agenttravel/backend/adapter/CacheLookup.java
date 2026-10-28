package com.agenttravel.backend.adapter;

public record CacheLookup<T>(CacheEntry<T> entry, CacheState state) {

    public static <T> CacheLookup<T> miss() {
        return new CacheLookup<>(null, CacheState.MISS);
    }

    public boolean present() {
        return entry != null;
    }

    public boolean fresh() {
        return state == CacheState.FRESH;
    }

    public boolean stale() {
        return state == CacheState.STALE;
    }
}
