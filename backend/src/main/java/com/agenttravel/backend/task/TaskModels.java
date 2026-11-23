package com.agenttravel.backend.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class TaskModels {

    private TaskModels() {
    }

    public record CreateTaskRequest(
            @JsonProperty("task_type") @NotBlank @Size(max = 32) String taskType,
            @JsonProperty("trip_id") UUID tripId,
            @JsonProperty("base_version_id") UUID baseVersionId,
            @JsonProperty("plan_count") @Min(1) @Max(3) int planCount,
            @NotEmpty Map<String, Object> request) {
    }

    public record CancelTaskRequest(@JsonProperty("expected_version") @Min(0) long expectedVersion) {
    }

    public record TaskView(
            @JsonProperty("task_id") UUID taskId,
            @JsonProperty("owner_id") UUID ownerId,
            @JsonProperty("quote_id") UUID quoteId,
            @JsonProperty("task_type") String taskType,
            TaskStatus status,
            @JsonProperty("state_version") long stateVersion,
            @JsonProperty("reserved_points") long reservedPoints,
            @JsonProperty("operation_deadline_at") Instant operationDeadlineAt,
            @JsonProperty("confirmation_deadline_at") Instant confirmationDeadlineAt,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt,
            boolean reused) {
    }

    public record PlanView(
            @JsonProperty("task_id") UUID taskId,
            String status,
            @JsonProperty("trip_id") UUID tripId,
            @JsonProperty("version_id") UUID versionId,
            @JsonProperty("version_no") int versionNo,
            String etag,
            @JsonProperty("content_hash") String contentHash,
            @JsonProperty("schema_version") String schemaVersion,
            @JsonProperty("generated_at") Instant generatedAt,
            Map<String, Object> plan,
            Map<String, Object> summary,
            @JsonProperty("source_metadata") List<Map<String, Object>> sourceMetadata,
            @JsonProperty("degraded_fields") List<String> degradedFields) {
    }

    public record TaskEventView(
            @JsonProperty("sequence_no") long sequenceNo,
            @JsonProperty("event_type") String eventType,
            String status,
            @JsonProperty("step_name") String stepName,
            @JsonProperty("attempt_no") Integer attemptNo,
            String message,
            @JsonProperty("degraded_fields") List<String> degradedFields,
            @JsonProperty("created_at") Instant createdAt) {
    }

    /**
     * One queued task command. {@code sequenceNo} is the task_event sequence that produced
     * this message; consumers use it to drop duplicate or out-of-order deliveries.
     */
    public record QueueMessage(UUID taskId, UUID outboxId, String eventType, String status, long sequenceNo) {

        public QueueMessage(UUID taskId, UUID outboxId, String eventType, String status) {
            this(taskId, outboxId, eventType, status, 0L);
        }
    }

    public record QueueRecord(String recordId, QueueMessage message) {
    }

    public record StepDefinition(TaskStatus from, TaskStatus to, String stepName) {
    }

    public record WorkerResult(
            UUID resultId,
            String status,
            Map<String, Object> result,
            List<String> degradedFields,
            String agentId) {

        public WorkerResult(UUID resultId, String status, Map<String, Object> result,
                List<String> degradedFields) {
            this(resultId, status, result, degradedFields, "mock-agent");
        }
    }

    public record EventData(
            String status,
            @JsonProperty("step_name") String stepName,
            @JsonProperty("attempt_no") Integer attemptNo,
            String message,
            @JsonProperty("degraded_fields") List<String> degradedFields) {
    }
}
