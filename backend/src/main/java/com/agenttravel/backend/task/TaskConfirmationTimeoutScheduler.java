package com.agenttravel.backend.task;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Periodically closes expired confirmation windows (S22 drill: confirmation timeout). */
@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TaskConfirmationTimeoutScheduler {

    private final TaskConfirmationTimeoutService service;
    private final TaskProperties properties;

    public TaskConfirmationTimeoutScheduler(TaskConfirmationTimeoutService service, TaskProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.s11.confirmation-sweep-interval-ms:60000}")
    public void sweep() {
        if (!properties.isConfirmationSweepEnabled()) {
            return;
        }
        service.expireOverdue(properties.getConfirmationSweepBatchSize());
    }
}
