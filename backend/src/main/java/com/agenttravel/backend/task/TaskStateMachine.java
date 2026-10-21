package com.agenttravel.backend.task;

import static com.agenttravel.backend.task.TaskStatus.ASSEMBLING;
import static com.agenttravel.backend.task.TaskStatus.AWAITING_CONFIRMATION;
import static com.agenttravel.backend.task.TaskStatus.BALANCE_RESERVED;
import static com.agenttravel.backend.task.TaskStatus.CANCELLED;
import static com.agenttravel.backend.task.TaskStatus.COLLECTING;
import static com.agenttravel.backend.task.TaskStatus.CREATED;
import static com.agenttravel.backend.task.TaskStatus.FAILED;
import static com.agenttravel.backend.task.TaskStatus.PARTIAL;
import static com.agenttravel.backend.task.TaskStatus.PARSING;
import static com.agenttravel.backend.task.TaskStatus.QUEUED;
import static com.agenttravel.backend.task.TaskStatus.REFUNDED;
import static com.agenttravel.backend.task.TaskStatus.ROUTING;
import static com.agenttravel.backend.task.TaskStatus.SCHEDULING;
import static com.agenttravel.backend.task.TaskStatus.SUCCEEDED;

import java.util.Map;
import java.util.Set;

import com.agenttravel.backend.common.ApiException;
import org.springframework.http.HttpStatus;

public final class TaskStateMachine {

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = Map.ofEntries(
            Map.entry(CREATED, Set.of(BALANCE_RESERVED, AWAITING_CONFIRMATION, CANCELLED, FAILED)),
            Map.entry(BALANCE_RESERVED, Set.of(QUEUED, CANCELLED, FAILED)),
            Map.entry(QUEUED, Set.of(PARSING, CANCELLED, FAILED)),
            Map.entry(PARSING, Set.of(COLLECTING, AWAITING_CONFIRMATION, CANCELLED, FAILED)),
            Map.entry(COLLECTING, Set.of(SCHEDULING, CANCELLED, FAILED)),
            Map.entry(SCHEDULING, Set.of(ROUTING, AWAITING_CONFIRMATION, CANCELLED, FAILED)),
            Map.entry(ROUTING, Set.of(ASSEMBLING, AWAITING_CONFIRMATION, CANCELLED, FAILED)),
            Map.entry(ASSEMBLING, Set.of(SUCCEEDED, PARTIAL, AWAITING_CONFIRMATION, CANCELLED, FAILED)),
            Map.entry(AWAITING_CONFIRMATION, Set.of(BALANCE_RESERVED, QUEUED, CANCELLED, FAILED)),
            Map.entry(FAILED, Set.of(REFUNDED)),
            Map.entry(CANCELLED, Set.of(REFUNDED)),
            Map.entry(SUCCEEDED, Set.of()),
            Map.entry(PARTIAL, Set.of()),
            Map.entry(REFUNDED, Set.of()));

    private TaskStateMachine() {
    }

    public static boolean canTransition(TaskStatus from, TaskStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void require(TaskStatus from, TaskStatus to) {
        if (!canTransition(from, to)) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT",
                    "任务状态不允许从 " + from + " 变更为 " + to);
        }
    }
}
