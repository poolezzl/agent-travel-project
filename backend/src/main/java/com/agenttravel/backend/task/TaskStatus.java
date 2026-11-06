package com.agenttravel.backend.task;

import java.util.Set;

public enum TaskStatus {
    CREATED,
    BALANCE_RESERVED,
    QUEUED,
    PARSING,
    COLLECTING,
    SCHEDULING,
    ROUTING,
    ASSEMBLING,
    AWAITING_CONFIRMATION,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED,
    REFUNDED;

    private static final Set<TaskStatus> TERMINAL = Set.of(SUCCEEDED, PARTIAL, FAILED, CANCELLED, REFUNDED);

    public boolean terminal() {
        return TERMINAL.contains(this);
    }
}
