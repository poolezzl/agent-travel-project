package com.agenttravel.backend.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class AdapterTests {

    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void routeAndPoiUseParameterCompleteKeysAndCacheSource() {
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        try (ProviderCallExecutor calls = executor(policy)) {
            MockAmapAdapter adapter = new MockAmapAdapter("amap", policy, CLOCK, calls,
                    new InMemoryProviderCache<>(), new InMemoryProviderCache<>());
            RouteRequest request = new RouteRequest("GCJ02", new Coordinate(116.3, 39.9),
                    new Coordinate(116.4, 39.9), "walking", NOW);

            ProviderResponse<RouteData> first = adapter.route(request);
            ProviderResponse<RouteData> second = adapter.route(request);

            assertThat(first.cacheHit()).isFalse();
            assertThat(second.cacheHit()).isTrue();
            assertThat(first.source().providerAlias()).isEqualTo("amap");
            assertThat(first.source().estimated()).isTrue();

            ProviderResponse<java.util.List<PoiRecord>> poi = adapter.search(
                    new PoiSearchRequest("beijing", "故宫", "attraction", 1));
            assertThat(poi.value()).hasSize(1);
            assertThat(poi.value().get(0).poiId()).startsWith("mock-poi-");
        }
    }

    @Test
    void emptyAndDirtyResultsAreNegativeCachedButAuthenticationIsNot() {
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        InMemoryProviderCache<java.util.List<PoiRecord>> cache = new InMemoryProviderCache<>();
        try (ProviderCallExecutor calls = executor(policy)) {
            MockAmapAdapter adapter = new MockAmapAdapter("amap", policy, CLOCK, calls,
                    new InMemoryProviderCache<>(), cache);
            PoiSearchRequest empty = new PoiSearchRequest("beijing", "empty", "attraction", 1);

            assertThatThrownBy(() -> adapter.search(empty))
                    .isInstanceOf(ProviderException.class)
                    .extracting(exception -> ((ProviderException) exception).code())
                    .isEqualTo(ProviderErrorCode.EMPTY_RESULT);
            assertThat(cache.size()).isEqualTo(1);
            assertThatThrownBy(() -> adapter.search(empty))
                    .isInstanceOf(ProviderException.class)
                    .extracting(exception -> ((ProviderException) exception).code())
                    .isEqualTo(ProviderErrorCode.EMPTY_RESULT);

            cache.putNegative("auth", ProviderErrorCode.AUTHENTICATION_FAILED, NOW, Duration.ofMinutes(1));
            assertThat(cache.size()).isEqualTo(1);
        }
    }

    @Test
    void weatherNeverLabelsOutsideWindowAsForecast() {
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        try (ProviderCallExecutor calls = executor(policy)) {
            MockQWeatherAdapter adapter = new MockQWeatherAdapter("qweather", policy, 3, CLOCK, calls,
                    new InMemoryProviderCache<>());
            WeatherRequest inside = new WeatherRequest("beijing", NOW.atZone(ZoneOffset.UTC).toLocalDate(),
                    NOW.atZone(ZoneOffset.UTC).toLocalDate().plusDays(3), 3);
            ProviderResponse<java.util.List<WeatherRecord>> response = adapter.forecast(inside);

            assertThat(response.value()).hasSize(4);
            assertThat(response.value()).allMatch(record -> record.dataType().equals("forecast"));
            assertThat(response.value()).allMatch(record -> record.validUntil() != null);

            WeatherRequest outside = new WeatherRequest("beijing", inside.to().plusDays(1), inside.to().plusDays(2), 3);
            assertThatThrownBy(() -> adapter.forecast(outside))
                    .isInstanceOf(ProviderException.class)
                    .extracting(exception -> ((ProviderException) exception).code())
                    .isEqualTo(ProviderErrorCode.EMPTY_RESULT);
        }
    }

    @Test
    void dirtyDataIsRejectedBeforeItCanBecomeAResponse() {
        assertThatThrownBy(() -> new WeatherRecord(NOW.atZone(ZoneOffset.UTC).toLocalDate(), 30, 20,
                0.2, "晴", "forecast", "source", NOW, NOW.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PoiRecord("", "beijing", new Coordinate(116, 39), "GCJ02", "a", "故宫",
                NOW, new ProviderSource("amap", "poi-1", NOW, NOW.plusSeconds(10), FreshnessStatus.FRESH, true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelMockDoesNotCachePrivateInputAndEmptyOutputIsFailure() {
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        try (ProviderCallExecutor calls = executor(policy)) {
            MockModelAdapter adapter = new MockModelAdapter("llm-mock", calls, CLOCK);
            ProviderResponse<ModelResponse> response = adapter.complete(new ModelRequest("planner-mock", "仅测试格式"));

            assertThat(response.value().outputText()).contains("mock");
            assertThat(response.cacheHit()).isFalse();
            assertThat(response.source().freshnessStatus()).isEqualTo(FreshnessStatus.UNKNOWN);
            assertThatThrownBy(() -> adapter.complete(new ModelRequest("planner-mock", "empty")))
                    .isInstanceOf(ProviderException.class)
                    .extracting(exception -> ((ProviderException) exception).code())
                    .isEqualTo(ProviderErrorCode.EMPTY_RESULT);
        }
    }

    @Test
    void cacheEntryExpiresAtTtlBoundary() {
        InMemoryProviderCache<String> cache = new InMemoryProviderCache<>();
        ProviderSource source = new ProviderSource("amap", "route-1", NOW, NOW.plusSeconds(30),
                FreshnessStatus.FRESH, true);
        cache.put("key", "value", source, NOW, Duration.ofSeconds(1));

        assertThat(cache.get("key", NOW.plusMillis(999))).isPresent();
        assertThat(cache.get("key", NOW.plusSeconds(1))).isEmpty();
    }

    @Test
    void timeoutRetriesOnceThenOpensCircuit() {
        ProviderPolicy policy = policy(Duration.ofMillis(20), 1, 2, 100);
        try (ProviderCallExecutor calls = executor(policy)) {
            AtomicInteger attempts = new AtomicInteger();
            assertThatThrownBy(() -> calls.call(ProviderKind.QWEATHER, () -> {
                attempts.incrementAndGet();
                Thread.sleep(100);
                return "never";
            })).isInstanceOf(ProviderException.class)
                    .extracting(exception -> ((ProviderException) exception).code())
                    .isEqualTo(ProviderErrorCode.TIMEOUT);
            assertThat(attempts).hasValue(2);
            assertThatThrownBy(() -> calls.call(ProviderKind.QWEATHER, () -> "blocked"))
                    .isInstanceOf(ProviderException.class)
                    .extracting(exception -> ((ProviderException) exception).code())
                    .isEqualTo(ProviderErrorCode.CIRCUIT_OPEN);
        }
    }

    @Test
    void rateLimitAndQuotaAreSeparateAndDeterministic() {
        ProviderPolicy ratePolicy = policy(Duration.ofSeconds(1), 0, 1, 100);
        try (ProviderCallExecutor calls = executor(ratePolicy)) {
            assertThat(calls.call(ProviderKind.AMAP_ROUTE, () -> "ok")).isEqualTo("ok");
            assertThatThrownBy(() -> calls.call(ProviderKind.AMAP_ROUTE, () -> "blocked"))
                    .isInstanceOf(ProviderException.class)
                    .extracting(exception -> ((ProviderException) exception).code())
                    .isEqualTo(ProviderErrorCode.RATE_LIMITED);
        }

        ProviderPolicy quotaPolicy = policy(Duration.ofSeconds(1), 0, 10, 1);
        try (ProviderCallExecutor calls = executor(quotaPolicy)) {
            assertThat(calls.call(ProviderKind.AMAP_ROUTE, () -> "ok")).isEqualTo("ok");
            assertThatThrownBy(() -> calls.call(ProviderKind.AMAP_ROUTE, () -> "blocked"))
                    .isInstanceOf(ProviderException.class)
                    .extracting(exception -> ((ProviderException) exception).code())
                    .isEqualTo(ProviderErrorCode.QUOTA_EXCEEDED);
        }
    }

    private ProviderPolicy policy(Duration timeout, int retries, int rateLimit, int quota) {
        return new ProviderPolicy(timeout, retries, Duration.ZERO, 2, Duration.ofMinutes(1), rateLimit, quota,
                Duration.ofHours(1), Duration.ofMinutes(5), Duration.ofSeconds(10), "v1");
    }

    private ProviderCallExecutor executor(ProviderPolicy policy) {
        Map<ProviderKind, ProviderPolicy> policies = new EnumMap<>(ProviderKind.class);
        for (ProviderKind kind : ProviderKind.values()) {
            policies.put(kind, policy);
        }
        return new ProviderCallExecutor(policies, CLOCK);
    }
}
