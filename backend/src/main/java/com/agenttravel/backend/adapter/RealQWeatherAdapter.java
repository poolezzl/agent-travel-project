package com.agenttravel.backend.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.web.util.UriComponentsBuilder;

public final class RealQWeatherAdapter implements WeatherAdapter {

    private final String alias;
    private final URI baseUri;
    private final String apiKey;
    private final int configuredForecastDays;
    private final ZoneId supplierZone;
    private final ObjectMapper mapper;
    private final ProviderPolicy policy;
    private final Clock clock;
    private final ProviderCallExecutor calls;
    private final ProviderHttpTransport transport;
    private final AdapterCityRegistry cityRegistry;
    private final InMemoryProviderCache<List<WeatherRecord>> cache;

    public RealQWeatherAdapter(
            String alias,
            URI baseUri,
            String apiKey,
            int configuredForecastDays,
            String supplierZone,
            ObjectMapper mapper,
            ProviderPolicy policy,
            Clock clock,
            ProviderCallExecutor calls,
            ProviderHttpTransport transport,
            AdapterCityRegistry cityRegistry,
            InMemoryProviderCache<List<WeatherRecord>> cache) {
        this.alias = requireText(alias, "供应商别名");
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.apiKey = requireText(apiKey, "和风天气密钥");
        if (configuredForecastDays < 1 || configuredForecastDays > 30) {
            throw new IllegalArgumentException("和风天气预报档位必须在 1 到 30 天之间");
        }
        this.configuredForecastDays = configuredForecastDays;
        this.supplierZone = ZoneId.of(requireText(supplierZone, "供应商时区"));
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.calls = Objects.requireNonNull(calls, "calls");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.cityRegistry = Objects.requireNonNull(cityRegistry, "cityRegistry");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public ProviderResponse<List<WeatherRecord>> forecast(WeatherRequest request) {
        AdapterCityRegistry.ResolvedCity city = resolveCity(request.cityId());
        String key = AdapterCacheKeys.weather(
                policy.cacheVersion(),
                alias,
                request,
                city.qweatherLocation(),
                configuredForecastDays);
        Instant now = clock.instant();
        CacheLookup<List<WeatherRecord>> cached = cache.get(key, now);
        boolean cachedRecordsFresh =
                cached.present()
                        && !cached.entry().negative()
                        && freshness(cached.entry().value(), now).fresh();
        if (cached.present() && cached.fresh() && cachedRecordsFresh) {
            return cachedResponse(cached.entry(), now);
        }
        if (cached.present() && cached.fresh() && cached.entry().negative()) {
            throw new ProviderException(
                    ProviderKind.QWEATHER, cached.entry().negativeCode(), "天气无可用预报");
        }

        try {
            WeatherLoad load = cache.singleFlight(key, () -> loadForecast(key, request, city));
            ProviderSource source =
                    responseSource(city, request, load.records(), clock.instant());
            return new ProviderResponse<>(
                    load.records(), source, false, load.degraded(), load.degradationCode());
        } catch (ProviderException exception) {
            if (cached.present() && !cached.entry().negative() && !cachedRecordsFresh) {
                return cachedResponse(cached.entry(), clock.instant()).asDegraded(exception.code());
            }
            throw exception;
        }
    }

    private WeatherLoad loadForecast(
            String key, WeatherRequest request, AdapterCityRegistry.ResolvedCity city) {
        Instant now = clock.instant();
        URI uri = forecastUri(request, city);
        try {
            WeatherLoad load =
                    calls.call(
                            ProviderKind.QWEATHER,
                            () -> parseForecast(send(uri, request), request, city, now));
            if (!load.degraded()) {
                cache.put(
                        key,
                        load.records(),
                        responseSource(city, request, load.records(), now),
                        now,
                        policy.cacheTtl());
            }
            return load;
        } catch (ProviderException exception) {
            if (isNegativeCacheable(exception.code())) {
                cache.putNegative(key, exception.code(), now, policy.negativeCacheTtl());
            }
            throw exception;
        }
    }

    private ProviderHttpResponse send(URI uri, WeatherRequest request) {
        try {
            return transport.send(new ProviderHttpRequest(uri, Map.of(), policy.timeout()));
        } catch (ProviderTransportException exception) {
            throw new ProviderException(
                    ProviderKind.QWEATHER, exception.code(), exception.getMessage(), exception);
        }
    }

    private WeatherLoad parseForecast(
            ProviderHttpResponse response,
            WeatherRequest request,
            AdapterCityRegistry.ResolvedCity city,
            Instant retrievedAt) {
        JsonNode root = parseSuccessResponse(response);
        JsonNode daily = root.path("daily");
        if (!daily.isArray()) {
            throw new ProviderException(
                    ProviderKind.QWEATHER,
                    ProviderErrorCode.INVALID_RESPONSE,
                    "和风天气响应结构无效");
        }
        if (daily.isEmpty()) {
            throw new ProviderException(
                    ProviderKind.QWEATHER, ProviderErrorCode.EMPTY_RESULT, "和风天气预报为空");
        }

        List<WeatherRecord> records = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        int dirtyCount = 0;
        LocalDate today = LocalDate.now(clock.withZone(supplierZone));
        for (JsonNode day : daily) {
            WeatherRecord record = parseDay(day, city, retrievedAt, today);
            if (record == null) {
                dirtyCount++;
                continue;
            }
            if (!dates.add(record.date())) {
                dirtyCount++;
                continue;
            }
            if (record.date().isBefore(request.from()) || record.date().isAfter(request.to())) {
                continue;
            }
            records.add(record);
        }

        if (records.isEmpty()) {
            ProviderErrorCode code =
                    dirtyCount > 0
                            ? ProviderErrorCode.DIRTY_DATA
                            : ProviderErrorCode.FORECAST_WINDOW_OUTSIDE;
            throw new ProviderException(ProviderKind.QWEATHER, code, "请求日期不在实际预报窗口内");
        }

        long expectedDays = request.from().datesUntil(request.to().plusDays(1)).count();
        boolean windowIncomplete = records.size() < expectedDays;
        boolean dirty = dirtyCount > 0;
        ProviderErrorCode code =
                dirty
                        ? ProviderErrorCode.DIRTY_DATA
                        : windowIncomplete ? ProviderErrorCode.FORECAST_WINDOW_OUTSIDE : null;
        return new WeatherLoad(List.copyOf(records), code != null, code);
    }

    private WeatherRecord parseDay(
            JsonNode day,
            AdapterCityRegistry.ResolvedCity city,
            Instant retrievedAt,
            LocalDate today) {
        LocalDate date = parseDate(text(day, "fxDate"));
        Double tempMin = parseDecimal(text(day, "tempMin"));
        Double tempMax = parseDecimal(text(day, "tempMax"));
        String condition = text(day, "textDay");
        if (condition.isBlank()) {
            condition = text(day, "textNight");
        }
        if (date == null
                || date.isBefore(today)
                || tempMin == null
                || tempMax == null
                || tempMin > tempMax
                || condition.isBlank()) {
            return null;
        }

        Double pop = parseDecimal(text(day, "pop"));
        Double precipProbability = pop == null ? null : pop / 100.0;
        if (precipProbability != null
                && (precipProbability < 0 || precipProbability > 1)) {
            return null;
        }

        Instant dayEnd = date.plusDays(1).atStartOfDay(supplierZone).toInstant();
        Instant ttlEnd = retrievedAt.plus(policy.cacheTtl());
        Instant validUntil = dayEnd.isBefore(ttlEnd) ? dayEnd : ttlEnd;
        return new WeatherRecord(
                date,
                tempMin,
                tempMax,
                precipProbability,
                condition,
                "forecast",
                "qweather:" + city.cityKey() + ":" + date + ":" + alias + ":"
                        + configuredForecastDays + "d",
                retrievedAt,
                validUntil,
                FreshnessStatus.FRESH);
    }

    private JsonNode parseSuccessResponse(ProviderHttpResponse response) {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new ProviderException(
                    ProviderKind.QWEATHER,
                    ProviderErrorCode.AUTHENTICATION_FAILED,
                    "和风天气鉴权失败");
        }
        if (status == 429) {
            ProviderErrorCode code =
                    qweatherFailureCode(response.body())
                            .orElse(ProviderErrorCode.PROVIDER_RATE_LIMITED);
            throw new ProviderException(ProviderKind.QWEATHER, code, "和风天气返回 429");
        }
        if (status >= 500) {
            throw new ProviderException(
                    ProviderKind.QWEATHER, ProviderErrorCode.UNAVAILABLE, "和风天气服务暂不可用");
        }
        if (status < 200 || status >= 300) {
            throw new ProviderException(
                    ProviderKind.QWEATHER, ProviderErrorCode.INVALID_REQUEST, "和风天气拒绝了请求");
        }

        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (Exception exception) {
            throw new ProviderException(
                    ProviderKind.QWEATHER,
                    ProviderErrorCode.INVALID_RESPONSE,
                    "和风天气响应不是有效 JSON",
                    exception);
        }
        if (root == null || !root.isObject()) {
            throw new ProviderException(
                    ProviderKind.QWEATHER,
                    ProviderErrorCode.INVALID_RESPONSE,
                    "和风天气响应结构无效");
        }
        String code = text(root, "code");
        if (!"200".equals(code)) {
            throw new ProviderException(
                    ProviderKind.QWEATHER,
                    qweatherFailureCode(response.body())
                            .orElse(ProviderErrorCode.INVALID_RESPONSE),
                    "和风天气返回失败状态");
        }
        return root;
    }

