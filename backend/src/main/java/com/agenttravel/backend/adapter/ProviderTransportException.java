package com.agenttravel.backend.adapter;

public final class ProviderTransportException extends RuntimeException {

    private final ProviderErrorCode code;

    public ProviderTransportException(ProviderErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ProviderErrorCode code() {
        return code;
    }
}
