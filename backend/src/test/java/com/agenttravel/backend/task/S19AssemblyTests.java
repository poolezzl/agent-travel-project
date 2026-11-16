package com.agenttravel.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.agenttravel.backend.common.AuditRepository;
import com.agenttravel.backend.points.PointsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class S19AssemblyTests {

    private final TaskRepository repository = mock(TaskRepository.class);
    private final TaskPlanRepository planRepository = mock(TaskPlanRepository.class);
    private final PointsService points = mock(PointsService.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final TaskAssemblyService assembly = new TaskAssemblyService(repository, planRepository, points,
            audit, new TaskProperties());

    @Test
    void confirmationPlanIsPersistedWithoutCreatingAnyVersion() {
        UUID taskId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        stubTask(task(taskId, ownerId, TaskStatus.ASSEMBLING, 3, 10), taskId);
        TaskModels.WorkerResult result = result(confirmationPlan(), "degraded");

        assertThat(assembly.finalizeAssembly(claim(taskId), result)).isTrue();

        verify(repository).setConfirmationDeadline(eq(taskId), any());
        verify(repository).transitionStatus(eq(taskId), eq(TaskStatus.ASSEMBLING),
                eq(TaskStatus.AWAITING_CONFIRMATION), eq(3L), any());
        verify(planRepository, never()).insertVersion(any(), any(), anyInt(), any(), any(), any(), any(), any());
        verify(points, never()).confirm(any(), any(), anyString());
    }

    @Test
    void completedPlanCreatesTripVersionSnapshotAndConfirmsPoints() {
        UUID taskId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        TaskRepository.TaskRow task = task(taskId, ownerId, TaskStatus.ASSEMBLING, 4, 10);
        stubTask(task, taskId);

        assertThat(assembly.finalizeAssembly(claim(taskId), result(okPlan(), "ok"))).isTrue();

        ArgumentCaptor<UUID> tripId = ArgumentCaptor.forClass(UUID.class);
        verify(planRepository).insertTrip(tripId.capture(), eq(ownerId), anyString(), any(), any(), any());
        verify(repository).bindTrip(taskId, tripId.getValue());
        verify(planRepository).insertVersion(any(), eq(tripId.getValue()), eq(1), any(), any(),
                eq("s19-plan-v1"), eq(ownerId), any());
        verify(planRepository).insertPlanSnapshot(any(), any(), anyString(), anyString(), any());
        verify(planRepository).setTripCurrentVersion(eq(tripId.getValue()), any(), any());
        verify(points).confirm(eq(ownerId), any(), eq("task-confirm:" + taskId));
        verify(repository).transitionStatus(eq(taskId), eq(TaskStatus.ASSEMBLING), eq(TaskStatus.SUCCEEDED),
                eq(4L), any());
        verify(repository).appendEvent(eq(taskId), eq("TASK_VERSION_CREATED"), any());
    }

    @Test
    void partialStatusMapsToPartialTask() {
        UUID taskId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        stubTask(task(taskId, ownerId, TaskStatus.ASSEMBLING, 0, 0), taskId);

        assertThat(assembly.finalizeAssembly(claim(taskId), result(okPlan(), "partial"))).isTrue();

        verify(repository).transitionStatus(eq(taskId), eq(TaskStatus.ASSEMBLING), eq(TaskStatus.PARTIAL),
                eq(0L), any());
        verify(points, never()).confirm(any(), any(), anyString());
    }

    @Test
    void existingContentHashReusesTheVersionWithoutInsertingAnother() {
        UUID taskId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        TaskRepository.TaskRow task = task(taskId, ownerId, TaskStatus.ASSEMBLING, 1, 0);
        stubTask(task, taskId);
        when(planRepository.findVersionByHash(any(), any())).thenReturn(new TaskPlanRepository.VersionRow(
                UUID.randomUUID(), 7, "etag", "hash", "s19-plan-v1"));

        assertThat(assembly.finalizeAssembly(claim(taskId), result(okPlan(), "ok"))).isTrue();

        verify(planRepository, never()).insertVersion(any(), any(), anyInt(), any(), any(), any(), any(), any());
        verify(planRepository, never()).insertPlanSnapshot(any(), any(), anyString(), anyString(), any());
        verify(repository).appendEvent(eq(taskId), eq("TASK_VERSION_CREATED"), any());
    }

    @Test
    void invalidPlanFailsClosedWithoutTouchingTheDatabase() {
        UUID taskId = UUID.randomUUID();
        stubTask(task(taskId, UUID.randomUUID(), TaskStatus.ASSEMBLING, 0, 10), taskId);
        Map<String, Object> envelope = Map.of("status", "ok", "plan", Map.of("plan_version", "unknown"));

        assertThat(assembly.finalizeAssembly(claim(taskId), result(envelope))).isFalse();

        verify(planRepository, never()).insertVersion(any(), any(), anyInt(), any(), any(), any(), any(), any());
        verify(repository, never()).transitionStatus(any(), any(), any(), any(), any());
    }

    private void stubTask(TaskRepository.TaskRow task, UUID taskId) {
        when(repository.findTaskForUpdate(taskId)).thenReturn(task);
        when(repository.transitionStatus(eq(taskId), eq(TaskStatus.ASSEMBLING), any(), any(), any()))
                .thenReturn(1);
        when(repository.bindTrip(eq(taskId), any())).thenReturn(1);
        when(planRepository.nextVersionNo(any())).thenReturn(1);
        when(planRepository.setTripCurrentVersion(any(), any(), any())).thenReturn(1);
    }

    private static TaskRepository.TaskRow task(UUID taskId, UUID ownerId, TaskStatus status, long version,
            long reservedPoints) {
        Instant now = Instant.now();
        return new TaskRepository.TaskRow(taskId, ownerId, UUID.randomUUID(), "GENERATION", status, version,
                reservedPoints, now.plusSeconds(180), null, now, now);
    }

    private static TaskWorkerClaimService.Claim claim(UUID taskId) {
        return new TaskWorkerClaimService.Claim(taskId, UUID.randomUUID(), "assemble", 1, "worker",
                TaskStatus.ASSEMBLING, null);
    }

    private static TaskModels.WorkerResult result(Map<String, Object> plan, String status) {
        return new TaskModels.WorkerResult(UUID.randomUUID(), status,
                Map.of("status", status, "plan", plan), List.of("simulated"), "python-orchestrator");
    }

    private static TaskModels.WorkerResult result(Map<String, Object> envelope) {
        return new TaskModels.WorkerResult(UUID.randomUUID(), "ok", envelope, List.of(), "python-orchestrator");
    }

    private static Map<String, Object> confirmationPlan() {
        Map<String, Object> plan = new java.util.LinkedHashMap<>(okPlan());
        plan.put("confirmation", Map.of("required", true, "conflict_codes", List.of("MUST_VISIT_CLOSED")));
        return plan;
    }

    private static Map<String, Object> okPlan() {
        Map<String, Object> plan = new java.util.LinkedHashMap<>();
        plan.put("plan_version", "s19-plan-v1");
        plan.put("generated_at", Instant.now().toString());
        plan.put("sample_only", false);
        plan.put("trip", Map.of(
                "city_id", "beijing",
                "city_name", "北京",
                "start_date", "2026-11-20",
                "end_date", "2026-11-22",
                "day_count", 3,
                "party_size", 2,
                "transport_preference", "混合"));
        plan.put("summary", Map.of("point_count", 1, "degraded", false));
        plan.put("days", List.of(Map.of("date", "2026-11-20", "segments", List.of())));
        plan.put("quality", Map.of("status", "pass", "checks", List.of()));
        plan.put("conflicts", List.of());
        plan.put("confirmation", Map.of("required", false));
        plan.put("sources", List.of());
        plan.put("degraded_fields", List.of());
        return plan;
    }
}
