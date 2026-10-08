package com.agenttravel.backend.adapter;

public enum ProviderErrorCode {
    TIMEOUT(true),
    RATE_LIMITED(true),
    QUOTA_EXCEEDED(false),
    AUTHENTICATION_FAILED(false),
    EMPTY_RESULT(false),
    INVALID_RESPONSE(false),
    DIRTY_DATA(false),
    INVALID_REQUEST(false),
    CIRCUIT_OPEN(true),
    UNAVAILABLE(true);

    private final boolean retryable;

    ProviderErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
