package com.agenttravel.backend.task;

import java.util.UUID;

import com.agenttravel.backend.common.RequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TaskWorkerService {

    private final TaskWorkerClaimService claims;
    private final TaskHeartbeatService heartbeats;
    private final AgentStepExecutor executor;
    private final String workerId;

    public TaskWorkerService(TaskWorkerClaimService claims, TaskHeartbeatService heartbeats,
            AgentStepExecutor executor, TaskProperties properties) {
        this.claims = claims;
        this.heartbeats = heartbeats;
        this.executor = executor;
        this.workerId = properties.getWorkerName() + "-" + UUID.randomUUID();
    }

    public boolean advance(UUID taskId) {
        TaskWorkerClaimService.ClaimResult result = claims.claim(taskId, workerId);
        if (!result.hasWork()) {
            return !result.busy();
        }
        TaskWorkerClaimService.Claim claim = result.claim();
        heartbeats.start(claim);
        try {
            TaskModels.WorkerResult output = executor.execute(taskId, claim.stepName(), claim.attemptNo());
            return claims.commit(claim, output, traceId());
        } catch (TaskWorkerClaimService.StaleWorkException exception) {
            return false;
        } catch (PythonAgentStepExecutor.AgentServiceException exception) {
            claims.failAttempt(claim, exception.code());
            return false;
        } catch (RuntimeException exception) {
            claims.failAttempt(claim, exception.getClass().getSimpleName());
            return false;
        } finally {
            heartbeats.stop(claim);
        }
    }

    public String workerId() {
        return workerId;
    }

    private String traceId() {
        RequestContext.Context context = RequestContext.get();
        return context == null || context.traceId() == null ? "worker-" + UUID.randomUUID() : context.traceId();
    }
}
