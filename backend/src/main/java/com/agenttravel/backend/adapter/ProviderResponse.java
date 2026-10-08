package com.agenttravel.backend.adapter;

import java.util.Objects;

public record ProviderResponse<T>(T value, ProviderSource source, boolean cacheHit) {

    public ProviderResponse {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }

    public ProviderResponse<T> fromCache() {
        return new ProviderResponse<>(value, source, true);
    }
}
