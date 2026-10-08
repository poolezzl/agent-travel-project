package com.agenttravel.backend.adapter;

import java.time.Instant;

public record CacheEntry<T>(
        T value,
        ProviderSource source,
        Instant expiresAt,
        boolean negative,
        ProviderErrorCode negativeCode) {

    public static <T> CacheEntry<T> value(T value, ProviderSource source, Instant expiresAt) {
        return new CacheEntry<>(value, source, expiresAt, false, null);
    }

    public static <T> CacheEntry<T> negative(ProviderErrorCode code, Instant expiresAt) {
        return new CacheEntry<>(null, null, expiresAt, true, code);
    }

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
