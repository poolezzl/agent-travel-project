package com.agenttravel.backend.task;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import com.agenttravel.backend.points.PointsModels;
import com.agenttravel.backend.points.PointsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TaskService {

    private final TaskRepository repository;
    private final PointsService pointsService;
    private final SensitiveDataSanitizer sanitizer;
    private final S11Metrics metrics;
    private final TaskProperties properties;
    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final AuditRepository audit;

    public TaskService(TaskRepository repository, PointsService pointsService, SensitiveDataSanitizer sanitizer,
            S11Metrics metrics, TaskProperties properties, AuditRepository audit) {
        this.repository = repository;
        this.pointsService = pointsService;
        this.sanitizer = sanitizer;
        this.metrics = metrics;
        this.properties = properties;
        this.audit = audit;
    }

    @Transactional
    public TaskModels.TaskView create(UUID userId, TaskModels.CreateTaskRequest request, String idempotencyKey) {
        requireKey(idempotencyKey);
        validate(request);
        byte[] requestHash = canonicalJson.hash(request);
        TaskRepository.TaskRow existing = repository.findTaskByIdempotency(userId, idempotencyKey);
        if (existing != null) {
            return reused(existing, requestHash);
        }
        validateOwnership(userId, request);

        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();
        var trustedPayload = new LinkedHashMap<>(request.request());
        trustedPayload.put("plan_count", request.planCount());
        try {
            repository.insertTask(taskId, userId, request.tripId(), request.baseVersionId(), request.taskType(),
                    idempotencyKey, requestHash, canonicalJson.write(sanitizer.sanitize(trustedPayload)),
                    now.plusSeconds(properties.getOperationTimeoutSeconds()));
        } catch (DuplicateKeyException exception) {
            TaskRepository.TaskRow raced = repository.findTaskByIdempotency(userId, idempotencyKey);
            if (raced == null) {
                throw exception;
            }
            return reused(raced, requestHash);
        }

        event(taskId, "TASK_STATUS_CHANGED", new TaskModels.EventData(TaskStatus.CREATED.name(), null, null,
                "任务已创建", List.of()));
        PointsModels.QuoteResponse quote = pointsService.quote(userId,
                new PointsModels.QuoteRequest(request.planCount(), taskId), "task-quote:" + taskId);
        pointsService.reserve(userId, new PointsModels.ReserveRequest(quote.quoteReference(), taskId),
                "task-reserve:" + taskId);
        requireTransition(taskId, TaskStatus.BALANCE_RESERVED, TaskStatus.QUEUED, null);
        long queuedSequence = event(taskId, "TASK_STATUS_CHANGED", new TaskModels.EventData(TaskStatus.QUEUED.name(),
                null, null, "等待执行器处理", List.of()));
        UUID outboxId = UUID.randomUUID();
        repository.insertOutbox(taskId, "TASK_QUEUED",
                "task-queued:" + taskId,
                new TaskModels.QueueMessage(taskId, outboxId, "TASK_QUEUED", TaskStatus.QUEUED.name(),
                        queuedSequence), now);
        metrics.taskCreated();
        return view(repository.findTask(taskId), false);
    }

    public TaskModels.TaskView get(UUID userId, String role, UUID taskId) {
        return view(requireVisible(userId, role, taskId), false);
    }

    @Transactional
    public TaskModels.TaskView cancel(UUID userId, String role, UUID taskId,
            TaskModels.CancelTaskRequest request, String idempotencyKey) {
        requireKey(idempotencyKey);
        TaskRepository.TaskRow task = requireVisibleForUpdate(userId, role, taskId);
        if (task.status().terminal()) {
            return view(task, true);
        }
        if (task.stateVersion() != request.expectedVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", "任务状态已变化，请刷新后重试");
        }
        Instant now = Instant.now();
        boolean reserved = task.reservedPoints() > 0;
        if (reserved) {
            pointsService.refundForTask(task.ownerId(), new PointsModels.SettlementRequest(
                    "reserve:" + task.ownerId() + ":task-reserve:" + taskId), "task-cancel:" + taskId);
            // The reservation snapshot must not survive the refund: otherwise a later
            // settle attempt would charge against points that are already back.
            repository.clearReservedPoints(taskId);
        }
        if (repository.transitionStatus(taskId, task.status(), TaskStatus.CANCELLED, task.stateVersion(), now) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", "任务状态已变化，请刷新后重试");
        }
        long sequence = event(taskId, "TASK_STATUS_CHANGED", new TaskModels.EventData(TaskStatus.CANCELLED.name(), null,
                null, "任务已取消", List.of()));
        TaskStatus finalStatus = TaskStatus.CANCELLED;
        if (reserved) {
            requireTransition(taskId, TaskStatus.CANCELLED, TaskStatus.REFUNDED, null);
            sequence = event(taskId, "TASK_STATUS_CHANGED", new TaskModels.EventData(TaskStatus.REFUNDED.name(), null,
                    null, "积分已退回", List.of()));
            finalStatus = TaskStatus.REFUNDED;
        }
        UUID outboxId = UUID.randomUUID();
        repository.insertOutbox(taskId, "TASK_CANCELLED", "task-cancelled:" + taskId,
                new TaskModels.QueueMessage(taskId, outboxId, "TASK_CANCELLED", finalStatus.name(), sequence), now);
        audit.write(userId, "TASK_CANCEL", "PLANNING_TASK", taskId, "SUCCESS", null);
        metrics.taskCancelled();
        return view(repository.findTask(taskId), false);
    }

    TaskRepository.TaskRow requireVisible(UUID userId, String role, UUID taskId) {
        TaskRepository.TaskRow task = repository.findTask(taskId);
        if (task == null || (!isOwner(task, userId) && !"ADMIN".equals(role))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONTEXT_NOT_FOUND", "任务不存在");
        }
        return task;
    }

    private TaskRepository.TaskRow requireVisibleForUpdate(UUID userId, String role, UUID taskId) {
        TaskRepository.TaskRow task = repository.findTaskForUpdate(taskId);
        if (task == null || (!isOwner(task, userId) && !"ADMIN".equals(role))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONTEXT_NOT_FOUND", "任务不存在");
        }
        return task;
    }

    private boolean isOwner(TaskRepository.TaskRow task, UUID userId) {
        return task.ownerId().equals(userId);
    }

    private TaskModels.TaskView reused(TaskRepository.TaskRow task, byte[] requestHash) {
        if (!Arrays.equals(requestHash, repository.requestHash(task.id()))) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "幂等键已用于其他任务请求");
        }
        return view(task, true);
    }

    private void requireTransition(UUID taskId, TaskStatus from, TaskStatus to, Long expectedVersion) {
        TaskStateMachine.require(from, to);
        if (repository.transitionStatus(taskId, from, to, expectedVersion, Instant.now()) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", "任务状态已变化");
        }
    }

    private long event(UUID taskId, String eventType, TaskModels.EventData eventData) {
        return repository.appendEvent(taskId, eventType, eventData);
    }

    private void validate(TaskModels.CreateTaskRequest request) {
        if (!List.of("GENERATION", "REVISION").contains(request.taskType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "任务类型不受支持");
        }
        if ("REVISION".equals(request.taskType()) && request.baseVersionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "修订任务必须指定基准版本");
        }
        if (request.baseVersionId() != null && request.tripId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "基准版本必须同时指定行程");
        }
    }

    private void validateOwnership(UUID userId, TaskModels.CreateTaskRequest request) {
        if (request.tripId() != null && !repository.ownsTrip(request.tripId(), userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONTEXT_NOT_FOUND", "行程不存在");
        }
        if (request.baseVersionId() != null
                && !repository.ownsBaseVersion(request.baseVersionId(), request.tripId(), userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONTEXT_NOT_FOUND", "基准版本不存在");
        }
    }

    private void requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "幂等键不合法");
        }
    }

    private TaskModels.TaskView view(TaskRepository.TaskRow task, boolean reused) {
        if (task == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "任务状态读取失败");
        }
        return new TaskModels.TaskView(task.id(), task.ownerId(), task.quoteId(), task.taskType(), task.status(),
                task.stateVersion(), task.reservedPoints(), task.operationDeadlineAt(),
                task.confirmationDeadlineAt(), task.createdAt(), task.updatedAt(), reused);
    }
}