    private java.util.Optional<ProviderErrorCode> qweatherFailureCode(String body) {
        if (body == null || body.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            String code = text(mapper.readTree(body), "code");
            return switch (code) {
                case "401", "403" ->
                        java.util.Optional.of(ProviderErrorCode.AUTHENTICATION_FAILED);
                case "402" -> java.util.Optional.of(ProviderErrorCode.QUOTA_EXCEEDED);
                case "429" -> java.util.Optional.of(ProviderErrorCode.PROVIDER_RATE_LIMITED);
                case "204" -> java.util.Optional.of(ProviderErrorCode.EMPTY_RESULT);
                case "400", "404" -> java.util.Optional.of(ProviderErrorCode.INVALID_REQUEST);
                case "500", "503" -> java.util.Optional.of(ProviderErrorCode.UNAVAILABLE);
                default -> java.util.Optional.empty();
            };
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private URI forecastUri(
            WeatherRequest request, AdapterCityRegistry.ResolvedCity city) {
        return UriComponentsBuilder.fromUri(baseUri)
                .path("/v7/weather/" + configuredForecastDays + "d")
                .queryParam("location", city.qweatherLocation())
                .queryParam("key", apiKey)
                .build()
                .encode()
                .toUri();
    }

    private ProviderSource responseSource(
            AdapterCityRegistry.ResolvedCity city,
            WeatherRequest request,
            List<WeatherRecord> records,
            Instant retrievedAt) {
        List<String> values = new ArrayList<>();
        values.add("qweather-response");
        values.add(city.cityKey());
        values.add(city.qweatherLocation());
        values.add(alias);
        values.add(request.from().toString());
        values.add(request.to().toString());
        values.add(Integer.toString(configuredForecastDays));
        records.forEach(record -> values.add(record.date().toString()));
        FreshnessView freshness = freshness(records, retrievedAt);
        return new ProviderSource(
                alias,
                sourceId(values.toArray(String[]::new)),
                retrievedAt,
                freshness.validUntil(),
                freshness.status(),
                false);
    }

    private ProviderResponse<List<WeatherRecord>> cachedResponse(
            CacheEntry<List<WeatherRecord>> entry, Instant now) {
        FreshnessView freshness = freshness(entry.value(), now);
        ProviderSource source = new ProviderSource(
                entry.source().providerAlias(),
                entry.source().sourceRecordId(),
                entry.source().retrievedAt(),
                freshness.validUntil(),
                freshness.status(),
                entry.source().estimated());
        return new ProviderResponse<>(
                freshness.records(),
                source,
                true,
                freshness.degradationCode() != null,
                freshness.degradationCode());
    }

    private FreshnessView freshness(List<WeatherRecord> records, Instant now) {
        List<WeatherRecord> refreshed =
                records.stream().map(record -> record.refreshedAt(now)).toList();
        boolean stale = false;
        boolean unknown = refreshed.isEmpty();
        Instant earliestValidUntil = null;
        for (WeatherRecord record : refreshed) {
            if (record.freshnessStatus() == FreshnessStatus.STALE) {
                stale = true;
            } else if (record.freshnessStatus() != FreshnessStatus.FRESH) {
                unknown = true;
            }
            if (record.validUntil() == null) {
                unknown = true;
            } else if (earliestValidUntil == null || record.validUntil().isBefore(earliestValidUntil)) {
                earliestValidUntil = record.validUntil();
            }
        }
        FreshnessStatus status =
                stale ? FreshnessStatus.STALE
                        : unknown ? FreshnessStatus.UNKNOWN : FreshnessStatus.FRESH;
        Instant aggregateValidUntil = unknown ? null : earliestValidUntil;
        ProviderErrorCode degradationCode =
                status == FreshnessStatus.STALE
                        ? ProviderErrorCode.STALE_DATA
                        : status == FreshnessStatus.UNKNOWN
                                ? ProviderErrorCode.SOURCE_FRESHNESS_UNKNOWN
                                : null;
        return new FreshnessView(
                refreshed, status, aggregateValidUntil, degradationCode, degradationCode == null);
    }

    private AdapterCityRegistry.ResolvedCity resolveCity(String cityId) {
        try {
            return cityRegistry.resolve(cityId);
        } catch (IllegalArgumentException exception) {
            throw new ProviderException(
                    ProviderKind.QWEATHER,
                    ProviderErrorCode.INVALID_REQUEST,
                    "标准城市未配置和风天气映射",
                    exception);
        }
    }

    private boolean isNegativeCacheable(ProviderErrorCode code) {
        return code == ProviderErrorCode.EMPTY_RESULT
                || code == ProviderErrorCode.INVALID_RESPONSE
                || code == ProviderErrorCode.DIRTY_DATA
                || code == ProviderErrorCode.FORECAST_WINDOW_OUTSIDE;
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Double parseDecimal(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String sourceId(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            byte[] bytes = digest.digest();
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                builder.append("%02x".formatted(current & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }

    private record WeatherLoad(
            List<WeatherRecord> records, boolean degraded, ProviderErrorCode degradationCode) {

        private WeatherLoad {
            records = List.copyOf(records);
            if (degraded != (degradationCode != null)) {
                throw new IllegalArgumentException("天气降级状态不一致");
            }
        }
    }

    private record FreshnessView(
            List<WeatherRecord> records,
            FreshnessStatus status,
            Instant validUntil,
            ProviderErrorCode degradationCode,
            boolean fresh) {

        private FreshnessView {
            records = List.copyOf(records);
        }
    }
}
