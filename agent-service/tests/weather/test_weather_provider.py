"""Weather provider cache/freshness/negative-cache tests (ALG-07/ALG-14)."""

from __future__ import annotations

from datetime import date, timedelta

from app.agents.weather.errors import WeatherErrorKind
from app.agents.weather.mocks import (
    FIXED_RETRIEVED_AT,
    MockWeatherCache,
    MockWeatherPort,
    make_climate,
    make_forecast,
)
from app.agents.weather.weather_provider import WeatherProvider, weather_key

CITY = "beijing"
DAY = date(2026, 9, 21)
TZ = "Asia/Shanghai"


def _provider(port: MockWeatherPort, cache: MockWeatherCache) -> WeatherProvider:
    return WeatherProvider(port, cache, TZ, now=lambda: FIXED_RETRIEVED_AT + timedelta(hours=1))


def test_fresh_cache_short_circuits_port(port, cache) -> None:
    cache.put(weather_key(CITY, DAY, "forecast", TZ), make_forecast(CITY, DAY))
    provider = _provider(port, cache)

    result = provider.resolve(CITY, DAY, "forecast")

    assert result.data is not None
    assert result.data.data_type == "forecast"
    assert port.call_count == 0


def test_expired_forecast_refetches(port, cache) -> None:
    stale = make_forecast(CITY, DAY, retrieved_at=FIXED_RETRIEVED_AT - timedelta(days=2))
    cache.put(weather_key(CITY, DAY, "forecast", TZ), stale)
    provider = _provider(port, cache)

    result = provider.resolve(CITY, DAY, "forecast")

    assert result.data is not None
    assert result.data.retrieved_at == FIXED_RETRIEVED_AT
    assert port.call_count == 1


def test_stale_climate_served_flagged(port, cache) -> None:
    stale = make_climate(CITY, DAY).model_copy(update={"freshness_status": "stale"})
    cache.put(weather_key(CITY, DAY, "climate_statistics", TZ), stale)
    port.fail_all(WeatherErrorKind.UNREACHABLE)
    provider = _provider(port, cache)

    result = provider.resolve(CITY, DAY, "climate_statistics")

    assert result.data is not None
    assert result.data.freshness_status == "stale"


def test_stale_forecast_never_served(port, cache) -> None:
    stale = make_forecast(CITY, DAY, retrieved_at=FIXED_RETRIEVED_AT - timedelta(days=2))
    cache.put(weather_key(CITY, DAY, "forecast", TZ), stale)
    port.fail_all(WeatherErrorKind.UNREACHABLE)
    provider = _provider(port, cache)

    result = provider.resolve(CITY, DAY, "forecast")

    assert result.data is None
    assert result.error_kind == WeatherErrorKind.UNREACHABLE


def test_auth_quota_do_not_write_negative_cache(port, cache) -> None:
    for kind in (WeatherErrorKind.AUTH, WeatherErrorKind.QUOTA):
        port.fail_all(kind)
        provider = _provider(port, cache)

        result = provider.resolve(CITY, DAY, "forecast")

        assert result.data is None
        assert result.error_kind == kind
        assert weather_key(CITY, DAY, "forecast", TZ) not in cache


def test_success_writes_cache(port, cache) -> None:
    provider = _provider(port, cache)

    provider.resolve(CITY, DAY, "forecast")

    assert weather_key(CITY, DAY, "forecast", TZ) in cache
