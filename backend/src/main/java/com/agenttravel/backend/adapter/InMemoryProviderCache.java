package com.agenttravel.backend.adapter;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public final class InMemoryProviderCache<T> {

    private final ConcurrentMap<String, CacheEntry<T>> entries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<T>> inFlight = new ConcurrentHashMap<>();

    public CacheLookup<T> get(String key, Instant now) {
        CacheEntry<T> entry = entries.get(key);
        if (entry == null) {
            return CacheLookup.miss();
        }
        return new CacheLookup<>(entry, entry.expiredAt(now) ? CacheState.STALE : CacheState.FRESH);
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

    public void restore(String key, CacheEntry<T> entry) {
        entries.put(key, entry);
    }

    public void invalidate(String key) {
        entries.remove(key);
    }

    public void invalidateNamespace(String namespacePrefix) {
        entries.keySet().removeIf(key -> key.startsWith(namespacePrefix));
    }

    public void invalidateVersion(String version) {
        AdapterCacheKeys.versionNamespaces(version).forEach(this::invalidateNamespace);
    }

    public T singleFlight(String key, Supplier<T> loader) {
        CompletableFuture<T> created = new CompletableFuture<>();
        CompletableFuture<T> current = inFlight.putIfAbsent(key, created);
        if (current != null) {
            return join(current);
        }
        try {
            T value = loader.get();
            created.complete(value);
            return value;
        } catch (RuntimeException | Error throwable) {
            created.completeExceptionally(throwable);
            throw throwable;
        } finally {
            inFlight.remove(key, created);
        }
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

    private T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }
}
