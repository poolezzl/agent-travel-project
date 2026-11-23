package com.agenttravel.backend.task;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.agenttravel.backend.common.AuditRepository;
import com.agenttravel.backend.points.PointsModels;
import com.agenttravel.backend.points.PointsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S22 confirmation-window close-out.
 *
 * <p>S19 persists {@code confirmation_deadline_at} when a plan needs user confirmation but
 * creates no version and settles no points. Without a sweeper the task would wait forever and
 * the reserved points would never return. This service closes the window exactly once:
 * {@code AWAITING_CONFIRMATION -> CANCELLED -> REFUNDED}, refunding the same reservation the
 * task reserved. Every decision is re-read under the task row lock, so a concurrent
 * confirmation, cancellation or duplicate sweep cannot refund twice.
 */
@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TaskConfirmationTimeoutService {

    static final String TIMEOUT_DEGRADED_FIELD = "confirmation_timeout";

    private final TaskRepository repository;
    private final PointsService pointsService;
    private final AuditRepository audit;
    private final S11Metrics metrics;

    public TaskConfirmationTimeoutService(TaskRepository repository, PointsService pointsService,
            AuditRepository audit, S11Metrics metrics) {
        this.repository = repository;
        this.pointsService = pointsService;
        this.audit = audit;
        this.metrics = metrics;
    }

    /** Expire every overdue confirmation in the current batch. */
    @Transactional
    public int expireOverdue(int batchSize) {
        Instant now = Instant.now();
        List<TaskRepository.TaskRow> candidates = repository.expiredConfirmations(now, batchSize);
        int expired = 0;
        for (TaskRepository.TaskRow candidate : candidates) {
            if (expireOne(candidate.id(), now)) {
                expired++;
            }
        }
        return expired;
    }

    /**
     * Close one confirmation window.
     *
     * @return true when this call performed the terminal transition.
     */
    @Transactional
    public boolean expireOne(UUID taskId, Instant now) {
        TaskRepository.TaskRow task = repository.findTaskForUpdate(taskId);
        if (task == null || task.status() != TaskStatus.AWAITING_CONFIRMATION) {
            return false;
        }
        Instant deadline = task.confirmationDeadlineAt();
        if (deadline == null || deadline.isAfter(now)) {
            return false;
        }
        if (repository.transitionStatus(taskId, TaskStatus.AWAITING_CONFIRMATION, TaskStatus.CANCELLED,
                task.stateVersion(), now) != 1) {
            return false;
        }
        long sequence = repository.appendEvent(taskId, "TASK_STATUS_CHANGED",
                new TaskModels.EventData(TaskStatus.CANCELLED.name(), null, null,
                        "确认超时，未创建任何行程版本", List.of(TIMEOUT_DEGRADED_FIELD)));
        boolean reserved = task.reservedPoints() > 0;
        if (reserved) {
            pointsService.refundForTask(task.ownerId(), new PointsModels.SettlementRequest(
                    "reserve:" + task.ownerId() + ":task-reserve:" + taskId), "task-confirmation-timeout:" + taskId);
            repository.clearReservedPoints(taskId);
            if (repository.transitionStatus(taskId, TaskStatus.CANCELLED, TaskStatus.REFUNDED, null, now) != 1) {
                throw new IllegalStateException("CONFIRMATION_TIMEOUT_REFUND_TRANSITION_FAILED");
            }
            sequence = repository.appendEvent(taskId, "TASK_STATUS_CHANGED",
                    new TaskModels.EventData(TaskStatus.REFUNDED.name(), null, null,
                            "确认超时，预扣积分已退回", List.of(TIMEOUT_DEGRADED_FIELD)));
        }
        TaskStatus terminal = reserved ? TaskStatus.REFUNDED : TaskStatus.CANCELLED;
        repository.insertOutbox(taskId, "TASK_CONFIRMATION_TIMEOUT", "task-confirmation-timeout:" + taskId,
                new TaskModels.QueueMessage(taskId, UUID.randomUUID(), "TASK_CONFIRMATION_TIMEOUT",
                        terminal.name(), sequence), now);
        audit.write(task.ownerId(), "TASK_CONFIRMATION_TIMEOUT", "PLANNING_TASK", taskId, "SUCCESS",
                TIMEOUT_DEGRADED_FIELD);
        metrics.taskCancelled();
        return true;
    }
}
