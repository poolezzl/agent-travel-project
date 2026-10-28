package com.agenttravel.backend.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    void cacheKeysAreVersionedHashedAndResistDelimiterCollisions() {
        RouteRequest first = new RouteRequest("GCJ02", new Coordinate(116.3, 39.9),
                new Coordinate(116.4, 39.9), "walk|ing", NOW);
        RouteRequest second = new RouteRequest("GCJ02", new Coordinate(116.3, 39.9),
                new Coordinate(116.4, 39.9), "walking", NOW);
        String firstKey = AdapterCacheKeys.route("v1", "amap|attacker", first);
        String secondKey = AdapterCacheKeys.route("v1", "amap|attacker", second);

        assertThat(firstKey).startsWith("cache:v1:route:");
        assertThat(firstKey).doesNotContain("amap|attacker");
        assertThat(firstKey).doesNotContain("walk|ing");
        assertThat(firstKey).isNotEqualTo(secondKey);
        assertThat(AdapterCacheKeys.poi("v1", "amap", new PoiSearchRequest("a|b", "c|d", "e", 1)))
                .isNotEqualTo(AdapterCacheKeys.poi("v1", "amap", new PoiSearchRequest("a", "b|c|d", "e", 1)));
    }

    @Test
    void cacheKeysUseFrozenNamespacesAndRetainVersionAndParameters() {
        RouteRequest routeRequest = new RouteRequest("GCJ02", new Coordinate(116.3, 39.9),
                new Coordinate(116.4, 39.9), "walking", NOW);
        PoiSearchRequest poiRequest = new PoiSearchRequest("beijing", "故宫", "attraction", 1);
        WeatherRequest weatherRequest = new WeatherRequest("beijing", NOW.atZone(ZoneOffset.UTC).toLocalDate(),
                NOW.atZone(ZoneOffset.UTC).toLocalDate().plusDays(3), 3);

        String routeKey = AdapterCacheKeys.route("v1", "amap", routeRequest);
        String poiKey = AdapterCacheKeys.poi("v1", "amap", poiRequest);
        String weatherKey = AdapterCacheKeys.weather("v1", "qweather", weatherRequest);

        assertThat(routeKey).startsWith("cache:v1:route:");
        assertThat(poiKey).startsWith("cache:v1:poi:");
        assertThat(weatherKey).startsWith("cache:v1:weather:");
        assertThat(routeKey).contains(":p");
        assertThat(poiKey).contains(":p");
        assertThat(weatherKey).contains(":p");
        assertThat(AdapterCacheKeys.route("v2", "amap", routeRequest)).isNotEqualTo(routeKey);
        assertThat(AdapterCacheKeys.poi("v2", "amap", poiRequest)).isNotEqualTo(poiKey);
        assertThat(AdapterCacheKeys.weather("v2", "qweather", weatherRequest)).isNotEqualTo(weatherKey);
        assertThat(AdapterCacheKeys.poi("v1", "amap", poiRequest, "110000"))
                .isNotEqualTo(AdapterCacheKeys.poi("v1", "amap", poiRequest, "310000"));
        assertThat(AdapterCacheKeys.weather("v1", "qweather", weatherRequest, "101010100", 3))
                .isNotEqualTo(
                        AdapterCacheKeys.weather("v1", "qweather", weatherRequest, "101020100", 3))
                .isNotEqualTo(
                        AdapterCacheKeys.weather("v1", "qweather", weatherRequest, "101010100", 7));
        assertThat(AdapterCacheKeys.namespace("v1", "route", "amap"))
                .startsWith("cache:v1:route:");
        assertThat(AdapterCacheKeys.versionNamespaces("v1"))
                .containsExactly("cache:v1:route:" + digestForTest("v1") + ":",
                        "cache:v1:poi:" + digestForTest("v1") + ":",
                        "cache:v1:weather:" + digestForTest("v1") + ":");
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
            assertThat(response.source().estimated()).isTrue();
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

        assertThat(cache.get("key", NOW.plusMillis(999)).fresh()).isTrue();
        assertThat(cache.get("key", NOW.plusSeconds(1)).stale()).isTrue();
        assertThat(cache.get("key", NOW.plusSeconds(1)).entry().source().sourceRecordId()).isEqualTo("route-1");
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
    void halfOpenNonRetryableFailureClearsProbeAndAllowsRecovery() {
        MutableClock clock = new MutableClock(NOW);
        ProviderPolicy policy = new ProviderPolicy(Duration.ofSeconds(1), 0, Duration.ZERO, 1,
                Duration.ofSeconds(1), 20, 100, Duration.ofHours(1), Duration.ofMinutes(5),
                Duration.ofSeconds(10), "v1");
        try (ProviderCallExecutor calls = executor(policy, clock)) {
            assertThatThrownBy(() -> calls.call(ProviderKind.AMAP_ROUTE,
                    () -> { throw new ProviderException(ProviderKind.AMAP_ROUTE, ProviderErrorCode.TIMEOUT, "超时"); }))
                    .isInstanceOf(ProviderException.class)
                    .extracting(exception -> ((ProviderException) exception).code())
                    .isEqualTo(ProviderErrorCode.TIMEOUT);
            clock.advance(Duration.ofSeconds(2));
            assertThatThrownBy(() -> calls.call(ProviderKind.AMAP_ROUTE,
                    () -> { throw new ProviderException(ProviderKind.AMAP_ROUTE,
                            ProviderErrorCode.AUTHENTICATION_FAILED, "鉴权失败"); }))
                    .isInstanceOf(ProviderException.class)
                    .extracting(exception -> ((ProviderException) exception).code())
                    .isEqualTo(ProviderErrorCode.AUTHENTICATION_FAILED);
            assertThat(calls.call(ProviderKind.AMAP_ROUTE, () -> "recovered")).isEqualTo("recovered");
        }
    }

    @Test
    void staleCacheFallsBackWithVisibleDegradedSourceAndRestoresEntry() {
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        InMemoryProviderCache<RouteData> cache = new InMemoryProviderCache<>();
        RouteRequest request = new RouteRequest("GCJ02", new Coordinate(116.3, 39.9),
                new Coordinate(116.4, 39.9), "dirty", NOW);
        RouteData old = new RouteData(request.origin(), request.destination(), "walking", 100, 1, true);
        String key = AdapterCacheKeys.route(policy.cacheVersion(), "amap", request);
        ProviderSource source = new ProviderSource("amap", "old-route", NOW.minusSeconds(10),
                NOW.minusSeconds(1), FreshnessStatus.STALE, true);
        cache.restore(key, CacheEntry.value(old, source, NOW.minusSeconds(1)));
        try (ProviderCallExecutor calls = executor(policy)) {
            MockAmapAdapter adapter = new MockAmapAdapter("amap", policy, CLOCK, calls, cache,
                    new InMemoryProviderCache<>());
            ProviderResponse<RouteData> response = adapter.route(request);

            assertThat(response.degraded()).isTrue();
            assertThat(response.degradationCode()).isEqualTo(ProviderErrorCode.DIRTY_DATA);
            assertThat(response.source().freshnessStatus()).isEqualTo(FreshnessStatus.STALE);
            assertThat(cache.get(key, NOW).entry().value()).isEqualTo(old);
        }
    }

    @Test
    void unknownCacheSourceIsReturnedAsVisibleDegradation() {
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        InMemoryProviderCache<RouteData> cache = new InMemoryProviderCache<>();
        RouteRequest request = new RouteRequest("GCJ02", new Coordinate(116.3, 39.9),
                new Coordinate(116.4, 39.9), "walking", NOW);
        String key = AdapterCacheKeys.route(policy.cacheVersion(), "amap", request);
        RouteData route = new RouteData(request.origin(), request.destination(), "walking", 100, 1, true);
        cache.restore(key, CacheEntry.value(route,
                new ProviderSource("amap", "unknown-route", NOW, null, FreshnessStatus.UNKNOWN, true),
                NOW.plusSeconds(30)));
        try (ProviderCallExecutor calls = executor(policy)) {
            MockAmapAdapter adapter = new MockAmapAdapter("amap", policy, CLOCK, calls, cache,
                    new InMemoryProviderCache<>());
            ProviderResponse<RouteData> response = adapter.route(request);

            assertThat(response.degraded()).isTrue();
            assertThat(response.source().freshnessStatus()).isEqualTo(FreshnessStatus.UNKNOWN);
        }
    }

    @Test
    void cacheSupportsSingleFlightAndScopedInvalidation() throws Exception {
        InMemoryProviderCache<String> cache = new InMemoryProviderCache<>();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> cache.singleFlight("cache:v1:route:key", () -> {
                loads.incrementAndGet();
                started.countDown();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("测试线程被中断", exception);
                }
                return "value";
            }));
            started.await();
            Future<String> second = executor.submit(() -> cache.singleFlight("cache:v1:route:key", () -> {
                loads.incrementAndGet();
                return "other";
            }));
            assertThat(first.get()).isEqualTo("value");
            assertThat(second.get()).isEqualTo("value");
            assertThat(loads).hasValue(1);

            String v1Route = AdapterCacheKeys.namespace("v1", "route", "amap") + "a";
            String v1Poi = AdapterCacheKeys.namespace("v1", "poi", "amap") + "b";
            String v2Route = AdapterCacheKeys.namespace("v2", "route", "amap") + "c";
            cache.put(v1Route, "a", source(), NOW, Duration.ofMinutes(1));
            cache.put(v1Poi, "b", source(), NOW, Duration.ofMinutes(1));
            cache.put(v2Route, "c", source(), NOW, Duration.ofMinutes(1));
            cache.invalidateVersion("v1");
            assertThat(cache.get(v1Route, NOW).present()).isFalse();
            assertThat(cache.get(v1Poi, NOW).present()).isFalse();
            assertThat(cache.get(v2Route, NOW).present()).isTrue();
            cache.invalidate(v2Route);
            assertThat(cache.get(v2Route, NOW).present()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void domainCacheTtlsArePositiveAndIndependent() {
        AdapterProperties properties = new AdapterProperties();
        assertThat(properties.getRouteCacheTtlSeconds()).isEqualTo(604800);
        assertThat(properties.getWeatherCacheTtlSeconds()).isEqualTo(3600);
        assertThat(properties.getPoiCacheTtlSeconds()).isEqualTo(2592000);
        properties.setWeatherCacheTtlSeconds(0);
        assertThatThrownBy(() -> properties.policy(Duration.ofSeconds(properties.getWeatherCacheTtlSeconds())))
                .isInstanceOf(IllegalArgumentException.class);
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
        return executor(policy, CLOCK);
    }

    private ProviderCallExecutor executor(ProviderPolicy policy, Clock clock) {
        Map<ProviderKind, ProviderPolicy> policies = new EnumMap<>(ProviderKind.class);
        for (ProviderKind kind : ProviderKind.values()) {
            policies.put(kind, policy);
        }
        return new ProviderCallExecutor(policies, clock);
    }

    private ProviderSource source() {
        return new ProviderSource("amap", "source", NOW, NOW.plusSeconds(30), FreshnessStatus.FRESH, true);
    }

    private String digestForTest(String value) {
        return AdapterCacheKeys.route(value, "amap", new RouteRequest("GCJ02", new Coordinate(116.3, 39.9),
                new Coordinate(116.4, 39.9), "walking", NOW)).split(":")[3];
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
