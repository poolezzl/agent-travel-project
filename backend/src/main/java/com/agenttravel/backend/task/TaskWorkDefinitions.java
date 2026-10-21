package com.agenttravel.backend.task;

import java.util.Map;

public final class TaskWorkDefinitions {

    private static final Map<TaskStatus, StepDefinition> STEPS = Map.of(
            TaskStatus.QUEUED, new StepDefinition(TaskStatus.PARSING, TaskStatus.COLLECTING, "parse"),
            TaskStatus.PARSING, new StepDefinition(TaskStatus.PARSING, TaskStatus.COLLECTING, "parse"),
            TaskStatus.COLLECTING, new StepDefinition(TaskStatus.COLLECTING, TaskStatus.SCHEDULING, "collect"),
            TaskStatus.SCHEDULING, new StepDefinition(TaskStatus.SCHEDULING, TaskStatus.ROUTING, "schedule"),
            TaskStatus.ROUTING, new StepDefinition(TaskStatus.ROUTING, TaskStatus.ASSEMBLING, "route"),
            TaskStatus.ASSEMBLING, new StepDefinition(TaskStatus.ASSEMBLING, null, "assemble"));

    private TaskWorkDefinitions() {
    }

    public static StepDefinition forStatus(TaskStatus status) {
        return STEPS.get(status);
    }

    public record StepDefinition(TaskStatus toStatus, TaskStatus nextStatus, String stepName) {
    }
}
