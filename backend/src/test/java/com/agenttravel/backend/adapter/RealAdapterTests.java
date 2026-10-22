package com.agenttravel.backend.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class RealAdapterTests {

    private static final Instant NOW = Instant.parse("2026-10-21T20:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    private static final URI BASE_URI = URI.create("https://provider.example");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AdapterCityRegistry CITY_REGISTRY = cityRegistry();

    @Test
    void amapRouteParsesSupplierValuesAndPreservesSource() {
        StubTransport transport =
                StubTransport.json(
                        """
                        {"status":"1","route":{"origin":"116.300000,39.900000",
                        "destination":"116.400000,39.900000",
                        "paths":[{"distance":"1200","duration":"600"}]}}
                        """);
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        try (ProviderCallExecutor calls = executor(policy)) {
            RealAmapAdapter adapter = amap(policy, calls, transport);
            ProviderResponse<RouteData> response = adapter.route(route("GCJ02", "driving"));

            assertThat(response.value().distanceMeters()).isEqualTo(1200);
            assertThat(response.value().durationMinutes()).isEqualTo(10);
            assertThat(response.value().estimated()).isFalse();
            assertThat(response.source().estimated()).isFalse();
            assertThat(response.source().freshnessStatus()).isEqualTo(FreshnessStatus.FRESH);
            assertThat(transport.requests).hasSize(1);
            assertThat(transport.requests.get(0).uri().getPath())
                    .isEqualTo("/v3/direction/driving");
            assertThat(transport.requests.get(0).uri().getHost()).isEqualTo("provider.example");
            assertThat(transport.requests.get(0).uri().getQuery())
                    .contains("origin=116.300000,39.900000")
                    .contains("destination=116.400000,39.900000")
                    .contains("key=test-key");
        }
    }

    @Test
    void amapAuthenticationAndProviderRateLimitAreNotNegativeCached() {
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        StubTransport auth = StubTransport.status(401, "{\"status\":\"0\"}");
        try (ProviderCallExecutor calls = executor(policy)) {
            RealAmapAdapter adapter = amap(policy, calls, auth);
            assertProviderCode(() -> adapter.route(route("GCJ02", "walking")), ProviderErrorCode.AUTHENTICATION_FAILED);
            assertProviderCode(() -> adapter.route(route("GCJ02", "walking")), ProviderErrorCode.AUTHENTICATION_FAILED);
            assertThat(auth.requests).hasSize(2);
        }

        StubTransport limited =
                StubTransport.status(429, "{\"status\":\"0\",\"infocode\":\"10004\"}");
        try (ProviderCallExecutor calls = executor(policy)) {
            RealAmapAdapter adapter = amap(policy, calls, limited);
            assertProviderCode(
                    () -> adapter.route(route("GCJ02", "driving")),
                    ProviderErrorCode.PROVIDER_RATE_LIMITED);
            assertProviderCode(
                    () -> adapter.route(route("GCJ02", "driving")),
                    ProviderErrorCode.PROVIDER_RATE_LIMITED);
            assertThat(limited.requests).hasSize(2);
        }
    }

    @Test
    void amapTimeoutUsesConfiguredRetryLimitAndDoesNotBecomeEmpty() {
        AtomicInteger attempts = new AtomicInteger();
        StubTransport transport =
                new StubTransport(
                        () -> {
                            attempts.incrementAndGet();
                            throw new ProviderTransportException(
                                    ProviderErrorCode.TIMEOUT, "供应商请求超时", null);
                        });
        ProviderPolicy policy = policy(Duration.ofMillis(50), 1, 30, 100);
        try (ProviderCallExecutor calls = executor(policy)) {
            RealAmapAdapter adapter = amap(policy, calls, transport);
            assertProviderCode(() -> adapter.route(route("GCJ02", "driving")), ProviderErrorCode.TIMEOUT);
            assertThat(attempts).hasValue(2);
        }
    }

    @Test
    void amapPoiDeduplicatesAndExcludesMissingCoordinateAndCrossCityRows() {
        StubTransport transport =
                StubTransport.json(
                        """
                        {"status":"1","pois":[
                          {"id":"poi-1","name":"故宫","location":"116.397,39.909",
                           "cityname":"北京市","adcode":"110101","typecode":"110200"},
                          {"id":"poi-1","name":"故宫重复","location":"116.397,39.909",
                           "cityname":"北京市","adcode":"110101","typecode":"110200"},
                          {"id":"poi-2","name":"缺坐标","cityname":"北京市","adcode":"110101"},
                          {"id":"poi-3","name":"外地点","location":"121.473,31.230",
                           "cityname":"上海市","adcode":"310101","typecode":"110200"}
                        ]}
                        """);
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        try (ProviderCallExecutor calls = executor(policy)) {
            RealAmapAdapter adapter = amap(policy, calls, transport);
            ProviderResponse<List<PoiRecord>> response =
                    adapter.search(new PoiSearchRequest("beijing", "故宫", "110200", 1));

            assertThat(response.value()).hasSize(1);
            assertThat(response.value().get(0).poiId()).isEqualTo("poi-1");
            assertThat(response.value().get(0).cityId()).isEqualTo("beijing");
            assertThat(response.value().get(0).coordinateSystem()).isEqualTo("GCJ02");
            assertThat(response.value().get(0).source().estimated()).isFalse();
            assertThat(response.degraded()).isTrue();
            assertThat(response.degradationCode()).isEqualTo(ProviderErrorCode.DIRTY_DATA);
            assertThat(transport.requests.get(0).uri().getQuery())
                    .contains("city=110000")
                    .contains("city_limit=true")
                    .contains("types=110200");
        }
    }

    @Test
    void amapPoiEmptyIsShortNegativeCachedAndDirtyJsonIsNotBusinessEmpty() {
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        StubTransport empty = StubTransport.json("{\"status\":\"1\",\"pois\":[]}");
        try (ProviderCallExecutor calls = executor(policy)) {
            RealAmapAdapter adapter = amap(policy, calls, empty);
            PoiSearchRequest request = new PoiSearchRequest("beijing", "空结果", "any", 1);
            assertProviderCode(() -> adapter.search(request), ProviderErrorCode.EMPTY_RESULT);
            assertProviderCode(() -> adapter.search(request), ProviderErrorCode.EMPTY_RESULT);
            assertThat(empty.requests).hasSize(1);
        }

        StubTransport dirty = StubTransport.json("{not-json");
        try (ProviderCallExecutor calls = executor(policy)) {
            RealAmapAdapter adapter = amap(policy, calls, dirty);
            PoiSearchRequest request = new PoiSearchRequest("beijing", "脏数据", "any", 1);
            assertProviderCode(() -> adapter.search(request), ProviderErrorCode.INVALID_RESPONSE);
            assertProviderCode(() -> adapter.search(request), ProviderErrorCode.INVALID_RESPONSE);
            assertThat(dirty.requests).hasSize(1);
        }
    }

    @Test
    void amapAllCrossCityPoiRowsFailAsDirtyData() {
        StubTransport transport =
                StubTransport.json(
                        """
                        {"status":"1","pois":[{"id":"poi-1","name":"外地点",
                        "location":"121.473,31.230","cityname":"上海市",
                        "adcode":"310101","typecode":"110200"}]}
                        """);
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        try (ProviderCallExecutor calls = executor(policy)) {
            RealAmapAdapter adapter = amap(policy, calls, transport);
            assertProviderCode(
                    () -> adapter.search(new PoiSearchRequest("beijing", "故宫", "110200", 1)),
                    ProviderErrorCode.DIRTY_DATA);
        }
    }

    @Test
    void qweatherParsesActualWindowInSupplierTimezoneAndCachesSource() {
        StubTransport transport = StubTransport.json(qweatherBody(TODAY));
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        try (ProviderCallExecutor calls = executor(policy)) {
            RealQWeatherAdapter adapter = weather(policy, calls, transport, 3);
            WeatherRequest request = new WeatherRequest("beijing", TODAY, TODAY.plusDays(2), 3);
            ProviderResponse<List<WeatherRecord>> first = adapter.forecast(request);
            ProviderResponse<List<WeatherRecord>> second = adapter.forecast(request);

            assertThat(first.value()).hasSize(3);
            assertThat(first.value()).allMatch(record -> record.dataType().equals("forecast"));
            assertThat(first.value().get(0).precipProbability()).isEqualTo(0.2);
            assertThat(first.value().get(0).condition()).isEqualTo("晴");
            assertThat(first.source().estimated()).isFalse();
            assertThat(second.cacheHit()).isTrue();
            assertThat(second.source().retrievedAt()).isEqualTo(first.source().retrievedAt());
            assertThat(second.source().validUntil()).isEqualTo(first.source().validUntil());
            assertThat(first.value().get(0).sourceRecordId())
                    .isEqualTo("qweather:beijing:2026-10-22:qweather:3d");
            assertThat(transport.requests).hasSize(1);
            assertThat(transport.requests.get(0).uri().getPath()).isEqualTo("/v7/weather/3d");
            assertThat(transport.requests.get(0).uri().getQuery()).contains("location=101010100");
        }
    }

    @Test
    void qweatherNeverFillsDatesOutsideActualForecastWindow() {
        StubTransport transport = StubTransport.json(qweatherBody(TODAY));
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        try (ProviderCallExecutor calls = executor(policy)) {
            RealQWeatherAdapter adapter = weather(policy, calls, transport, 3);
            WeatherRequest partial =
                    new WeatherRequest("beijing", TODAY, TODAY.plusDays(3), 3);
            ProviderResponse<List<WeatherRecord>> response = adapter.forecast(partial);

            assertThat(response.value()).hasSize(3);
            assertThat(response.value()).noneMatch(record -> record.date().equals(TODAY.plusDays(3)));
            assertThat(response.degraded()).isTrue();
            assertThat(response.degradationCode())
                    .isEqualTo(ProviderErrorCode.FORECAST_WINDOW_OUTSIDE);

            WeatherRequest outside =
                    new WeatherRequest("beijing", TODAY.plusDays(5), TODAY.plusDays(6), 3);
            assertProviderCode(
                    () -> adapter.forecast(outside), ProviderErrorCode.FORECAST_WINDOW_OUTSIDE);
        }
    }

    @Test
    void qweatherAuthenticationQuotaAndRateLimitRemainDistinctAndNotNegative() {
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        StubTransport auth = StubTransport.status(401, "{\"code\":\"401\"}");
        try (ProviderCallExecutor calls = executor(policy)) {
            RealQWeatherAdapter adapter = weather(policy, calls, auth, 3);
            assertProviderCode(() -> adapter.forecast(todayRequest()), ProviderErrorCode.AUTHENTICATION_FAILED);
            assertProviderCode(() -> adapter.forecast(todayRequest()), ProviderErrorCode.AUTHENTICATION_FAILED);
            assertThat(auth.requests).hasSize(2);
        }

        StubTransport quota = StubTransport.json("{\"code\":\"402\"}");
        try (ProviderCallExecutor calls = executor(policy)) {
            RealQWeatherAdapter adapter = weather(policy, calls, quota, 3);
            assertProviderCode(() -> adapter.forecast(todayRequest()), ProviderErrorCode.QUOTA_EXCEEDED);
            assertProviderCode(() -> adapter.forecast(todayRequest()), ProviderErrorCode.QUOTA_EXCEEDED);
            assertThat(quota.requests).hasSize(2);
        }

        StubTransport limited = StubTransport.status(429, "{\"code\":\"429\"}");
        try (ProviderCallExecutor calls = executor(policy)) {
            RealQWeatherAdapter adapter = weather(policy, calls, limited, 3);
            assertProviderCode(
                    () -> adapter.forecast(todayRequest()),
                    ProviderErrorCode.PROVIDER_RATE_LIMITED);
            assertProviderCode(
                    () -> adapter.forecast(todayRequest()),
                    ProviderErrorCode.PROVIDER_RATE_LIMITED);
            assertThat(limited.requests).hasSize(2);
        }
    }

    @Test
    void qweatherDirtyRowsFailOrRemainExplicitlyDegraded() {
        StubTransport dirty =
                StubTransport.json(
                        """
                        {"code":"200","daily":[
                          {"fxDate":"2026-10-22","tempMin":"","tempMax":"22","textDay":"晴","pop":"10"},
                          {"fxDate":"2026-10-23","tempMin":"15","tempMax":"25","textDay":"多云","pop":"20"},
                          {"fxDate":"2026-10-24","tempMin":"14","tempMax":"24","textDay":"阴","pop":"30"}
                        ]}
                        """);
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        try (ProviderCallExecutor calls = executor(policy)) {
            RealQWeatherAdapter adapter = weather(policy, calls, dirty, 3);
            ProviderResponse<List<WeatherRecord>> response =
                    adapter.forecast(new WeatherRequest("beijing", TODAY, TODAY.plusDays(2), 3));

            assertThat(response.value()).hasSize(2);
            assertThat(response.degraded()).isTrue();
            assertThat(response.degradationCode()).isEqualTo(ProviderErrorCode.DIRTY_DATA);
        }
    }

    @Test
    void weatherRecordsExpireIndividuallyAcrossMidnight() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-22T15:55:00Z"));
        AtomicInteger attempts = new AtomicInteger();
        StubTransport transport =
                new StubTransport(
                        () ->
                                attempts.getAndIncrement() == 0
                                        ? new ProviderHttpResponse(200, qweatherBody(TODAY))
                                        : new ProviderHttpResponse(503, "{}"));
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100, Duration.ofHours(1));
        try (ProviderCallExecutor calls = executor(policy, clock)) {
            RealQWeatherAdapter adapter = weather(policy, calls, transport, 3, clock);
            WeatherRequest request = new WeatherRequest("beijing", TODAY, TODAY.plusDays(2), 3);
            ProviderResponse<List<WeatherRecord>> first = adapter.forecast(request);
            assertThat(first.value()).allMatch(
                    record -> record.freshnessStatus() == FreshnessStatus.FRESH);

            clock.advance(Duration.ofMinutes(10));
            ProviderResponse<List<WeatherRecord>> second = adapter.forecast(request);

            assertThat(second.cacheHit()).isTrue();
            assertThat(second.value().get(0).freshnessStatus())
                    .as("第二条天气为 %s", second.value().get(0))
                    .isEqualTo(FreshnessStatus.STALE);
            assertThat(second.value().get(1).freshnessStatus()).isEqualTo(FreshnessStatus.FRESH);
            assertThat(second.source().freshnessStatus()).isEqualTo(FreshnessStatus.STALE);
            assertThat(second.source().validUntil()).isEqualTo(second.value().get(0).validUntil());
            assertThat(second.degraded()).isTrue();
        }
    }

    @Test
    void weatherUnknownDailyValidityIsNeverReportedFresh() {
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100, Duration.ofHours(1));
        WeatherRequest request = new WeatherRequest("beijing", TODAY, TODAY, 3);
        InMemoryProviderCache<List<WeatherRecord>> cache = new InMemoryProviderCache<>();
        String key =
                AdapterCacheKeys.weather(
                        policy.cacheVersion(), "qweather", request, "101010100", 3);
        WeatherRecord record =
                new WeatherRecord(
                        TODAY,
                        15,
                        25,
                        0.2,
                        "晴",
                        "forecast",
                        "qweather:beijing:2026-10-22:qweather:3d",
                        NOW,
                        null,
                        FreshnessStatus.UNKNOWN);
        ProviderSource source =
                new ProviderSource(
                        "qweather",
                        "unknown-weather",
                        NOW,
                        null,
                        FreshnessStatus.UNKNOWN,
                        false);
        cache.restore(key, CacheEntry.value(List.of(record), source, NOW.plusSeconds(3600)));
        StubTransport unavailable = StubTransport.status(503, "{}");

        try (ProviderCallExecutor calls = executor(policy)) {
            RealQWeatherAdapter adapter =
                    weather(policy, calls, unavailable, 3, CLOCK, cache);
            ProviderResponse<List<WeatherRecord>> response = adapter.forecast(request);

            assertThat(response.cacheHit()).isTrue();
            assertThat(response.value().get(0).freshnessStatus()).isEqualTo(FreshnessStatus.UNKNOWN);
            assertThat(response.source().freshnessStatus()).isEqualTo(FreshnessStatus.UNKNOWN);
            assertThat(response.source().validUntil()).isNull();
            assertThat(response.degraded()).isTrue();
        }
    }

    @Test
    void missingStandardCityMappingFailsBeforeProviderAccess() {
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        StubTransport transport = StubTransport.json("{}");
        try (ProviderCallExecutor calls = executor(policy)) {
            RealAmapAdapter amap = amap(policy, calls, transport);
            assertProviderCode(
                    () -> amap.search(new PoiSearchRequest("shanghai", "外滩", "any", 1)),
                    ProviderErrorCode.INVALID_REQUEST);

            RealQWeatherAdapter weather = weather(policy, calls, transport, 3);
            assertProviderCode(
                    () -> weather.forecast(new WeatherRequest("shanghai", TODAY, TODAY, 3)),
                    ProviderErrorCode.INVALID_REQUEST);
            assertThat(transport.requests).isEmpty();
        }
    }

    @Test
    void staleRealRouteCacheIsReturnedWithVisibleDegradation() {
        ProviderPolicy policy = policy(Duration.ofSeconds(1), 0, 30, 100);
        InMemoryProviderCache<RouteData> routeCache = new InMemoryProviderCache<>();
        RouteRequest request = route("GCJ02", "driving");
        String key = AdapterCacheKeys.route(policy.cacheVersion(), "amap", request);
        RouteData old =
                new RouteData(
                        request.origin(), request.destination(), request.mode(), 1000, 8, false);
        ProviderSource staleSource =
                new ProviderSource(
                        "amap",
                        "old-route",
                        NOW.minusSeconds(20),
                        NOW.minusSeconds(1),
                        FreshnessStatus.STALE,
                        false);
        routeCache.restore(key, CacheEntry.value(old, staleSource, NOW.minusSeconds(1)));
        StubTransport unavailable = StubTransport.status(503, "{}");
        try (ProviderCallExecutor calls = executor(policy)) {
            RealAmapAdapter adapter =
                    new RealAmapAdapter(
                            "amap",
                            BASE_URI,
                            "test-key",
                            policy,
                            policy,
                            CLOCK,
                            calls,
                            unavailable,
                            CITY_REGISTRY,
                            MAPPER,
                            routeCache,
                            new InMemoryProviderCache<>());
            ProviderResponse<RouteData> response = adapter.route(request);

            assertThat(response.degraded()).isTrue();
            assertThat(response.degradationCode()).isEqualTo(ProviderErrorCode.UNAVAILABLE);
            assertThat(response.source().sourceRecordId()).isEqualTo("old-route");
            assertThat(response.source().freshnessStatus()).isEqualTo(FreshnessStatus.STALE);
            assertThat(response.source().estimated()).isFalse();
        }
    }

    @Test
    void productionTransportRejectsPlainHttpWithoutNetworkAccess() {
        JavaProviderHttpTransport transport =
                new JavaProviderHttpTransport(Duration.ofMillis(100), 1024);
        ProviderHttpRequest request =
                new ProviderHttpRequest(
                        URI.create("http://127.0.0.1/never"), Map.of(), Duration.ofMillis(100));

        assertThatThrownBy(() -> transport.send(request))
                .isInstanceOf(ProviderTransportException.class)
                .extracting(exception -> ((ProviderTransportException) exception).code())
                .isEqualTo(ProviderErrorCode.INVALID_REQUEST);
    }

    @Test
    void realModeWiresRealAdaptersWithoutCallingProviders() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "real-adapter-test",
                                    Map.of(
                                            "app.adapters.mode",
                                            "real",
                                            "app.adapters.amap-web-key",
                                            "test-key",
                                            "app.adapters.qweather-api-host",
                                            "provider.example",
                                            "app.adapters.qweather-api-key",
                                            "test-key")));
            context.registerBean(
                    ObjectMapper.class, (java.util.function.Supplier<ObjectMapper>) ObjectMapper::new);
            context.registerBean(
                    SimpleMeterRegistry.class,
                    (java.util.function.Supplier<SimpleMeterRegistry>) SimpleMeterRegistry::new);
            context.register(AdapterConfiguration.class);
            context.refresh();

            assertThat(context.getBean(RouteAdapter.class)).isInstanceOf(RealAmapAdapter.class);
            assertThat(context.getBean(PoiAdapter.class)).isInstanceOf(RealAmapAdapter.class);
            assertThat(context.getBean(WeatherAdapter.class))
                    .isInstanceOf(RealQWeatherAdapter.class);
        }
    }

    private RealAmapAdapter amap(
            ProviderPolicy policy, ProviderCallExecutor calls, ProviderHttpTransport transport) {
        return new RealAmapAdapter(
                "amap",
                BASE_URI,
                "test-key",
                policy,
                policy,
                CLOCK,
                calls,
                transport,
                CITY_REGISTRY,
                MAPPER,
                new InMemoryProviderCache<>(),
                new InMemoryProviderCache<>());
    }

    private RealQWeatherAdapter weather(
            ProviderPolicy policy,
            ProviderCallExecutor calls,
            ProviderHttpTransport transport,
            int forecastDays) {
        return weather(policy, calls, transport, forecastDays, CLOCK);
    }

    private RealQWeatherAdapter weather(
            ProviderPolicy policy,
            ProviderCallExecutor calls,
            ProviderHttpTransport transport,
            int forecastDays,
            Clock clock) {
        return weather(
                policy,
                calls,
                transport,
                forecastDays,
                clock,
                new InMemoryProviderCache<>());
    }

    private RealQWeatherAdapter weather(
            ProviderPolicy policy,
            ProviderCallExecutor calls,
            ProviderHttpTransport transport,
            int forecastDays,
            Clock clock,
            InMemoryProviderCache<List<WeatherRecord>> cache) {
        return new RealQWeatherAdapter(
                "qweather",
                BASE_URI,
                "test-key",
                forecastDays,
                "Asia/Shanghai",
                MAPPER,
                policy,
                clock,
                calls,
                transport,
                CITY_REGISTRY,
                cache);
    }

    private RouteRequest route(String coordinateSystem, String mode) {
        return new RouteRequest(
                coordinateSystem,
                new Coordinate(116.3, 39.9),
                new Coordinate(116.4, 39.9),
                mode,
                NOW);
    }

    private WeatherRequest todayRequest() {
        return new WeatherRequest("beijing", TODAY, TODAY, 3);
    }

    private static AdapterCityRegistry cityRegistry() {
        return new AdapterCityRegistry(
                Map.of(
                        "beijing",
                        new ProviderCityMappingProperties("110000", "010", "101010100")));
    }

    private ProviderPolicy policy(Duration timeout, int retries, int rateLimit, int quota) {
        return policy(timeout, retries, rateLimit, quota, Duration.ofMinutes(5));
    }

    private ProviderPolicy policy(
            Duration timeout,
            int retries,
            int rateLimit,
            int quota,
            Duration cacheTtl) {
        return new ProviderPolicy(
                timeout,
                retries,
                Duration.ZERO,
                3,
                Duration.ofMinutes(1),
                rateLimit,
                quota,
                Duration.ofHours(1),
                cacheTtl,
                Duration.ofSeconds(10),
                "v1");
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

    private void assertProviderCode(ThrowingCall call, ProviderErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ProviderException.class)
                .extracting(exception -> ((ProviderException) exception).code())
                .isEqualTo(expected);
    }

    private String qweatherBody(LocalDate firstDate) {
        return """
                {"code":"200","updateTime":"2026-10-22T04:00+08:00","daily":[
                  {"fxDate":"%s","tempMin":"15","tempMax":"25","textDay":"晴","pop":"20"},
                  {"fxDate":"%s","tempMin":"14","tempMax":"24","textDay":"多云","pop":"30"},
                  {"fxDate":"%s","tempMin":"13","tempMax":"23","textDay":"阴","pop":"40"}
                ]}
                """
                .formatted(firstDate, firstDate.plusDays(1), firstDate.plusDays(2));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
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

    private static final class StubTransport implements ProviderHttpTransport {

        private final List<ProviderHttpRequest> requests = new ArrayList<>();
        private final Supplier<ProviderHttpResponse> supplier;

        private StubTransport(Supplier<ProviderHttpResponse> supplier) {
            this.supplier = supplier;
        }

        private static StubTransport json(String body) {
            return new StubTransport(() -> new ProviderHttpResponse(200, body));
        }

        private static StubTransport status(int status, String body) {
            return new StubTransport(() -> new ProviderHttpResponse(status, body));
        }

        @Override
        public ProviderHttpResponse send(ProviderHttpRequest request) {
            requests.add(request);
            return supplier.get();
        }
    }
}
