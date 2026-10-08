package com.agenttravel.backend.adapter;

import java.time.Instant;
import java.util.Objects;

public record ProviderSource(
        String providerAlias,
        String sourceRecordId,
        Instant retrievedAt,
        Instant validUntil,
        FreshnessStatus freshnessStatus,
        boolean estimated) {

    public ProviderSource {
        Objects.requireNonNull(providerAlias, "providerAlias");
        Objects.requireNonNull(sourceRecordId, "sourceRecordId");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
        Objects.requireNonNull(freshnessStatus, "freshnessStatus");
        if (providerAlias.isBlank() || sourceRecordId.isBlank()) {
            throw new IllegalArgumentException("来源标识不能为空");
        }
    }

    public FreshnessStatus freshnessAt(Instant now) {
        if (validUntil == null) {
            return FreshnessStatus.UNKNOWN;
        }
        return now.isBefore(validUntil) ? FreshnessStatus.FRESH : FreshnessStatus.STALE;
    }

    public ProviderSource refreshedStatus(Instant now) {
        return new ProviderSource(providerAlias, sourceRecordId, retrievedAt, validUntil,
                freshnessAt(now), estimated);
    }
}
