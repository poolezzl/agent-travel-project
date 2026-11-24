package com.agenttravel.backend.revision;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read models for trip version history (closes PENDING-S21-01 / PENDING-S22-03). */
final class TripVersionModels {

    private TripVersionModels() {
    }

    record VersionSummary(
            UUID versionId,
            UUID tripId,
            int versionNo,
            UUID parentVersionId,
            String status,
            String etag,
            String contentHash,
            String schemaVersion,
            Instant createdAt,
            boolean current,
            String changeType) {
    }

    record VersionDetail(
            UUID versionId,
            UUID tripId,
            int versionNo,
            UUID parentVersionId,
            String status,
            String etag,
            String contentHash,
            String schemaVersion,
            Instant createdAt,
            boolean current,
            String changeType,
            List<String> affectedPaths,
            Map<String, Object> plan,
            Map<String, Object> summary,
            List<Map<String, Object>> sourceMetadata,
            List<String> degradedFields) {
    }
}
