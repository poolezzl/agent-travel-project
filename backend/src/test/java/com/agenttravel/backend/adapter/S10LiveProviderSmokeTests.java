package com.agenttravel.backend.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;

@EnabledIfEnvironmentVariables({
    @EnabledIfEnvironmentVariable(named = "RUN_S10_LIVE_SMOKE", matches = "true"),
    @EnabledIfEnvironmentVariable(named = "AMAP_WEB_KEY", matches = ".+"),
    @EnabledIfEnvironmentVariable(named = "QWEATHER_API_KEY", matches = ".+"),
    @EnabledIfEnvironmentVariable(named = "QWEATHER_API_HOST", matches = ".+")
})
class S10LiveProviderSmokeTests {

    private static final String STANDARD_CITY = "beijing";
    private static final int FORECAST_DAYS = 3;
    private static final ZoneId SUPPLIER_ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void amapPoiSmokeUsesJavaAdapterAndStandardCityMapping() {
        long started = System.nanoTime();
        try (LiveAdapters adapters = adapters()) {
            ProviderResponse<List<PoiRecord>> response =
                    adapters.amap().search(new PoiSearchRequest(STANDARD_CITY, "天安门", "110200", 1));

            assertThat(response.value()).isNotEmpty();
            assertThat(response.source().retrievedAt()).isNotNull();
            assertThat(response.source().validUntil()).isNotNull();
            assertThat(response.source().estimated()).isFalse();
            assertThat(response.value())
                    .allSatisfy(
                            poi -> {
                                assertThat(poi.cityId()).isEqualTo(STANDARD_CITY);
                                assertThat(poi.source().retrievedAt()).isNotNull();
                                assertThat(poi.source().validUntil()).isNotNull();
                            });
            printResult("amap_poi", "success", "none", started, response.value().size(), null);
        } catch (ProviderException exception) {
            printResult("amap_poi", "failed", exception.code().name(), started, 0, null);
            throw exception;
        }
    }

    @Test
    void amapDrivingSmokeUsesJavaAdapter() {
        long started = System.nanoTime();
        try (LiveAdapters adapters = adapters()) {
            ProviderResponse<RouteData> response =
                    adapters.amap()
                            .route(
                                    new RouteRequest(
                                            "GCJ02",
                                            new Coordinate(116.397, 39.908),
                                            new Coordinate(116.273, 39.999),
                                            "driving",
                                            Clock.systemUTC().instant()));

            assertThat(response.value().distanceMeters()).isPositive();
            assertThat(response.value().durationMinutes()).isPositive();
            assertThat(response.value().estimated()).isFalse();
            assertThat(response.source().retrievedAt()).isNotNull();
            assertThat(response.source().validUntil()).isNotNull();
            assertThat(response.source().estimated()).isFalse();
            printResult(
                    "amap_driving",
                    "success",
                    "none",
                    started,
                    response.value().distanceMeters() > 0 ? 1 : 0,
                    null);
        } catch (ProviderException exception) {
            printResult("amap_driving", "failed", exception.code().name(), started, 0, null);
            throw exception;
        }
    }

    @Test
    void qweatherThreeDaySmokeUsesJavaAdapterAndStandardCityMapping() {
        long started = System.nanoTime();
        LocalDate today = LocalDate.now(SUPPLIER_ZONE);
        try (LiveAdapters adapters = adapters()) {
            ProviderResponse<List<WeatherRecord>> response =
                    adapters.qweather()
                            .forecast(
                                    new WeatherRequest(
                                            STANDARD_CITY,
                                            today,
                                            today.plusDays(FORECAST_DAYS - 1L),
                                            FORECAST_DAYS));

            assertThat(response.value()).hasSize(FORECAST_DAYS);
            assertThat(response.value())
                    .allSatisfy(
                            weather -> {
                                assertThat(weather.dataType()).isEqualTo("forecast");
                                assertThat(weather.sourceRecordId())
                                        .contains("qweather:" + STANDARD_CITY + ":");
                                assertThat(weather.retrievedAt()).isNotNull();
                                assertThat(weather.validUntil()).isNotNull();
                                assertThat(weather.freshnessStatus())
                                        .isEqualTo(FreshnessStatus.FRESH);
                            });
            assertThat(response.source().retrievedAt()).isNotNull();
            assertThat(response.source().validUntil()).isNotNull();
            assertThat(response.source().estimated()).isFalse();
            printResult(
                    "qweather",
                    "success",
                    "none",
                    started,
                    response.value().size(),
                    FORECAST_DAYS);
        } catch (ProviderException exception) {
            printResult(
                    "qweather", "failed", exception.code().name(), started, 0, FORECAST_DAYS);
            throw exception;
        }
    }

