package com.agenttravel.backend.task;

import java.util.UUID;

/** Executes one planning task step; implementations may be local mocks or the Python DAG. */
public interface AgentStepExecutor {

    TaskModels.WorkerResult execute(UUID taskId, String stepName, int attemptNo);

    default String agentId() {
        return "mock-agent";
    }
}
