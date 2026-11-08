"""ALG-07 + ALG-14 per-day weather resolution: fresh cache -> port -> unavailable.

Auth/quota errors never write negative cache; stale forecast data is never returned as a
forecast (过期数据不得当预报). Stale climate statistics may still be served, flagged stale.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, date, datetime

from app.agents.weather.errors import WeatherErrorKind
from app.agents.weather.models import DailyWeather, DataType, WeatherPortError, WeatherPortResult
from app.agents.weather.ports import WeatherCache, WeatherPort

_NO_NEGATIVE_CACHE = frozenset({WeatherErrorKind.AUTH, WeatherErrorKind.QUOTA})


def weather_key(city_id: str, day: date, data_type: str, timezone: str) -> str:
    """Deterministic cache key for a single day. Exact format is PENDING (ALG-14)."""
    return f"weather:v1:{city_id}:{day.isoformat()}:{data_type}:{timezone}"


@dataclass(frozen=True)
class DayResult:
    """One day's resolution outcome: either data (fresh/stale) or a typed failure."""

    data: DailyWeather | None = None
    error_kind: WeatherErrorKind | None = None


class WeatherProvider:
    """Resolve a single day following ALG-07/ALG-14."""

    def __init__(
        self,
        port: WeatherPort,
        cache: WeatherCache,
        timezone: str,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self._port = port
        self._cache = cache
        self._timezone = timezone
        self._now = now if now is not None else lambda: datetime.now(UTC)

    def resolve(
        self,
        city_id: str,
        day: date,
        data_type: DataType,
    ) -> DayResult:
        key = weather_key(city_id, day, data_type, self._timezone)
        cached = self._cache.get(key)
        if cached is not None and self._is_fresh(cached):
            return DayResult(data=cached)

        result = self._fetch(data_type, city_id, day)
        if isinstance(result, DailyWeather):
            self._cache.put(key, result)
            return DayResult(data=result)

        error: WeatherPortError = result
        if error.kind in _NO_NEGATIVE_CACHE:
            return DayResult(error_kind=error.kind)

        # Climate statistics are stable and may be served from stale cache, flagged stale.
        # A stale forecast is never returned (过期数据不得当预报).
        if data_type == "climate_statistics" and cached is not None:
            return DayResult(data=cached.model_copy(update={"freshness_status": "stale"}))
        return DayResult(error_kind=error.kind)

    def _is_fresh(self, data: DailyWeather) -> bool:
        if data.freshness_status != "fresh":
            return False
        if data.valid_until is not None and data.valid_until <= self._now():
            return False
        return True

    def _fetch(self, data_type: DataType, city_id: str, day: date) -> WeatherPortResult:
        if data_type == "forecast":
            return self._port.forecast(city_id, day)
        return self._port.climate(city_id, day)
