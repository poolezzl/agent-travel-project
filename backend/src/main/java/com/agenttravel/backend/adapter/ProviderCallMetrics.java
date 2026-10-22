package com.agenttravel.backend.adapter;

public interface ProviderCallMetrics {

    void retry(ProviderKind provider);

    void circuitOpened(ProviderKind provider);

    void circuitHalfOpened(ProviderKind provider);

    void localRateLimited(ProviderKind provider);

    void quotaExceeded(ProviderKind provider);

    void providerFailed(ProviderKind provider, ProviderErrorCode code);

    static ProviderCallMetrics noop() {
        return new ProviderCallMetrics() {
            @Override
            public void retry(ProviderKind provider) {
            }

            @Override
            public void circuitOpened(ProviderKind provider) {
            }

            @Override
            public void circuitHalfOpened(ProviderKind provider) {
            }

            @Override
            public void localRateLimited(ProviderKind provider) {
            }

            @Override
            public void quotaExceeded(ProviderKind provider) {
            }

            @Override
            public void providerFailed(ProviderKind provider, ProviderErrorCode code) {
            }
        };
    }
}
