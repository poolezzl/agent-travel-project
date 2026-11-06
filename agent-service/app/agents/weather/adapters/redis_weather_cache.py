"""Redis weather cache implementing ``WeatherCache`` (ALG-14).

Key format is owned by ``weather_provider.weather_key``:
``weather:v1:{city_id}:{day}:{data_type}:{timezone}`` (the caller supplies it verbatim).
ALG-14 also requires the supplier version, which ``weather_key`` does not carry (format
PENDING-R5); the adapter namespaces it with ``key_prefix``, e.g.
``qweather:weather:v1:...``.

Retention rules:

- **Forecast**: never replayed once expired (过期预报绝不回放). The stored entry is dropped at
  ``valid_until`` (end of supplier day at the latest) and ``get`` additionally refuses an
  entry whose ``valid_until`` has passed — a stale forecast is indistinguishable from a miss.
- **Climate statistics**: retained longer than their validity window
  (``climate_retention_seconds``) so the ALG-07 ladder can serve the old value flagged
  ``stale`` when the supplier is unreachable.
- **AUTH/QUOTA never write a negative cache**: this cache only ever stores a successfully
  fetched ``DailyWeather``, so there is no negative entry to write.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime
from typing import Protocol

from pydantic import ValidationError

from app.agents.weather.models import DailyWeather


class WeatherStore(Protocol):
    """Minimal key/value backend the cache runs against (Redis or a test double)."""

    def get(self, key: str) -> str | None: ...
    def setex(self, key: str, ttl_seconds: int, value: str) -> None: ...


class RedisWeatherCache:
    def __init__(
        self,
        store: WeatherStore,
        ttl_seconds: int,
        climate_retention_seconds: int | None = None,
        now: Callable[[], datetime] | None = None,
        key_prefix: str = "qweather",
    ) -> None:
        self._store = store
        self._ttl_seconds = ttl_seconds
        self._climate_retention_seconds = climate_retention_seconds or ttl_seconds
        self._now = now if now is not None else lambda: datetime.now(UTC)
        self._key_prefix = key_prefix

    def _full_key(self, key: str) -> str:
        # ALG-14 requires the supplier version in the cache key. ``weather_key`` (ALG-07) does
        # not carry it yet (format PENDING-R5), so the adapter namespaces it here.
        return f"{self._key_prefix}:{key}"

    def get(self, key: str) -> DailyWeather | None:
        raw = self._store.get(self._full_key(key))
        if raw is None:
            return None
        try:
            data = DailyWeather.model_validate_json(raw)
        except ValidationError:
            return None
        if data.data_type == "forecast" and _expired(data, self._now()):
            # Defence in depth: even if the backend clock lags, an expired forecast is a miss.
            return None
        return data

    def put(self, key: str, data: DailyWeather) -> None:
        ttl = self._ttl_for(data)
        if ttl <= 0:
            return
        self._store.setex(self._full_key(key), ttl, data.model_dump_json())

    def _ttl_for(self, data: DailyWeather) -> int:
        if data.data_type == "climate_statistics":
            return self._climate_retention_seconds
        if data.valid_until is None:
            return self._ttl_seconds
        remaining = int((data.valid_until - self._now()).total_seconds())
        return min(self._ttl_seconds, remaining)


def _expired(data: DailyWeather, now: datetime) -> bool:
    return data.valid_until is not None and data.valid_until <= now
