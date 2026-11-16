package com.agenttravel.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class S19PlanServiceTests {

    private final TaskRepository repository = mock(TaskRepository.class);
    private final TaskPlanRepository planRepository = mock(TaskPlanRepository.class);
    private final TaskPlanService service = new TaskPlanService(repository, planRepository,
            new ObjectMapper());

    @Test
    void publishedPlanIsReadFromTheImmutableVersionSnapshot() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(repository.findTask(taskId)).thenReturn(task(taskId, userId, TaskStatus.SUCCEEDED));
        String planJson = new ObjectMapper().writeValueAsString(Map.of(
                "plan_version", "s19-plan-v1",
                "sources", List.of(Map.of("source_type", "knowledge_base", "source_record_id", "SRC-1")),
                "degraded_fields", List.of("days[0].weather")));
        when(planRepository.findPlanByTask(taskId)).thenReturn(new TaskPlanRepository.PlanRow(taskId,
                "SUCCEEDED", tripId, versionId, 2, "ETAG", "HASH", "s19-plan-v1", planJson,
                "{\"point_count\":1}", Instant.parse("2026-11-16T06:00:00Z")));

        TaskModels.PlanView view = service.get(userId, "USER", taskId);

        assertThat(view.status()).isEqualTo("SUCCEEDED");
        assertThat(view.versionNo()).isEqualTo(2);
        assertThat(view.tripId()).isEqualTo(tripId);
        assertThat(view.schemaVersion()).isEqualTo("s19-plan-v1");
        assertThat(view.plan()).containsEntry("plan_version", "s19-plan-v1");
        assertThat(view.summary()).containsEntry("point_count", 1);
        assertThat(view.sourceMetadata()).hasSize(1);
        assertThat(view.degradedFields()).containsExactly("days[0].weather");
    }

    @Test
    void nonTerminalTaskIsNotReadableYet() {
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(repository.findTask(taskId)).thenReturn(task(taskId, userId, TaskStatus.ROUTING));

        assertThatThrownBy(() -> service.get(userId, "USER", taskId))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("PLAN_NOT_READY");
    }

    @Test
    void awaitingConfirmationHasItsOwnStableCode() {
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(repository.findTask(taskId)).thenReturn(task(taskId, userId, TaskStatus.AWAITING_CONFIRMATION));

        assertThatThrownBy(() -> service.get(userId, "USER", taskId))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("CONFIRMATION_REQUIRED");
    }

    @Test
    void otherUsersCannotReadThePlan() {
        UUID taskId = UUID.randomUUID();
        when(repository.findTask(taskId)).thenReturn(task(taskId, UUID.randomUUID(), TaskStatus.SUCCEEDED));

        assertThatThrownBy(() -> service.get(UUID.randomUUID(), "USER", taskId))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("CONTEXT_NOT_FOUND");
    }

    private static TaskRepository.TaskRow task(UUID taskId, UUID ownerId, TaskStatus status) {
        Instant now = Instant.now();
        return new TaskRepository.TaskRow(taskId, ownerId, UUID.randomUUID(), "GENERATION", status, 3, 10,
                now.plusSeconds(180), null, now, now);
    }
}
