"""Composition root wiring the weather-agent dependencies (mock or QWeather).

Reads ``WEATHER_PROVIDER`` / cache TTLs / supplier tier / timezone from ``app.core.config``.
Returns ``(weather_port, cache)``; the ALG-07 orchestration stays in ``weather_provider.py`` and
``run_weather(request, provider)`` is unchanged. Adapters never decide what "today" is and
never hard-code the supplier timezone — both are injected here from configuration.
"""

from __future__ import annotations

import httpx

from app.agents.weather.adapters.cities import CityRegistry
from app.agents.weather.adapters.qweather_adapter import QWeatherAdapter
from app.agents.weather.adapters.redis_weather_cache import RedisWeatherCache, WeatherStore
from app.agents.weather.mocks import MockWeatherCache, MockWeatherPort
from app.agents.weather.ports import WeatherCache, WeatherPort
from app.core.config import get_weather_settings


def build_weather_deps(
    store: WeatherStore | None = None,
    client: httpx.Client | None = None,
) -> tuple[WeatherPort, WeatherCache]:
    """Build ``(weather_port, cache)`` for the configured ``WEATHER_PROVIDER``.

    ``store``/``client`` are injection points for tests (Redis double, MockTransport client);
    in production both are omitted and created from :class:`WeatherAdapterSettings`.
    """
    settings = get_weather_settings()

    if settings.weather_provider == "mock":
        return MockWeatherPort(), MockWeatherCache()

    if client is None:
        client = httpx.Client(
            base_url=settings.qweather_api_host,
            timeout=settings.qweather_timeout_seconds,
        )

    port = QWeatherAdapter(
        client=client,
        api_key=settings.qweather_api_key,
        city_registry=CityRegistry.from_json(settings.qweather_city_map),
        zone_id=settings.qweather_zone_id,
        forecast_days=settings.qweather_forecast_days,
        alias=settings.qweather_alias,
        cache_ttl_seconds=settings.weather_cache_ttl_seconds,
    )

    if store is None:
        return port, MockWeatherCache()

    cache = RedisWeatherCache(
        store,
        ttl_seconds=settings.weather_cache_ttl_seconds,
        climate_retention_seconds=settings.weather_climate_retention_seconds,
        key_prefix=settings.qweather_alias,
    )
    return port, cache
