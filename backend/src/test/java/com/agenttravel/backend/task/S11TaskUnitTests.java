package com.agenttravel.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import com.agenttravel.backend.points.PointsModels;
import com.agenttravel.backend.points.PointsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class S11TaskUnitTests {

    @Test
    void stateMachineAllowsOnlyFrozenTransitions() {
        assertThat(TaskStateMachine.canTransition(TaskStatus.QUEUED, TaskStatus.PARSING)).isTrue();
        assertThat(TaskStateMachine.canTransition(TaskStatus.PARSING, TaskStatus.ROUTING)).isFalse();
        assertThat(TaskStateMachine.canTransition(TaskStatus.CANCELLED, TaskStatus.REFUNDED)).isTrue();
        assertThat(TaskStateMachine.canTransition(TaskStatus.SUCCEEDED, TaskStatus.PARSING)).isFalse();
        assertThatThrownBy(() -> TaskStateMachine.require(TaskStatus.SUCCEEDED, TaskStatus.PARSING))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("CONCURRENCY_CONFLICT");
    }

    @Test
    void sensitiveKeysAreRedactedRecursively() {
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer();
        Map<String, Object> result = sanitizer.sanitize(Map.of(
                "city", "北京",
                "api_key", "secret-value",
                "nested", Map.of("phone", "synthetic-phone", "days", 3)));

        assertThat(result).containsEntry("city", "北京").containsEntry("api_key", "[REDACTED]");
        assertThat(result.toString()).doesNotContain("secret-value").doesNotContain("synthetic-phone");
    }

    @Test
    void canonicalRequestHashDoesNotDependOnMapInsertionOrder() {
        CanonicalJson canonical = new CanonicalJson();
        byte[] first = canonical.hash(Map.of("b", 2, "a", 1));
        byte[] second = canonical.hash(new java.util.LinkedHashMap<>(Map.of("a", 1, "b", 2)));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void redisQueueUsesTheFrozenTaskKeyAndSeparatePrefixes() {
        UUID taskId = UUID.randomUUID();

        assertThat(RedisTaskQueue.key(taskId)).isEqualTo("queue:v1:task:" + taskId);
        assertThat(RedisTaskQueue.key(taskId)).doesNotStartWith("cache:").doesNotStartWith("rate:");
    }

    @Test
    void createOrchestratesTaskQuoteReserveAndOutboxInOneSpringTransaction() throws NoSuchMethodException {
        TaskRepository repository = mock(TaskRepository.class);
        PointsService points = mock(PointsService.class);
        S11Metrics metrics = new S11Metrics(new SimpleMeterRegistry());
        TaskService service = new TaskService(repository, points, new SensitiveDataSanitizer(), metrics,
                new TaskProperties(), mock(AuditRepository.class));
        UUID userId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        when(repository.findTaskByIdempotency(eq(userId), eq("create-key"))).thenReturn(null);
        when(points.quote(eq(userId), any(), anyString()))
                .thenReturn(new PointsModels.QuoteResponse(quoteId, "quote-reference", 1, 10,
                        Instant.now().plusSeconds(300).getEpochSecond()));
        when(points.reserve(eq(userId), any(), anyString()))
                .thenReturn(new PointsModels.PointsResponse("reserve", -10, 90, false));
        when(repository.transitionStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(repository.findTask(any())).thenAnswer(invocation -> task(invocation.getArgument(0), userId,
                TaskStatus.QUEUED, 2, 10));

        TaskModels.TaskView result = service.create(userId,
                new TaskModels.CreateTaskRequest("GENERATION", null, null, 1, Map.of("city", "北京")),
                "create-key");

        assertThat(result.status()).isEqualTo(TaskStatus.QUEUED);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository, points);
        order.verify(repository).insertTask(any(), eq(userId), any(), any(), eq("GENERATION"), eq("create-key"),
                any(), anyString(), any());
        order.verify(repository).appendEvent(any(), eq("TASK_STATUS_CHANGED"), any());
        order.verify(points).quote(eq(userId), any(), anyString());
        order.verify(points).reserve(eq(userId), any(), anyString());
        order.verify(repository).transitionStatus(any(), eq(TaskStatus.BALANCE_RESERVED), eq(TaskStatus.QUEUED),
                any(), any());
        order.verify(repository).insertOutbox(any(), eq("TASK_QUEUED"), anyString(), any(), any());
        assertThat(TaskService.class.getMethod("create", UUID.class, TaskModels.CreateTaskRequest.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void repeatedCreateWithSameRequestReturnsExistingTaskWithoutSecondReserve() {
        TaskRepository repository = mock(TaskRepository.class);
        PointsService points = mock(PointsService.class);
        TaskService service = new TaskService(repository, points, new SensitiveDataSanitizer(),
                new S11Metrics(new SimpleMeterRegistry()), new TaskProperties(), mock(AuditRepository.class));
        UUID userId = UUID.randomUUID();
        TaskModels.CreateTaskRequest request = new TaskModels.CreateTaskRequest("GENERATION", null, null, 1,
                Map.of("city", "北京"));
        TaskRepository.TaskRow existing = task(UUID.randomUUID(), userId, TaskStatus.QUEUED, 2, 10);
        CanonicalJson canonical = new CanonicalJson();
        when(repository.findTaskByIdempotency(userId, "same-key")).thenReturn(existing);
        when(repository.requestHash(existing.id())).thenReturn(canonical.hash(request));

        TaskModels.TaskView result = service.create(userId, request, "same-key");

        assertThat(result.reused()).isTrue();
        verify(points, never()).quote(any(), any(), anyString());
        verify(points, never()).reserve(any(), any(), anyString());
        verify(repository, never()).insertTask(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sameIdempotencyKeyWithChangedRequestIsRejected() {
        TaskRepository repository = mock(TaskRepository.class);
        TaskService service = new TaskService(repository, mock(PointsService.class),
                new SensitiveDataSanitizer(), new S11Metrics(new SimpleMeterRegistry()), new TaskProperties(),
                mock(AuditRepository.class));
        UUID userId = UUID.randomUUID();
        TaskRepository.TaskRow existing = task(UUID.randomUUID(), userId, TaskStatus.QUEUED, 2, 10);
        when(repository.findTaskByIdempotency(userId, "same-key")).thenReturn(existing);
        when(repository.requestHash(existing.id())).thenReturn(new byte[] { 1 });

        assertThatThrownBy(() -> service.create(userId,
                new TaskModels.CreateTaskRequest("GENERATION", null, null, 1, Map.of("city", "上海")), "same-key"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("DUPLICATE_REQUEST");
    }

    @Test
    void cancellationRefundsOwnerBeforeCommittingCancelledAndRefundedStates() {
        TaskRepository repository = mock(TaskRepository.class);
        PointsService points = mock(PointsService.class);
        TaskService service = new TaskService(repository, points, new SensitiveDataSanitizer(),
                new S11Metrics(new SimpleMeterRegistry()), new TaskProperties(), mock(AuditRepository.class));
        UUID owner = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskRepository.TaskRow running = task(taskId, owner, TaskStatus.PARSING, 3, 10);
        when(repository.findTaskForUpdate(taskId)).thenReturn(running);
        when(repository.transitionStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(repository.findTask(taskId)).thenReturn(task(taskId, owner, TaskStatus.REFUNDED, 5, 10));
        when(points.refund(eq(owner), any(), anyString()))
                .thenReturn(new PointsModels.PointsResponse("refund", 10, 100, false));

        TaskModels.TaskView result = service.cancel(owner, "USER", taskId,
                new TaskModels.CancelTaskRequest(3), "cancel-key");

        assertThat(result.status()).isEqualTo(TaskStatus.REFUNDED);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(points, repository);
        order.verify(points).refund(eq(owner), any(), eq("task-cancel:" + taskId));
        order.verify(repository).transitionStatus(eq(taskId), eq(TaskStatus.PARSING), eq(TaskStatus.CANCELLED),
                eq(3L), any());
        order.verify(repository).transitionStatus(eq(taskId), eq(TaskStatus.CANCELLED), eq(TaskStatus.REFUNDED),
                any(), any());
        verify(repository).insertOutbox(eq(taskId), eq("TASK_CANCELLED"), anyString(), any(), any());
    }

    @Test
    void staleLeaseOwnerCannotCompleteAnotherWorkersStep() {
        TaskRepository repository = mock(TaskRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        S11Metrics metrics = new S11Metrics(new SimpleMeterRegistry());
        TaskWorkerClaimService worker = new TaskWorkerClaimService(repository,
                mock(TaskTerminationService.class), audit, new TaskProperties(), metrics);
        UUID taskId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(repository.findTask(taskId)).thenReturn(task(taskId, owner, TaskStatus.PARSING, 3, 10));
        when(repository.findTaskForUpdate(taskId)).thenReturn(task(taskId, owner, TaskStatus.PARSING, 3, 10));
        when(repository.latestStep(taskId, "parse")).thenReturn(new TaskRepository.StepRow(
                UUID.randomUUID(), "parse", "RUNNING", 1, "other-worker", Instant.now().plusSeconds(30),
                Instant.now(), null));

        TaskWorkerClaimService.ClaimResult result = worker.claim(taskId, "current-worker");

        assertThat(result.busy()).isTrue();
        verify(repository, never()).heartbeat(any(), anyString(), anyInt(), any(), any());
        verify(audit, never()).write(any(), anyString(), anyString(), any(), anyString(), anyString());
        assertThat(metrics.count("stale-results")).isZero();
    }

    @Test
    void terminalTaskIgnoresLateWorkerMessage() {
        TaskRepository repository = mock(TaskRepository.class);
        TaskWorkerClaimService worker = new TaskWorkerClaimService(repository,
                mock(TaskTerminationService.class), mock(AuditRepository.class), new TaskProperties(),
                new S11Metrics(new SimpleMeterRegistry()));
        UUID taskId = UUID.randomUUID();
        when(repository.findTaskForUpdate(taskId))
                .thenReturn(task(taskId, UUID.randomUUID(), TaskStatus.REFUNDED, 7, 10));

        assertThat(worker.claim(taskId, "worker").terminal()).isTrue();
        verify(repository, never()).insertAgentResult(any(), any(), anyInt(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void expiredLeaseIsRecoveredBeforeAnotherAttemptStarts() {
        TaskRepository repository = mock(TaskRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        S11Metrics metrics = new S11Metrics(new SimpleMeterRegistry());
        TaskWorkerClaimService worker = new TaskWorkerClaimService(repository,
                mock(TaskTerminationService.class), audit, new TaskProperties(), metrics);
        UUID taskId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        when(repository.findTaskForUpdate(taskId))
                .thenReturn(task(taskId, UUID.randomUUID(), TaskStatus.PARSING, 3, 10));
        when(repository.latestStep(taskId, "parse")).thenReturn(new TaskRepository.StepRow(stepId, "parse",
                "RUNNING", 1, "crashed-worker", Instant.now().minusSeconds(1), Instant.now().minusSeconds(20),
                null));
        when(repository.expireStep(eq(stepId), any())).thenReturn(1);

        TaskWorkerClaimService.ClaimResult result = worker.claim(taskId, "recovery-worker");

        assertThat(result.hasWork()).isFalse();
        verify(repository).expireStep(eq(stepId), any());
        assertThat(metrics.count("leases-recovered")).isEqualTo(1.0);
    }

    @Test
    void exhaustedStepAttemptsTerminateTaskAndStartRefundFlow() {
        TaskRepository repository = mock(TaskRepository.class);
        TaskTerminationService termination = mock(TaskTerminationService.class);
        TaskWorkerClaimService worker = new TaskWorkerClaimService(repository, termination,
                mock(AuditRepository.class), new TaskProperties(), new S11Metrics(new SimpleMeterRegistry()));
        UUID taskId = UUID.randomUUID();
        when(repository.findTaskForUpdate(taskId))
                .thenReturn(task(taskId, UUID.randomUUID(), TaskStatus.PARSING, 3, 10));
        when(repository.latestStep(taskId, "parse")).thenReturn(new TaskRepository.StepRow(UUID.randomUUID(),
                "parse", "FAILED", 3, null, null, null, null));

        TaskWorkerClaimService.ClaimResult result = worker.claim(taskId, "worker");

        assertThat(result.terminal()).isTrue();
        verify(termination).failAndRefund(taskId, "STEP_RETRY_EXHAUSTED");
    }

    @Test
    void heartbeatRenewsOnlyWhileClaimIsActive() {
        TaskWorkerClaimService claims = mock(TaskWorkerClaimService.class);
        TaskHeartbeatService heartbeat = new TaskHeartbeatService(claims, new TaskProperties());
        TaskWorkerClaimService.Claim claim = new TaskWorkerClaimService.Claim(UUID.randomUUID(), UUID.randomUUID(),
                "parse", 1, "worker", TaskStatus.PARSING, TaskStatus.COLLECTING);
        when(claims.heartbeat(claim)).thenReturn(true);

        heartbeat.start(claim);
        heartbeat.heartbeatAll();

        verify(claims).heartbeat(claim);
        assertThat(heartbeat.activeCount()).isEqualTo(1);
        heartbeat.stop(claim);
        assertThat(heartbeat.activeCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = { "ok", "partial", "degraded" })
    void nonFailedResultsUseTheirOwnStepStatusAndAdvance(String status) {
        TaskRepository repository = mock(TaskRepository.class);
        TaskTerminationService termination = mock(TaskTerminationService.class);
        AuditRepository audit = mock(AuditRepository.class);
        TaskWorkerClaimService worker = new TaskWorkerClaimService(repository, termination, audit,
                new TaskProperties(), new S11Metrics(new SimpleMeterRegistry()));
        TaskWorkerClaimService.Claim claim = claim("worker", 1);
        UUID resultId = UUID.randomUUID();
        String expectedStepStatus = switch (status) {
            case "ok" -> "SUCCEEDED";
            case "partial" -> "PARTIAL";
            case "degraded" -> "DEGRADED";
            default -> throw new IllegalStateException();
        };
        when(repository.findTaskForUpdate(claim.taskId()))
                .thenReturn(task(claim.taskId(), UUID.randomUUID(), TaskStatus.PARSING, 3, 10));
        when(repository.heartbeat(any(), eq("worker"), eq(1), any(), any())).thenReturn(1);
        when(repository.insertAgentResult(any(), any(), anyInt(), any(), any(), any(), any(), any(), eq(status),
                any(), any(), any())).thenReturn(resultId);
        when(repository.completeStep(any(), eq("worker"), eq(1), eq(expectedStepStatus), eq(resultId),
                any())).thenReturn(1);
        when(repository.transitionStatus(eq(claim.taskId()), eq(TaskStatus.PARSING), eq(TaskStatus.COLLECTING),
                eq(3L), any())).thenReturn(1);

        assertThat(worker.commit(claim, result(status, List.of("weather_optional")), "trace")).isTrue();

        verify(repository).completeStep(any(), eq("worker"), eq(1), eq(expectedStepStatus), eq(resultId),
                any());
        verify(repository).transitionStatus(eq(claim.taskId()), eq(TaskStatus.PARSING), eq(TaskStatus.COLLECTING),
                eq(3L), any());
        verify(repository, never()).failStepWithResult(any(), anyString(), anyInt(), any(), any());
        verify(termination, never()).failAndRefund(any(), anyString());
    }

    @Test
    void failedResultMarksStepFailedWithoutAdvancingSuccessState() {
        TaskRepository repository = mock(TaskRepository.class);
        TaskTerminationService termination = mock(TaskTerminationService.class);
        AuditRepository audit = mock(AuditRepository.class);
        TaskWorkerClaimService worker = new TaskWorkerClaimService(repository, termination, audit,
                new TaskProperties(), new S11Metrics(new SimpleMeterRegistry()));
        TaskWorkerClaimService.Claim claim = claim("worker", 1);
        UUID resultId = UUID.randomUUID();
        when(repository.findTaskForUpdate(claim.taskId()))
                .thenReturn(task(claim.taskId(), UUID.randomUUID(), TaskStatus.PARSING, 3, 10));
        when(repository.heartbeat(any(), eq("worker"), eq(1), any(), any())).thenReturn(1);
        when(repository.insertAgentResult(any(), any(), anyInt(), any(), any(), any(), any(), any(), eq("failed"),
                any(), any(), any())).thenReturn(resultId);
        when(repository.failStepWithResult(any(), eq("worker"), eq(1), eq(resultId), any())).thenReturn(1);

        assertThat(worker.commit(claim, result("failed", List.of("source_unavailable")), "trace")).isFalse();

        verify(repository).failStepWithResult(any(), eq("worker"), eq(1), eq(resultId), any());
        verify(repository, never()).completeStep(any(), anyString(), anyInt(), anyString(), any(), any());
        verify(repository, never()).transitionStatus(any(), any(), any(), any(), any());
        verify(termination, never()).failAndRefund(any(), anyString());
        verify(audit).write(null, "TASK_STEP_FAILED", "TASK_STEP", claim.stepId(), "FAILURE",
                "AGENT_RESULT_FAILED");
    }

    @Test
    void failedResultAtAttemptLimitTerminatesAndStartsRefund() {
        TaskRepository repository = mock(TaskRepository.class);
        TaskTerminationService termination = mock(TaskTerminationService.class);
        TaskWorkerClaimService worker = new TaskWorkerClaimService(repository, termination,
                mock(AuditRepository.class), new TaskProperties(), new S11Metrics(new SimpleMeterRegistry()));
        TaskWorkerClaimService.Claim claim = claim("worker", 3);
        UUID resultId = UUID.randomUUID();
        when(repository.findTaskForUpdate(claim.taskId()))
                .thenReturn(task(claim.taskId(), UUID.randomUUID(), TaskStatus.PARSING, 3, 10));
        when(repository.heartbeat(any(), eq("worker"), eq(3), any(), any())).thenReturn(1);
        when(repository.insertAgentResult(any(), any(), anyInt(), any(), any(), any(), any(), any(), eq("failed"),
                any(), any(), any())).thenReturn(resultId);
        when(repository.failStepWithResult(any(), eq("worker"), eq(3), eq(resultId), any())).thenReturn(1);

        assertThat(worker.commit(claim, result("failed", List.of()), "trace")).isFalse();

        verify(termination).failAndRefund(claim.taskId(), "STEP_RETRY_EXHAUSTED");
        verify(repository, never()).completeStep(any(), anyString(), anyInt(), anyString(), any(), any());
        verify(repository, never()).transitionStatus(any(), any(), any(), any(), any());
    }

    @Test
    void lateResultForTerminalTaskIsRejectedWithoutWriting() {
        TaskRepository repository = mock(TaskRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        S11Metrics metrics = new S11Metrics(new SimpleMeterRegistry());
        TaskWorkerClaimService worker = new TaskWorkerClaimService(repository,
                mock(TaskTerminationService.class), audit, new TaskProperties(), metrics);
        TaskWorkerClaimService.Claim claim = claim("worker", 1);
        when(repository.findTaskForUpdate(claim.taskId()))
                .thenReturn(task(claim.taskId(), UUID.randomUUID(), TaskStatus.REFUNDED, 7, 10));

        assertThat(worker.commit(claim, result("ok", List.of()), "trace")).isFalse();

        verify(repository, never()).insertAgentResult(any(), any(), anyInt(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
        verify(audit).write(null, "WORKER_RESULT_STALE", "PLANNING_TASK", claim.taskId(), "DENIED",
                "WORKER_RESULT_STALE");
        assertThat(metrics.count("stale-results")).isEqualTo(1.0);
    }

    @Test
    void outboxRelayPublishesBeforeMarkingTheRowPublished() {
        TaskRepository repository = mock(TaskRepository.class);
        TaskQueue queue = mock(TaskQueue.class);
        OutboxRelayService relay = new OutboxRelayService(repository, queue, new TaskProperties(),
                new S11Metrics(new SimpleMeterRegistry()), mock(TaskTerminationService.class));
        TaskModels.QueueMessage message = new TaskModels.QueueMessage(UUID.randomUUID(), UUID.randomUUID(),
                "TASK_QUEUED", "QUEUED");
        TaskRepository.OutboxRow row = new TaskRepository.OutboxRow(message.outboxId(), message.taskId(),
                "TASK_QUEUED", message, 0);
        when(repository.lockPendingOutbox(anyInt(), any())).thenReturn(List.of(row));

        relay.relayPending();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(queue, repository);
        order.verify(queue).publish(message);
        order.verify(repository).markOutboxPublished(eq(row.id()), any());
    }

    @Test
    void outboxPublishFailureIncrementsAttemptAndDoesNotMarkPublished() {
        TaskRepository repository = mock(TaskRepository.class);
        TaskQueue queue = mock(TaskQueue.class);
        S11Metrics metrics = new S11Metrics(new SimpleMeterRegistry());
        OutboxRelayService relay = new OutboxRelayService(repository, queue, new TaskProperties(), metrics,
                mock(TaskTerminationService.class));
        TaskModels.QueueMessage message = new TaskModels.QueueMessage(UUID.randomUUID(), UUID.randomUUID(),
                "TASK_QUEUED", "QUEUED");
        TaskRepository.OutboxRow row = new TaskRepository.OutboxRow(message.outboxId(), message.taskId(),
                "TASK_QUEUED", message, 0);
        when(repository.lockPendingOutbox(anyInt(), any())).thenReturn(List.of(row));
        doThrow(new IllegalStateException("redis unavailable")).when(queue).publish(message);

        relay.relayPending();

        verify(repository, never()).markOutboxPublished(any(), any());
        verify(repository).markOutboxFailed(eq(row.id()), eq(0), eq(5), any(), eq("IllegalStateException"));
        assertThat(metrics.count("outbox-dead")).isZero();
    }

    @Test
    void permanentOutboxFailureTerminatesTaskAndRefunds() {
        TaskRepository repository = mock(TaskRepository.class);
        TaskQueue queue = mock(TaskQueue.class);
        TaskTerminationService termination = mock(TaskTerminationService.class);
        OutboxRelayService relay = new OutboxRelayService(repository, queue, new TaskProperties(),
                new S11Metrics(new SimpleMeterRegistry()), termination);
        TaskModels.QueueMessage message = new TaskModels.QueueMessage(UUID.randomUUID(), UUID.randomUUID(),
                "TASK_QUEUED", "QUEUED");
        TaskRepository.OutboxRow row = new TaskRepository.OutboxRow(message.outboxId(), message.taskId(),
                "TASK_QUEUED", message, 4);
        when(repository.lockPendingOutbox(anyInt(), any())).thenReturn(List.of(row));
        doThrow(new IllegalStateException("redis unavailable")).when(queue).publish(message);

        relay.relayPending();

        verify(termination).failAndRefund(message.taskId(), "OUTBOX_PUBLISH_FAILED");
    }

    @Test
    void workerBoundaryClaimsThenExecutesOutsideThenCommits() throws NoSuchMethodException {
        TaskWorkerClaimService claims = mock(TaskWorkerClaimService.class);
        TaskHeartbeatService heartbeats = mock(TaskHeartbeatService.class);
        MockAgentExecutor executor = mock(MockAgentExecutor.class);
        TaskWorkerService worker = new TaskWorkerService(claims, heartbeats, executor, new TaskProperties());
        UUID taskId = UUID.randomUUID();
        TaskWorkerClaimService.Claim claim = new TaskWorkerClaimService.Claim(taskId, UUID.randomUUID(), "parse",
                1, "worker", TaskStatus.PARSING, TaskStatus.COLLECTING);
        TaskModels.WorkerResult output = new TaskModels.WorkerResult(UUID.randomUUID(), "ok", Map.of(), List.of());
        when(claims.claim(eq(taskId), anyString()))
                .thenReturn(TaskWorkerClaimService.ClaimResult.workResult(claim));
        when(executor.execute(taskId, "parse", 1)).thenReturn(output);
        when(claims.commit(eq(claim), eq(output), anyString())).thenReturn(true);

        assertThat(worker.advance(taskId)).isTrue();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(claims, executor);
        order.verify(claims).claim(eq(taskId), anyString());
        order.verify(executor).execute(taskId, "parse", 1);
        order.verify(claims).commit(eq(claim), eq(output), anyString());
        assertThat(TaskWorkerService.class.getMethod("advance", UUID.class).getAnnotation(Transactional.class))
                .isNull();
        assertThat(TaskWorkerClaimService.class.getMethod("claim", UUID.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(TaskWorkerClaimService.class.getMethod("commit",
                TaskWorkerClaimService.Claim.class, TaskModels.WorkerResult.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void taskLinkedTripAndBaseVersionMustBelongToOwner() {
        TaskRepository repository = mock(TaskRepository.class);
        TaskService service = new TaskService(repository, mock(PointsService.class),
                new SensitiveDataSanitizer(), new S11Metrics(new SimpleMeterRegistry()), new TaskProperties(),
                mock(AuditRepository.class));
        UUID userId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(repository.ownsTrip(tripId, userId)).thenReturn(false);

        assertThatThrownBy(() -> service.create(userId,
                new TaskModels.CreateTaskRequest("GENERATION", tripId, null, 1, Map.of("city", "北京")), "key"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("CONTEXT_NOT_FOUND");

        when(repository.ownsTrip(tripId, userId)).thenReturn(true);
        when(repository.ownsBaseVersion(versionId, tripId, userId)).thenReturn(false);
        assertThatThrownBy(() -> service.create(userId,
                new TaskModels.CreateTaskRequest("REVISION", tripId, versionId, 1, Map.of("city", "北京")), "key2"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("CONTEXT_NOT_FOUND");
        verify(repository, never()).insertTask(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void progressStreamRequiresVisibleTaskAndRejectsNegativeCursor() {
        TaskRepository repository = mock(TaskRepository.class);
        TaskService taskService = mock(TaskService.class);
        TaskEventStreamService stream = new TaskEventStreamService(repository, taskService,
                new TaskProperties(), new S11Metrics(new SimpleMeterRegistry()));
        UUID taskId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(taskService.requireVisible(userId, "USER", taskId))
                .thenReturn(task(taskId, userId, TaskStatus.PARSING, 3, 10));

        assertThatThrownBy(() -> stream.subscribe(userId, "USER", taskId, -1))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("INVALID_INPUT");
        verify(repository, never()).eventsAfter(any(), anyLong(), anyInt());
    }

    @Test
    void concurrentPollsSendEachEventOnlyOnceForOneSubscriber() throws Exception {
        TaskRepository repository = mock(TaskRepository.class);
        TaskService taskService = mock(TaskService.class);
        TaskProperties properties = new TaskProperties();
        RecordingEmitter emitter = new RecordingEmitter();
        TaskEventStreamService stream = new TaskEventStreamService(repository, taskService, properties,
                new S11Metrics(new SimpleMeterRegistry()), timeout -> emitter);
        UUID taskId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        TaskRepository.TaskRow task = task(taskId, userId, TaskStatus.PARSING, 3, 10);
        TaskModels.TaskEventView event = new TaskModels.TaskEventView(1, "TASK_STATUS_CHANGED",
                "PARSING", "parse", 1, "开始", List.of(), Instant.now());
        when(taskService.requireVisible(userId, "USER", taskId)).thenReturn(task);
        when(repository.findTask(taskId)).thenReturn(task);
        when(repository.eventsAfter(eq(taskId), eq(0L), eq(properties.getSseBatchSize())))
                .thenReturn(List.of(), List.of(event), List.of());

        stream.subscribe(userId, "USER", taskId, 0);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                start.await();
                stream.pollAll();
                return null;
            });
            Future<?> second = executor.submit(() -> {
                start.await();
                stream.pollAll();
                return null;
            });
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(emitter.sendCount()).isEqualTo(1);
    }

    @Test
    void reconnectedSubscriberStartsFromLastEventId() {
        TaskRepository repository = mock(TaskRepository.class);
        TaskService taskService = mock(TaskService.class);
        TaskProperties properties = new TaskProperties();
        TaskEventStreamService stream = new TaskEventStreamService(repository, taskService, properties,
                new S11Metrics(new SimpleMeterRegistry()), timeout -> new RecordingEmitter());
        UUID taskId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(taskService.requireVisible(userId, "USER", taskId))
                .thenReturn(task(taskId, userId, TaskStatus.PARSING, 3, 10));
        when(repository.findTask(taskId)).thenReturn(task(taskId, userId, TaskStatus.PARSING, 3, 10));
        when(repository.eventsAfter(eq(taskId), eq(7L), eq(properties.getSseBatchSize())))
                .thenReturn(List.of());

        stream.subscribe(userId, "USER", taskId, 7);

        verify(repository).eventsAfter(eq(taskId), eq(7L), eq(properties.getSseBatchSize()));
    }

    private static TaskRepository.TaskRow task(UUID taskId, UUID ownerId, TaskStatus status, long version,
            long reservedPoints) {
        Instant now = Instant.now();
        return new TaskRepository.TaskRow(taskId, ownerId, UUID.randomUUID(), "GENERATION", status, version,
                reservedPoints, now.plusSeconds(180), null, now, now);
    }

    private static TaskWorkerClaimService.Claim claim(String workerId, int attemptNo) {
        return new TaskWorkerClaimService.Claim(UUID.randomUUID(), UUID.randomUUID(), "parse", attemptNo,
                workerId, TaskStatus.PARSING, TaskStatus.COLLECTING);
    }

    private static TaskModels.WorkerResult result(String status, List<String> degradedFields) {
        return new TaskModels.WorkerResult(UUID.randomUUID(), status,
                "failed".equals(status) ? Map.of("error_code", "MOCK_FAILED") : Map.of("simulated", true),
                degradedFields);
    }

    private static final class RecordingEmitter extends SseEmitter {
        private final AtomicInteger sends = new AtomicInteger();

        @Override
        public void send(SseEmitter.SseEventBuilder builder) throws IOException {
            sends.incrementAndGet();
        }

        private int sendCount() {
            return sends.get();
        }
    }
}
