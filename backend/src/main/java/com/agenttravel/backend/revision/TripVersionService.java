package com.agenttravel.backend.revision;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Read model for trip version history and version content.
 *
 * <p>Closes {@code PENDING-S21-01} / {@code PENDING-S22-03}: cross-session version timelines and
 * version evolution comparison need a version list plus a read-by-version endpoint. Published
 * versions are immutable, so this service never writes.
 *
 * <p>Ownership is enforced here, not in the controller: a missing trip, a soft-deleted trip and a
 * trip owned by somebody else all return the same {@code 404 CONTEXT_NOT_FOUND} so callers cannot
 * probe for other users' trip ids.
 */
@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TripVersionService {

    private static final int MAX_LIMIT = 100;

    private final TripVersionRepository repository;
    private final ObjectMapper objectMapper;

    public TripVersionService(TripVersionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public List<TripVersionModels.VersionSummary> list(UUID userId, String role, UUID tripId, int limit,
            int offset) {
        TripVersionRepository.TripRow trip = ownedTrip(userId, role, tripId);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int safeOffset = Math.max(offset, 0);
        return repository.listVersions(tripId, safeLimit, safeOffset).stream()
                .map(row -> new TripVersionModels.VersionSummary(row.versionId(), tripId, row.versionNo(),
                        row.parentVersionId(), row.status(), row.etag(), row.contentHash(), row.schemaVersion(),
                        row.createdAt(), row.versionId().equals(trip.currentVersionId()), row.changeType()))
                .toList();
    }

    public TripVersionModels.VersionDetail get(UUID userId, String role, UUID tripId, UUID versionId) {
        TripVersionRepository.TripRow trip = ownedTrip(userId, role, tripId);
        TripVersionRepository.DetailRow row = repository.findVersion(tripId, versionId);
        if (row == null) {
            throw notFound();
        }
        if (row.planJson() == null || row.planJson().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_NOT_READY", "该版本尚无可用行程内容");
        }
        Map<String, Object> plan = readMap(row.planJson(), "PLAN_CORRUPTED", "行程版本内容损坏");
        Map<String, Object> summary = readMap(row.summaryJson(), "PLAN_CORRUPTED", "行程版本摘要损坏");
        return new TripVersionModels.VersionDetail(row.versionId(), tripId, row.versionNo(), row.parentVersionId(),
                row.status(), row.etag(), row.contentHash(), row.schemaVersion(), row.createdAt(),
                row.versionId().equals(trip.currentVersionId()), row.changeType(),
                affectedPaths(row.affectedPathsJson()), plan, summary, sources(plan), degraded(plan));
    }

    private TripVersionRepository.TripRow ownedTrip(UUID userId, String role, UUID tripId) {
        TripVersionRepository.TripRow trip = repository.findTrip(tripId);
        if (trip == null || (!trip.ownerId().equals(userId) && !"ADMIN".equals(role))) {
            throw notFound();
        }
        return trip;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "CONTEXT_NOT_FOUND", "行程不存在");
    }

    private Map<String, Object> readMap(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, code, message);
        }
    }

    private List<String> affectedPaths(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<String> paths = objectMapper.readValue(value, new TypeReference<>() {
            });
            return paths == null ? List.of() : paths;
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sources(Map<String, Object> plan) {
        if (!(plan.get("sources") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private List<String> degraded(Map<String, Object> plan) {
        if (!(plan.get("degraded_fields") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
