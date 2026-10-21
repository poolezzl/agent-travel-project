package com.agenttravel.backend.task;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TaskHeartbeatService {

    private final TaskWorkerClaimService claimService;
    private final TaskProperties properties;
    private final Map<UUID, TaskWorkerClaimService.Claim> active = new ConcurrentHashMap<>();

    public TaskHeartbeatService(TaskWorkerClaimService claimService, TaskProperties properties) {
        this.claimService = claimService;
        this.properties = properties;
    }

    public void start(TaskWorkerClaimService.Claim claim) {
        active.put(claim.taskId(), claim);
    }

    public void stop(TaskWorkerClaimService.Claim claim) {
        active.remove(claim.taskId(), claim);
    }

    @Scheduled(fixedDelayString = "${app.s11.heartbeat-interval-ms:10000}")
    public void heartbeatAll() {
        if (!properties.isHeartbeatSchedulerEnabled()) {
            return;
        }
        active.forEach((taskId, claim) -> {
            if (!claimService.heartbeat(claim)) {
                active.remove(taskId, claim);
            }
        });
    }

    int activeCount() {
        return active.size();
    }
}
