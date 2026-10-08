package com.agenttravel.backend.common;

import java.util.UUID;

public final class RequestContext {

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(String requestId, String traceId, UUID userId, String role) {
        CURRENT.set(new Context(requestId, traceId, userId, role));
    }

    public static Context get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Context(String requestId, String traceId, UUID userId, String role) {
    }
}
