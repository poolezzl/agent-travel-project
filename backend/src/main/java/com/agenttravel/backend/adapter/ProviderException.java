package com.agenttravel.backend.adapter;

public class ProviderException extends RuntimeException {

    private final ProviderKind provider;
    private final ProviderErrorCode code;

    public ProviderException(ProviderKind provider, ProviderErrorCode code, String message) {
        super(message);
        this.provider = provider;
        this.code = code;
    }

    public ProviderException(ProviderKind provider, ProviderErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.code = code;
    }

    public ProviderKind provider() {
        return provider;
    }

    public ProviderErrorCode code() {
        return code;
    }

    public boolean retryable() {
        return code.retryable();
    }
}
