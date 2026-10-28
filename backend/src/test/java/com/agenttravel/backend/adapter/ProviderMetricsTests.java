package com.agenttravel.backend.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderMetricsTests {

    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");

    @Test
    void retryCircuitOpenHalfOpenAndProviderFailuresAreCounted() {
        MutableClock clock = new MutableClock(NOW);
        CountingMetrics metrics = new CountingMetrics();
        ProviderPolicy policy = policy(Duration.ofMillis(20), 1, 20, 100, 2);
        try (ProviderCallExecutor calls = new ProviderCallExecutor(policies(policy), clock, metrics)) {
            assertThatThrownBy(() -> calls.call(ProviderKind.QWEATHER, () -> {
                        Thread.sleep(100);
                        return "never";
                    }))
                    .isInstanceOf(ProviderException.class)
                    .extracting(exception -> ((ProviderException) exception).code())
                    .isEqualTo(ProviderErrorCode.TIMEOUT);

            assertThat(metrics.retries).hasValue(1);
            assertThat(metrics.providerFailures).hasValue(2);
            assertThat(metrics.circuitOpened).hasValue(1);

            clock.advance(Duration.ofSeconds(2));
            assertThat(calls.call(ProviderKind.QWEATHER, () -> "recovered")).isEqualTo("recovered");
            assertThat(metrics.circuitHalfOpened).hasValue(1);
        }
    }

    @Test
    void localRateAndQuotaHaveIndependentCounters() {
        CountingMetrics rateMetrics = new CountingMetrics();
        ProviderPolicy ratePolicy = policy(Duration.ofSeconds(1), 0, 1, 100, 3);
        try (ProviderCallExecutor calls =
                new ProviderCallExecutor(policies(ratePolicy), Clock.fixed(NOW, ZoneOffset.UTC), rateMetrics)) {
            assertThat(calls.call(ProviderKind.AMAP_ROUTE, () -> "ok")).isEqualTo("ok");
            assertThatThrownBy(() -> calls.call(ProviderKind.AMAP_ROUTE, () -> "blocked"))
                    .isInstanceOf(ProviderException.class)
                    .extracting(exception -> ((ProviderException) exception).code())
                    .isEqualTo(ProviderErrorCode.RATE_LIMITED);
            assertThat(rateMetrics.localRateLimited).hasValue(1);
            assertThat(rateMetrics.providerFailures).hasValue(0);
        }

        CountingMetrics quotaMetrics = new CountingMetrics();
        ProviderPolicy quotaPolicy = policy(Duration.ofSeconds(1), 0, 10, 1, 3);
        try (ProviderCallExecutor calls =
                new ProviderCallExecutor(
                        policies(quotaPolicy), Clock.fixed(NOW, ZoneOffset.UTC), quotaMetrics)) {
            assertThat(calls.call(ProviderKind.AMAP_POI, () -> "ok")).isEqualTo("ok");
            assertThatThrownBy(() -> calls.call(ProviderKind.AMAP_POI, () -> "blocked"))
                    .isInstanceOf(ProviderException.class)
                    .extracting(exception -> ((ProviderException) exception).code())
                    .isEqualTo(ProviderErrorCode.QUOTA_EXCEEDED);
            assertThat(quotaMetrics.quotaExceeded).hasValue(1);
            assertThat(quotaMetrics.providerFailures).hasValue(0);
        }
    }

    @Test
    void nonRetryableAuthenticationFailureCountsWithoutRetry() {
        CountingMetrics metrics = new CountingMetrics();
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 2, 20, 100, 3);
        try (ProviderCallExecutor calls =
                new ProviderCallExecutor(policies(policy), Clock.fixed(NOW, ZoneOffset.UTC), metrics)) {
            assertThatThrownBy(() -> calls.call(ProviderKind.QWEATHER, () -> {
                        throw new ProviderException(
                                ProviderKind.QWEATHER,
                                ProviderErrorCode.AUTHENTICATION_FAILED,
                                "鉴权失败");
                    }))
                    .isInstanceOf(ProviderException.class);
            assertThat(metrics.providerFailures).hasValue(1);
            assertThat(metrics.retries).hasValue(0);
        }
    }

    @Test
    void micrometerRegistryExposesProductionCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerProviderCallMetrics metrics = new MicrometerProviderCallMetrics(registry);
        MutableClock clock = new MutableClock(NOW);
        ProviderPolicy policy = policy(Duration.ofMillis(20), 1, 20, 100, 2);
        try (ProviderCallExecutor calls = new ProviderCallExecutor(policies(policy), clock, metrics)) {
            assertThatThrownBy(() -> calls.call(ProviderKind.QWEATHER, () -> {
                        Thread.sleep(100);
                        return "never";
                    }))
                    .isInstanceOf(ProviderException.class);
            clock.advance(Duration.ofSeconds(2));
            assertThat(calls.call(ProviderKind.QWEATHER, () -> "recovered")).isEqualTo("recovered");
        }

        assertThat(
                        registry.get("adapter.provider.retries")
                                .tag("provider", "qweather")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        registry.get("adapter.provider.circuit.opened")
                                .tag("provider", "qweather")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        registry.get("adapter.provider.circuit.half.opened")
                                .tag("provider", "qweather")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        registry.get("adapter.provider.failures")
                                .tag("provider", "qweather")
                                .tag("code", "timeout")
                                .counter()
                                .count())
                .isEqualTo(2);

        metrics.localRateLimited(ProviderKind.AMAP_ROUTE);
        metrics.quotaExceeded(ProviderKind.AMAP_POI);
        assertThat(
                        registry.get("adapter.provider.local.rate.limited")
                                .tag("provider", "amap_route")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        registry.get("adapter.provider.quota.exceeded")
                                .tag("provider", "amap_poi")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    private ProviderPolicy policy(
            Duration timeout,
            int retries,
            int rateLimit,
            int quota,
            int circuitThreshold) {
        return new ProviderPolicy(
                timeout,
                retries,
                Duration.ZERO,
                circuitThreshold,
                Duration.ofSeconds(1),
                rateLimit,
                quota,
                Duration.ofHours(1),
                Duration.ofMinutes(5),
                Duration.ofSeconds(10),
                "v1");
    }

    private Map<ProviderKind, ProviderPolicy> policies(ProviderPolicy policy) {
        Map<ProviderKind, ProviderPolicy> policies = new EnumMap<>(ProviderKind.class);
        for (ProviderKind kind : ProviderKind.values()) {
            policies.put(kind, policy);
        }
        return policies;
    }

    private static final class CountingMetrics implements ProviderCallMetrics {

        private final AtomicInteger retries = new AtomicInteger();
        private final AtomicInteger circuitOpened = new AtomicInteger();
        private final AtomicInteger circuitHalfOpened = new AtomicInteger();
        private final AtomicInteger localRateLimited = new AtomicInteger();
        private final AtomicInteger quotaExceeded = new AtomicInteger();
        private final AtomicInteger providerFailures = new AtomicInteger();

        @Override
        public void retry(ProviderKind provider) {
            retries.incrementAndGet();
        }

        @Override
        public void circuitOpened(ProviderKind provider) {
            circuitOpened.incrementAndGet();
        }

        @Override
        public void circuitHalfOpened(ProviderKind provider) {
            circuitHalfOpened.incrementAndGet();
        }

        @Override
        public void localRateLimited(ProviderKind provider) {
            localRateLimited.incrementAndGet();
        }

        @Override
        public void quotaExceeded(ProviderKind provider) {
            quotaExceeded.incrementAndGet();
        }

        @Override
        public void providerFailed(ProviderKind provider, ProviderErrorCode code) {
            providerFailures.incrementAndGet();
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
