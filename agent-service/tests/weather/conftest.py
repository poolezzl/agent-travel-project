"""Shared fixtures for weather tests (deterministic, no network)."""

from __future__ import annotations

from datetime import date

import pytest
from app.agents.weather.mocks import FIXED_RETRIEVED_AT, MockWeatherCache, MockWeatherPort
from app.agents.weather.models import DateRange, WeatherPlanRequest
from app.agents.weather.weather_provider import WeatherProvider

CITY = "beijing"
TZ = "Asia/Shanghai"
HORIZON = 3
FIXED_NOW = FIXED_RETRIEVED_AT


@pytest.fixture
def port() -> MockWeatherPort:
    return MockWeatherPort()


@pytest.fixture
def cache() -> MockWeatherCache:
    return MockWeatherCache()


@pytest.fixture
def provider(port: MockWeatherPort, cache: MockWeatherCache) -> WeatherProvider:
    return WeatherProvider(port, cache, TZ, now=lambda: FIXED_NOW)


def make_request(
    *,
    start: date = date(2026, 9, 21),
    end: date = date(2026, 9, 24),
    today: date = date(2026, 9, 21),
    horizon: int = HORIZON,
    sensitive_points: list[str] | None = None,
    ask_clothing: bool = False,
    city_id: str = CITY,
) -> WeatherPlanRequest:
    return WeatherPlanRequest(
        city_id=city_id,
        date_range=DateRange(start=start, end=end),
        today=today,
        forecast_horizon_days=horizon,
        sensitive_points=sensitive_points or [],
        ask_clothing=ask_clothing,
    )
