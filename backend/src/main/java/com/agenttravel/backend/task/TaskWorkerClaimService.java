package com.agenttravel.backend.task;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.agenttravel.backend.common.AuditRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TaskWorkerClaimService {

    private final TaskRepository repository;
    private final TaskTerminationService termination;
    private final TaskAssemblyService assembly;
    private final AuditRepository audit;
    private final TaskProperties properties;
    private final S11Metrics metrics;
    private final CanonicalJson canonicalJson = new CanonicalJson();

    @org.springframework.beans.factory.annotation.Autowired
    public TaskWorkerClaimService(TaskRepository repository, TaskTerminationService termination,
            TaskAssemblyService assembly, AuditRepository audit, TaskProperties properties, S11Metrics metrics) {
        this.repository = repository;
        this.termination = termination;
        this.assembly = assembly;
        this.audit = audit;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** Test/back-compat constructor; the assemble step requires the real assembly service. */
    public TaskWorkerClaimService(TaskRepository repository, TaskTerminationService termination,
            AuditRepository audit, TaskProperties properties, S11Metrics metrics) {
        this(repository, termination, null, audit, properties, metrics);
    }

    @Transactional
    public ClaimResult claim(UUID taskId, String workerId) {
        TaskRepository.TaskRow task = repository.findTaskForUpdate(taskId);
        if (task == null || task.status().terminal()) {
            return ClaimResult.terminalResult();
        }
        if (Instant.now().isAfter(task.operationDeadlineAt())) {
            termination.failAndRefund(taskId, "OPERATION_DEADLINE_EXCEEDED");
            return ClaimResult.terminalResult();
        }
        TaskWorkDefinitions.StepDefinition definition = TaskWorkDefinitions.forStatus(task.status());
        if (definition == null) {
            return ClaimResult.waitingResult();
        }
        if (task.status() == TaskStatus.QUEUED) {
            if (repository.transitionStatus(taskId, TaskStatus.QUEUED, TaskStatus.PARSING, task.stateVersion(),
                    Instant.now()) != 1) {
                return ClaimResult.busyResult();
            }
            append(taskId, TaskStatus.PARSING, "parse", 1, "开始解析任务");
        }

        TaskRepository.StepRow step = repository.latestStep(taskId, definition.stepName());
        if (step == null) {
            return work(taskId, insert(taskId, definition.stepName(), 1, workerId), definition);
        }
        if ("RUNNING".equals(step.status())) {
            if (step.leaseExpiresAt() != null && step.leaseExpiresAt().isAfter(Instant.now())) {
                return workerId.equals(step.leaseOwner()) ? work(taskId, step, definition)
                        : ClaimResult.busyResult();
            }
            repository.expireStep(step.id(), Instant.now());
            metrics.leaseRecovered();
            return ClaimResult.advancedResult();
        }
        if ("FAILED".equals(step.status())) {
            if (step.attemptNo() >= properties.getMaxStepAttempts()) {
                termination.failAndRefund(taskId, "STEP_RETRY_EXHAUSTED");
                return ClaimResult.terminalResult();
            }
            return work(taskId, insert(taskId, definition.stepName(), step.attemptNo() + 1, workerId),
                    definition);
        }
        // PARTIAL/DEGRADED still represent a completed, usable step result and must advance the
        // state machine; otherwise any degraded sub-agent would stall the whole task.
        if (Set.of("SUCCEEDED", "PARTIAL", "DEGRADED").contains(step.status())) {
            if (!repository.outputFresh(step.id(), Instant.now())) {
                return work(taskId, insert(taskId, definition.stepName(), step.attemptNo() + 1, workerId),
                        definition);
            }
            if (definition.nextStatus() != null
                    && repository.transitionStatus(taskId, definition.toStatus(), definition.nextStatus(), null,
                            Instant.now()) == 1) {
                append(taskId, definition.nextStatus(), definition.stepName(), step.attemptNo(), "步骤已完成");
            }
            return ClaimResult.advancedResult();
        }
        return ClaimResult.waitingResult();
    }

    @Transactional
    public boolean commit(Claim claim, TaskModels.WorkerResult result, String traceId) {
        TaskRepository.TaskRow task = repository.findTaskForUpdate(claim.taskId());
        if (task == null || task.status().terminal() || task.status() != claim.workStatus()) {
            stale(claim.taskId());
            return false;
        }
        if (repository.heartbeat(claim.stepId(), claim.workerId(), claim.attemptNo(), Instant.now(),
                Instant.now().plus(properties.leaseDuration())) != 1) {
            stale(claim.taskId());
            return false;
        }
        UUID resultId;
        try {
            resultId = repository.insertAgentResult(claim.taskId(), claim.stepId(), claim.attemptNo(),
                    result.agentId() == null ? "mock-agent" : result.agentId(),
                    "mock:" + claim.taskId() + ":" + claim.stepName() + ":" + claim.attemptNo(),
                    "agent:" + claim.taskId() + ":" + claim.stepName() + ":" + claim.attemptNo(),
                    canonicalJson.hash(claim.taskId() + ":" + claim.stepName() + ":" + claim.attemptNo()),
                    traceId, result.status(), result.result(), result.degradedFields(), Instant.now());
        } catch (DuplicateKeyException duplicate) {
            resultId = repository.findAgentResultId(claim.taskId(), claim.stepId(), claim.attemptNo());
        }
        String stepStatus = switch (result.status()) {
            case "ok" -> "SUCCEEDED";
            case "partial" -> "PARTIAL";
            case "degraded" -> "DEGRADED";
            case "failed" -> "FAILED";
            default -> throw new IllegalArgumentException("未知执行结果状态");
        };
        if ("FAILED".equals(stepStatus)) {
            if (repository.failStepWithResult(claim.stepId(), claim.workerId(), claim.attemptNo(), resultId,
                    Instant.now()) != 1) {
                stale(claim.taskId());
                throw new StaleWorkException();
            }
            audit.write(null, "TASK_STEP_FAILED", "TASK_STEP", claim.stepId(), "FAILURE",
                    "AGENT_RESULT_FAILED");
            append(claim.taskId(), claim.workStatus(), claim.stepName(), claim.attemptNo(), "执行器返回失败",
                    result.degradedFields());
            if (claim.attemptNo() >= properties.getMaxStepAttempts()) {
                termination.failAndRefund(claim.taskId(), "STEP_RETRY_EXHAUSTED");
            }
            return false;
        }
        if (repository.completeStep(claim.stepId(), claim.workerId(), claim.attemptNo(), stepStatus,
                resultId, Instant.now()) != 1) {
            stale(claim.taskId());
            throw new StaleWorkException();
        }
        append(claim.taskId(), claim.workStatus(), claim.stepName(), claim.attemptNo(),
                "步骤已完成，结果=" + stepStatus, result.degradedFields());
        if (claim.nextStatus() == null) {
            if (!assembly.finalizeAssembly(claim, result)) {
                termination.failAndRefund(claim.taskId(), "ASSEMBLY_FAILED");
                return false;
            }
            return true;
        }
        if (repository.transitionStatus(claim.taskId(), claim.workStatus(), claim.nextStatus(), task.stateVersion(),
                Instant.now()) != 1) {
            throw new StaleWorkException();
        }
        append(claim.taskId(), claim.nextStatus(), claim.stepName(), claim.attemptNo(), "步骤已完成",
                result.degradedFields());
        return true;
    }

    @Transactional
    public boolean heartbeat(Claim claim) {
        return repository.heartbeat(claim.stepId(), claim.workerId(), claim.attemptNo(), Instant.now(),
                Instant.now().plus(properties.leaseDuration())) == 1;
    }

    @Transactional
    public void failAttempt(Claim claim, String code) {
        TaskRepository.TaskRow task = repository.findTaskForUpdate(claim.taskId());
        if (task == null || task.status().terminal()) {
            return;
        }
        if (repository.failStep(claim.stepId(), claim.workerId(), claim.attemptNo(), Instant.now()) != 1) {
            stale(claim.taskId());
            return;
        }
        audit.write(null, "TASK_STEP_FAILED", "TASK_STEP", claim.stepId(), "FAILURE", code);
        if (claim.attemptNo() >= properties.getMaxStepAttempts()) {
            termination.failAndRefund(claim.taskId(), "STEP_RETRY_EXHAUSTED");
        }
    }

    private TaskRepository.StepRow insert(UUID taskId, String stepName, int attemptNo, String workerId) {
        return repository.insertRunningStep(taskId, stepName, attemptNo, workerId, Instant.now(),
                Instant.now().plus(properties.leaseDuration()));
    }

    private ClaimResult work(UUID taskId, TaskRepository.StepRow step,
            TaskWorkDefinitions.StepDefinition definition) {
        return ClaimResult.workResult(new Claim(taskId, step.id(), step.stepName(), step.attemptNo(),
                step.leaseOwner(), definition.toStatus(), definition.nextStatus()));
    }

    private void append(UUID taskId, TaskStatus status, String stepName, Integer attemptNo, String message) {
        append(taskId, status, stepName, attemptNo, message, List.of());
    }

    private void append(UUID taskId, TaskStatus status, String stepName, Integer attemptNo, String message,
            List<String> degradedFields) {
        repository.appendEvent(taskId, "TASK_STATUS_CHANGED",
                new TaskModels.EventData(status.name(), stepName, attemptNo, message, degradedFields));
    }

    private void stale(UUID taskId) {
        audit.write(null, "WORKER_RESULT_STALE", "PLANNING_TASK", taskId, "DENIED", "WORKER_RESULT_STALE");
        metrics.staleResult();
    }

    public record Claim(UUID taskId, UUID stepId, String stepName, int attemptNo, String workerId,
            TaskStatus workStatus, TaskStatus nextStatus) {
    }

    public record ClaimResult(Claim claim, boolean terminal, boolean busy) {

        public static ClaimResult workResult(Claim claim) {
            return new ClaimResult(claim, false, false);
        }

        public static ClaimResult advancedResult() {
            return new ClaimResult(null, false, false);
        }

        public static ClaimResult terminalResult() {
            return new ClaimResult(null, true, false);
        }

        public static ClaimResult busyResult() {
            return new ClaimResult(null, false, true);
        }

        public static ClaimResult waitingResult() {
            return new ClaimResult(null, false, false);
        }

        public boolean hasWork() {
            return claim != null;
        }
    }

    public static class StaleWorkException extends RuntimeException {
    }
}
