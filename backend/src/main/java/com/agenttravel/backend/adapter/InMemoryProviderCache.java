package com.agenttravel.backend.adapter;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryProviderCache<T> {

    private final ConcurrentMap<String, CacheEntry<T>> entries = new ConcurrentHashMap<>();

    public Optional<CacheEntry<T>> get(String key, Instant now) {
        CacheEntry<T> entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiredAt(now)) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    public void put(String key, T value, ProviderSource source, Instant now, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        entries.put(key, CacheEntry.value(value, source, now.plus(ttl)));
    }

    public void putNegative(String key, ProviderErrorCode code, Instant now, Duration ttl) {
        if (!isNegativeCacheable(code) || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        entries.put(key, CacheEntry.negative(code, now.plus(ttl)));
    }

    public void invalidate(String key) {
        entries.remove(key);
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    private boolean isNegativeCacheable(ProviderErrorCode code) {
        return code == ProviderErrorCode.EMPTY_RESULT || code == ProviderErrorCode.INVALID_RESPONSE
                || code == ProviderErrorCode.DIRTY_DATA;
    }
}
