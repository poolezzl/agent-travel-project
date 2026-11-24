package com.agenttravel.backend.preference;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class PreferenceModels {

    record HistoricalTrip(
            UUID id,
            String title,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            List<String> cities,
            int versionCount,
            UUID currentVersionId) {
    }

    record ReuseResult(UUID tripId, UUID versionId, List<String> removedFields) {
    }

    record VersionSummary(
            UUID versionId,
            int versionNo,
            String versionStatus,
            java.time.Instant createdAt,
            String schemaVersion) {
    }

    record VersionChange(String path, String changeType, Object before, Object after) {
    }

    record VersionComparison(
            UUID tripId,
            VersionSummary from,
            VersionSummary to,
            List<VersionChange> changes) {
    }

    record PreferenceItem(
            String key,
            Object valueJson,
            String sourceType,
            Double confidence,
            int weight,
            java.time.Instant lastUsedAt,
            String reason) {
    }

    record PreferenceSummary(
            int summaryVersion,
            int sampleCount,
            boolean eligible,
            List<PreferenceItem> dimensions,
            String narrative,
            java.time.Instant generatedAt) {
    }

    private PreferenceModels() {
    }
}