    private LiveAdapters adapters() {
        String amapKey = requiredEnvironment("AMAP_WEB_KEY");
        String qweatherKey = requiredEnvironment("QWEATHER_API_KEY");
        String qweatherHost = requiredEnvironment("QWEATHER_API_HOST");
        String amapBaseUrl = System.getenv().getOrDefault("AMAP_BASE_URL", "https://restapi.amap.com");
        String qweatherBaseUrl =
                qweatherHost.contains("://") ? qweatherHost : "https://" + qweatherHost;

        ProviderPolicy policy =
                new ProviderPolicy(
                        Duration.ofSeconds(15),
                        1,
                        Duration.ofMillis(200),
                        3,
                        Duration.ofSeconds(10),
                        1,
                        10,
                        Duration.ofHours(1),
                        Duration.ofMinutes(5),
                        Duration.ofSeconds(15),
                        "s10-live-smoke");
        Map<ProviderKind, ProviderPolicy> policies = new EnumMap<>(ProviderKind.class);
        for (ProviderKind kind : ProviderKind.values()) {
            policies.put(kind, policy);
        }

        ProviderCallExecutor calls = new ProviderCallExecutor(policies);
        JavaProviderHttpTransport transport =
                new JavaProviderHttpTransport(Duration.ofSeconds(5), 1_048_576);
        AdapterCityRegistry cities =
                new AdapterCityRegistry(
                        Map.of(
                                STANDARD_CITY,
                                new ProviderCityMappingProperties(
                                        "110000", "010", "101010100")));
        ObjectMapper mapper = new ObjectMapper();
        RealAmapAdapter amap =
                new RealAmapAdapter(
                        "amap",
                        URI.create(amapBaseUrl),
                        amapKey,
                        policy,
                        policy,
                        Clock.systemUTC(),
                        calls,
                        transport,
                        cities,
                        mapper,
                        new InMemoryProviderCache<>(),
                        new InMemoryProviderCache<>());
        RealQWeatherAdapter qweather =
                new RealQWeatherAdapter(
                        "qweather",
                        URI.create(qweatherBaseUrl),
                        qweatherKey,
                        FORECAST_DAYS,
                        SUPPLIER_ZONE.getId(),
                        mapper,
                        policy,
                        Clock.systemUTC(),
                        calls,
                        transport,
                        cities,
                        new InMemoryProviderCache<>());
        return new LiveAdapters(calls, amap, qweather);
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("真实冒烟测试环境变量缺失");
        }
        return value;
    }

    private void printResult(
            String target,
            String status,
            String errorCode,
            long started,
            int count,
            Integer forecastDays) {
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        if (forecastDays == null) {
            System.out.printf(
                    "%s status=%s error_code=%s elapsed_ms=%d count=%d%n",
                    target, status, errorCode, elapsedMs, count);
        } else {
            System.out.printf(
                    "%s status=%s error_code=%s elapsed_ms=%d count=%d forecast_days=%d%n",
                    target, status, errorCode, elapsedMs, count, forecastDays);
        }
    }

    private record LiveAdapters(
            ProviderCallExecutor calls, RealAmapAdapter amap, RealQWeatherAdapter qweather)
            implements AutoCloseable {

        @Override
        public void close() {
            calls.close();
        }
    }
}
