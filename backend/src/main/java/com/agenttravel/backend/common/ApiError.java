package com.agenttravel.backend.common;

import java.time.Instant;

public record ApiError(String type, String title, int status, String detail, String code,
        String traceId, Instant timestamp) {
}
