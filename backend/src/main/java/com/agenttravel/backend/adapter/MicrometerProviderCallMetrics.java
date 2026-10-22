package com.agenttravel.backend.adapter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

public final class MicrometerProviderCallMetrics implements ProviderCallMetrics {

    private final MeterRegistry registry;

    public MicrometerProviderCallMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void retry(ProviderKind provider) {
        counter("adapter.provider.retries", provider).increment();
    }

    @Override
    public void circuitOpened(ProviderKind provider) {
        counter("adapter.provider.circuit.opened", provider).increment();
    }

    @Override
    public void circuitHalfOpened(ProviderKind provider) {
        counter("adapter.provider.circuit.half.opened", provider).increment();
    }

    @Override
    public void localRateLimited(ProviderKind provider) {
        counter("adapter.provider.local.rate.limited", provider).increment();
    }

    @Override
    public void quotaExceeded(ProviderKind provider) {
        counter("adapter.provider.quota.exceeded", provider).increment();
    }

    @Override
    public void providerFailed(ProviderKind provider, ProviderErrorCode code) {
        registry.counter(
                        "adapter.provider.failures",
                        "provider",
                        providerTag(provider),
                        "code",
                        code.name().toLowerCase(Locale.ROOT))
                .increment();
    }

    private Counter counter(String name, ProviderKind provider) {
        return registry.counter(name, "provider", providerTag(provider));
    }

    private String providerTag(ProviderKind provider) {
        return provider.name().toLowerCase(Locale.ROOT);
    }
}
