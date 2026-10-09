package com.agenttravel.backend.adapter;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class MockQWeatherAdapter implements WeatherAdapter {

    private final String alias;
    private final ProviderPolicy policy;
    private final int actualForecastHorizonDays;
    private final Clock clock;
    private final ProviderCallExecutor calls;
    private final InMemoryProviderCache<List<WeatherRecord>> cache;

    public MockQWeatherAdapter(String alias, ProviderPolicy policy, int actualForecastHorizonDays, Clock clock,
            ProviderCallExecutor calls, InMemoryProviderCache<List<WeatherRecord>> cache) {
        if (actualForecastHorizonDays < 0) {
            throw new IllegalArgumentException("预报窗口不能为负数");
        }
        this.alias = alias;
        this.policy = policy;
        this.actualForecastHorizonDays = actualForecastHorizonDays;
        this.clock = clock;
        this.calls = calls;
        this.cache = cache;
    }

    @Override
    public ProviderResponse<List<WeatherRecord>> forecast(WeatherRequest request) {
        String key = AdapterCacheKeys.weather(policy.cacheVersion(), alias, request);
        Instant now = clock.instant();
        CacheLookup<List<WeatherRecord>> cached = cache.get(key, now);
        if (cached.present() && cached.fresh()) {
            if (cached.entry().negative()) {
                throw new ProviderException(ProviderKind.QWEATHER, cached.entry().negativeCode(), "天气无可用预报");
            }
            return cachedResponse(cached.entry(), now);
        }

        try {
            List<WeatherRecord> records = cache.singleFlight(key, () -> loadForecast(key, request));
            ProviderSource source = source(clock.instant(), request.cityId());
            return new ProviderResponse<>(records, source, false);
        } catch (ProviderException exception) {
            if (cached.present() && cached.stale() && !cached.entry().negative()) {
                cache.restore(key, cached.entry());
                return cachedResponse(cached.entry(), clock.instant()).asDegraded(exception.code());
            }
            throw exception;
        }
    }

    private List<WeatherRecord> loadForecast(String key, WeatherRequest request) {
        Instant now = clock.instant();
        try {
            List<WeatherRecord> records = calls.call(ProviderKind.QWEATHER, () -> buildForecast(request, now));
            cache.put(key, records, source(now, request.cityId()), now, policy.cacheTtl());
            return records;
        } catch (ProviderException exception) {
            if (exception.code() == ProviderErrorCode.EMPTY_RESULT
                    || exception.code() == ProviderErrorCode.INVALID_RESPONSE
                    || exception.code() == ProviderErrorCode.DIRTY_DATA) {
                cache.putNegative(key, exception.code(), now, policy.negativeCacheTtl());
            }
            throw exception;
        }
    }

    private ProviderSource source(Instant retrievedAt, String cityId) {
        return new ProviderSource(alias, "forecast-" + cityId, retrievedAt,
                retrievedAt.plus(policy.cacheTtl()), FreshnessStatus.FRESH, true);
    }

    private <T> ProviderResponse<T> cachedResponse(CacheEntry<T> entry, Instant now) {
        ProviderSource source = entry.source().refreshedStatus(now);
        return new ProviderResponse<>(entry.value(), source, true,
                source.freshnessStatus() != FreshnessStatus.FRESH, null);
    }

    private List<WeatherRecord> buildForecast(WeatherRequest request, Instant now) {
        LocalDate today = LocalDate.now(clock);
        LocalDate lastForecastDate = today.plusDays(actualForecastHorizonDays);
        List<WeatherRecord> records = new ArrayList<>();
        for (LocalDate date = request.from(); !date.isAfter(request.to()); date = date.plusDays(1)) {
            if (date.isBefore(today) || date.isAfter(lastForecastDate)) {
                continue;
            }
            records.add(new WeatherRecord(date, 18, 28, 0.2, "晴间多云", "forecast",
                    "qweather-" + request.cityId() + "-" + date, now, now.plus(policy.cacheTtl())));
        }
        if (records.isEmpty()) {
            throw new ProviderException(ProviderKind.QWEATHER, ProviderErrorCode.EMPTY_RESULT, "请求日期超出实际预报窗口");
        }
        return List.copyOf(records);
    }
}
