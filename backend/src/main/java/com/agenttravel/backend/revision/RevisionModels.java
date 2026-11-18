package com.agenttravel.backend.revision;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

public final class RevisionModels {

    private RevisionModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AtomicChange(
            @NotBlank String operation,
            @NotBlank String path,
            Object value) {
    }

    public record RevisionCommand(
            @JsonProperty("base_version_id") UUID baseVersionId,
            String etag,
            String intent,
            @JsonProperty("locked_paths") List<String> lockedPaths,
            @JsonProperty("affected_days") List<Integer> affectedDays,
            @JsonProperty("affected_agents") List<String> affectedAgents,
            List<AtomicChange> changes,
            @JsonProperty("needs_confirm") Boolean needsConfirm,
            Boolean confirmed,
            @JsonProperty("confirmation_id") String confirmationId,
            @JsonProperty("revision_request") String revisionRequest,
            Map<String, Object> plan,
            String answer,
            @JsonProperty("clarification_question") String clarificationQuestion,
            @JsonProperty("schema_version") String schemaVersion,
            @JsonProperty("model_version") String modelVersion,
            @JsonProperty("prompt_version") String promptVersion,
            @JsonProperty("source_snapshot_ids") List<UUID> sourceSnapshotIds) {
    }

    public record RevisionResult(
            String intent,
            @JsonProperty("base_version_id") UUID baseVersionId,
            @JsonProperty("version_id") UUID versionId,
            @JsonProperty("parent_version_id") UUID parentVersionId,
            String etag,
            String status,
            @JsonProperty("needs_confirm") boolean needsConfirm,
            @JsonProperty("affected_days") List<Integer> affectedDays,
            @JsonProperty("affected_agents") List<String> affectedAgents,
            List<AtomicChange> changes,
            String answer,
            @JsonProperty("clarification_question") String clarificationQuestion,
            String summary,
            Instant createdAt,
            boolean reused,
            @JsonProperty("change_set") List<AtomicChange> changeSet,
            @JsonProperty("source_snapshot_ids") List<UUID> sourceSnapshotIds,
            @JsonProperty("schema_version") String schemaVersion,
            @JsonProperty("model_version") String modelVersion,
            @JsonProperty("prompt_version") String promptVersion,
            @JsonProperty("content_hash") String contentHash) {
    }
}
