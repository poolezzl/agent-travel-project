package com.agenttravel.backend.adapter;

import java.util.Objects;

public record ProviderResponse<T>(T value, ProviderSource source, boolean cacheHit, boolean degraded,
        ProviderErrorCode degradationCode) {

    public ProviderResponse(T value, ProviderSource source, boolean cacheHit) {
        this(value, source, cacheHit, false, null);
    }

    public ProviderResponse {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }

    public ProviderResponse<T> fromCache() {
        return new ProviderResponse<>(value, source, true, degraded, degradationCode);
    }

    public ProviderResponse<T> asDegraded(ProviderErrorCode code) {
        return new ProviderResponse<>(value, source, true, true, code);
    }
}
