package com.agenttravel.backend.adapter;

import java.time.Duration;
import java.util.Objects;

public record ProviderPolicy(
        Duration timeout,
        int maxRetries,
        Duration retryBackoff,
        int circuitFailureThreshold,
        Duration circuitOpenDuration,
        int rateLimitPerSecond,
        int quotaPerWindow,
        Duration quotaWindow,
        Duration cacheTtl,
        Duration negativeCacheTtl,
        String cacheVersion) {

    public ProviderPolicy {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(retryBackoff, "retryBackoff");
        Objects.requireNonNull(circuitOpenDuration, "circuitOpenDuration");
        Objects.requireNonNull(quotaWindow, "quotaWindow");
        Objects.requireNonNull(cacheTtl, "cacheTtl");
        Objects.requireNonNull(negativeCacheTtl, "negativeCacheTtl");
        Objects.requireNonNull(cacheVersion, "cacheVersion");
        if (timeout.isZero() || timeout.isNegative() || maxRetries < 0
                || retryBackoff.isNegative() || circuitFailureThreshold < 1
                || circuitOpenDuration.isZero() || circuitOpenDuration.isNegative()
                || rateLimitPerSecond < 1 || quotaPerWindow < 1
                || quotaWindow.isZero() || quotaWindow.isNegative() || cacheVersion.isBlank()) {
            throw new IllegalArgumentException("供应商韧性配置无效");
        }
    }

    public static ProviderPolicy defaults() {
        return new ProviderPolicy(Duration.ofMillis(1500), 1, Duration.ofMillis(10), 3,
                Duration.ofSeconds(10), 20, 1000, Duration.ofHours(24), Duration.ofMinutes(5),
                Duration.ofSeconds(15), "v1");
    }
}
