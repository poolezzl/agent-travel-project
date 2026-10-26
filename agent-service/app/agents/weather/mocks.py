"""Deterministic mock weather adapters for tests (NOT the real S10 adapters).

Byte-identical outputs on repeated runs are guaranteed by fixed ``retrieved_at`` timestamps
and a seed-free, value-override table (no randomness).
"""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

from app.agents.weather.errors import WeatherErrorKind
from app.agents.weather.models import DailyWeather, WeatherPortError, WeatherPortResult

FIXED_RETRIEVED_AT = datetime(2026, 9, 21, 8, 0, 0, tzinfo=UTC)


def make_forecast(
    city_id: str,
    day: date,
    retrieved_at: datetime = FIXED_RETRIEVED_AT,
) -> DailyWeather:
    """Default in-window forecast record (mild, dry)."""
    return DailyWeather(
        city_id=city_id,
        date=day,
        temp_min=10.0,
        temp_max=20.0,
        precip_prob=0.1,
        condition="多云",
        data_type="forecast",
        source_record_id=f"fc:{city_id}:{day.isoformat()}",
        retrieved_at=retrieved_at,
        valid_until=retrieved_at + timedelta(hours=24),
        freshness_status="fresh",
    )


def make_climate(
    city_id: str,
    day: date,
    retrieved_at: datetime = FIXED_RETRIEVED_AT,
) -> DailyWeather:
    """Default out-of-window climate statistics record (precip_prob intentionally null)."""
    return DailyWeather(
        city_id=city_id,
        date=day,
        temp_min=5.0,
        temp_max=15.0,
        precip_prob=None,
        condition="气候统计（非预报）",
        data_type="climate_statistics",
        source_record_id=f"cl:{city_id}:{day.isoformat()}",
        retrieved_at=retrieved_at,
        valid_until=None,
        freshness_status="fresh",
    )


class MockWeatherPort:
    """Value-override forecast/climate port with scriptable failures and a call counter."""

    def __init__(
        self,
        forecast: dict[date, DailyWeather] | None = None,
        climate: dict[date, DailyWeather] | None = None,
        retrieved_at: datetime = FIXED_RETRIEVED_AT,
    ) -> None:
        self._forecast = forecast or {}
        self._climate = climate or {}
        self._retrieved_at = retrieved_at
        self._failures: dict[tuple[str, date], WeatherErrorKind] = {}
        self._fail_all: WeatherErrorKind | None = None
        self.call_count = 0

    def fail(self, data_type: str, day: date, kind: WeatherErrorKind) -> None:
        """Script a failure for one (data_type, day) slot."""
        self._failures[(data_type, day)] = kind

    def fail_all(self, kind: WeatherErrorKind) -> None:
        """Script a failure for every slot that is not individually overridden."""
        self._fail_all = kind

    def forecast(self, city_id: str, day: date) -> WeatherPortResult:
        self.call_count += 1
        kind = self._failures.get(("forecast", day), self._fail_all)
        if kind is not None:
            return WeatherPortError(kind=kind, message=f"mock {kind.value}")
        return self._forecast.get(day) or make_forecast(city_id, day, self._retrieved_at)

    def climate(self, city_id: str, day: date) -> WeatherPortResult:
        self.call_count += 1
        kind = self._failures.get(("climate", day), self._fail_all)
        if kind is not None:
            return WeatherPortError(kind=kind, message=f"mock {kind.value}")
        return self._climate.get(day) or make_climate(city_id, day, self._retrieved_at)


class MockWeatherCache:
    """In-memory cache implementing the WeatherCache protocol."""

    def __init__(self) -> None:
        self._store: dict[str, DailyWeather] = {}

    def get(self, key: str) -> DailyWeather | None:
        return self._store.get(key)

    def put(self, key: str, data: DailyWeather) -> None:
        self._store[key] = data

    def __contains__(self, key: str) -> bool:
        return key in self._store
