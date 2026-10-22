package com.agenttravel.backend.adapter;

public enum ProviderErrorCode {
    TIMEOUT(true),
    RATE_LIMITED(true),
    PROVIDER_RATE_LIMITED(true),
    QUOTA_EXCEEDED(false),
    AUTHENTICATION_FAILED(false),
    EMPTY_RESULT(false),
    FORECAST_WINDOW_OUTSIDE(false),
    STALE_DATA(false),
    SOURCE_FRESHNESS_UNKNOWN(false),
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
