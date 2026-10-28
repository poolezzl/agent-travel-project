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

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TaskTerminationService {

    private final TaskRepository repository;
    private final PointsService pointsService;
    private final AuditRepository audit;

    public TaskTerminationService(TaskRepository repository, PointsService pointsService, AuditRepository audit) {
        this.repository = repository;
        this.pointsService = pointsService;
        this.audit = audit;
    }

    @Transactional
    public boolean failAndRefund(UUID taskId, String code) {
        TaskRepository.TaskRow task = repository.findTaskForUpdate(taskId);
        if (task == null || task.status().terminal()) {
            return false;
        }
        if (repository.transitionStatus(taskId, task.status(), TaskStatus.FAILED, task.stateVersion(),
                Instant.now()) != 1) {
            return false;
        }
        append(taskId, TaskStatus.FAILED, "任务执行失败");
        if (task.reservedPoints() > 0) {
            pointsService.refund(task.ownerId(), new PointsModels.SettlementRequest(
                    "reserve:" + task.ownerId() + ":task-reserve:" + taskId), "task-fail:" + taskId);
            if (repository.transitionStatus(taskId, TaskStatus.FAILED, TaskStatus.REFUNDED, null,
                    Instant.now()) == 1) {
                append(taskId, TaskStatus.REFUNDED, "任务失败，积分已退回");
            }
        }
        audit.write(null, "TASK_TERMINATED", "PLANNING_TASK", taskId, "FAILURE", code);
        return true;
    }

    private void append(UUID taskId, TaskStatus status, String message) {
        repository.appendEvent(taskId, "TASK_STATUS_CHANGED",
                new TaskModels.EventData(status.name(), null, null, message, List.of()));
    }
}
