package com.agenttravel.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import com.agenttravel.backend.points.PointsModels;
import com.agenttravel.backend.points.PointsRepository;
import com.agenttravel.backend.points.PointsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * S22 robustness drills: confirmation timeout, duplicate/out-of-order queue messages,
 * single settlement of a revision and task-only refunds of task reservations.
 */
class S22RobustnessUnitTests {

    @Test
    void confirmationTimeoutRefundsReservedPointsExactlyOnce() {
        TaskRepository repository = mock(TaskRepository.class);
        PointsService points = mock(PointsService.class);
        TaskConfirmationTimeoutService service = new TaskConfirmationTimeoutService(repository, points,
                mock(AuditRepository.class), new S11Metrics(new SimpleMeterRegistry()));
        UUID owner = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();
        when(repository.findTaskForUpdate(taskId)).thenReturn(awaitingConfirmation(taskId, owner, now));
        when(repository.transitionStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(points.refundForTask(eq(owner), any(), anyString()))
                .thenReturn(new PointsModels.PointsResponse("refund", 10, 100, false));

        assertThat(service.expireOne(taskId, now)).isTrue();

        var order = inOrder(repository, points);
        order.verify(repository).transitionStatus(eq(taskId), eq(TaskStatus.AWAITING_CONFIRMATION),
                eq(TaskStatus.CANCELLED), eq(5L), any());
        order.verify(points).refundForTask(eq(owner), any(), eq("task-confirmation-timeout:" + taskId));
        order.verify(repository).clearReservedPoints(taskId);
        order.verify(repository).transitionStatus(eq(taskId), eq(TaskStatus.CANCELLED),
                eq(TaskStatus.REFUNDED), any(), any());
        verify(repository).insertOutbox(eq(taskId), eq("TASK_CONFIRMATION_TIMEOUT"), anyString(), any(), any());

        // A second sweep sees a terminal task and must not refund again.
        when(repository.findTaskForUpdate(taskId)).thenReturn(task(taskId, owner, TaskStatus.REFUNDED, 7, 0, null));
        assertThat(service.expireOne(taskId, now)).isFalse();
        verify(points, times(1)).refundForTask(any(), any(), anyString());
    }

    @Test
    void confirmationInsideItsWindowIsNotExpired() {
        TaskRepository repository = mock(TaskRepository.class);
        PointsService points = mock(PointsService.class);
        TaskConfirmationTimeoutService service = new TaskConfirmationTimeoutService(repository, points,
                mock(AuditRepository.class), new S11Metrics(new SimpleMeterRegistry()));
        UUID taskId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Instant now = Instant.now();
        when(repository.findTaskForUpdate(taskId)).thenReturn(task(taskId, owner,
                TaskStatus.AWAITING_CONFIRMATION, 5, 10, now.plusSeconds(3600)));

        assertThat(service.expireOne(taskId, now)).isFalse();
        verify(points, never()).refundForTask(any(), any(), anyString());
        verify(repository, never()).transitionStatus(any(), any(), any(), any(), any());
    }

    @Test
    void consumerDropsDuplicateAndOutOfOrderQueueMessages() {
        TaskRepository repository = mock(TaskRepository.class);
        TaskQueue queue = mock(TaskQueue.class);
        TaskWorkerService worker = mock(TaskWorkerService.class);
        TaskProperties properties = new TaskProperties();
        TaskConsumerScheduler scheduler = new TaskConsumerScheduler(repository, queue, worker, properties);
        UUID taskId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(repository.currentQueueSequence(taskId)).thenReturn(5L);
        when(repository.activeTasks(anyInt()))
                .thenReturn(List.of(task(taskId, owner, TaskStatus.QUEUED, 3, 10, null)));
        when(queue.claim(eq(taskId), anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of(
                        new TaskModels.QueueRecord("1-0",
                                new TaskModels.QueueMessage(taskId, UUID.randomUUID(), "TASK_QUEUED",
                                        TaskStatus.QUEUED.name(), 4L)),
                        new TaskModels.QueueRecord("2-0",
                                new TaskModels.QueueMessage(taskId, UUID.randomUUID(), "TASK_QUEUED",
                                        TaskStatus.QUEUED.name(), 6L))));
        when(worker.workerId()).thenReturn("s22-worker");
        when(worker.advance(taskId)).thenReturn(true);

        scheduler.consume();

        verify(worker, times(1)).advance(taskId);
        verify(repository).markQueueSequence(taskId, 6L);
        verify(repository, never()).markQueueSequence(eq(taskId), eq(4L));
        verify(queue).acknowledge(eq(taskId), anyString(), eq("1-0"));
        verify(queue).acknowledge(eq(taskId), anyString(), eq("2-0"));
    }

    @Test
    void ownerCannotRefundATaskReservationDirectly() {
        PointsRepository repository = mock(PointsRepository.class);
        PointsService service = new PointsService(repository, mock(AuditRepository.class));
        UUID owner = UUID.randomUUID();
        String reservation = "reserve:" + owner + ":task-reserve:" + UUID.randomUUID();
        when(repository.ledgerBelongsToTask(reservation)).thenReturn(true);

        assertThatThrownBy(() -> service.refund(owner, new PointsModels.SettlementRequest(reservation),
                "owner-refund-key"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("CONCURRENCY_CONFLICT");
        verify(repository, never()).lockAccount(any());
        verify(repository, never()).updateBalance(any(), anyLong());
    }

    @Test
    void revisionSettlementChargesOnlyOnceForTheSameFingerprint() {
        PointsRepository repository = mock(PointsRepository.class);
        PointsService service = new PointsService(repository, mock(AuditRepository.class));
        UUID user = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        String reserveReference = "revision:" + user + ":fingerprint:reserve";
        when(repository.lockAccount(user)).thenReturn(new PointsRepository.AccountRow(accountId, 10));
        when(repository.findLedger(reserveReference)).thenReturn(null);
        when(repository.insertLedgerIfAbsent(eq(accountId), eq(user), any(), any(), eq("RESERVE"), eq(-2L), eq(10L),
                eq(8L), eq(reserveReference))).thenReturn(1);
        AtomicInteger published = new AtomicInteger();

        Optional<String> first = service.settleRevision(user, tripId, "fingerprint", () -> {
            published.incrementAndGet();
            return "version-1";
        });

        assertThat(first).contains("version-1");
        assertThat(published.get()).isEqualTo(1);
        verify(repository, times(1)).updateBalance(accountId, 8L);

        when(repository.findLedger(reserveReference))
                .thenReturn(new PointsRepository.LedgerRow(reserveReference, -2, 8, user));
        Optional<String> replay = service.settleRevision(user, tripId, "fingerprint", () -> {
            published.incrementAndGet();
            return "version-2";
        });

        assertThat(replay).isEmpty();
        assertThat(published.get()).isEqualTo(1);
        verify(repository, times(1)).updateBalance(accountId, 8L);
    }

    private static TaskRepository.TaskRow awaitingConfirmation(UUID taskId, UUID ownerId, Instant now) {
        return task(taskId, ownerId, TaskStatus.AWAITING_CONFIRMATION, 5, 10, now.minusSeconds(1));
    }

    private static TaskRepository.TaskRow task(UUID taskId, UUID ownerId, TaskStatus status, long version,
            long reservedPoints, Instant confirmationDeadlineAt) {
        Instant now = Instant.now();
        return new TaskRepository.TaskRow(taskId, ownerId, UUID.randomUUID(), "GENERATION", status, version,
                reservedPoints, now.plusSeconds(180), confirmationDeadlineAt, now, now);
    }
}
