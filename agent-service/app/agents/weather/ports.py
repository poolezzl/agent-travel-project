"""Port interfaces (Protocols) the weather agent depends on (S10 contract surface).

The real S10 weather adapter is owned by R5 and is not present on dev-r2. These Protocols
define the dependency surface S13 codes against; deterministic Mocks live in ``mocks.py``.
Real adapter wiring is PENDING-R5.
"""

from __future__ import annotations

from datetime import date
from typing import Protocol

from app.agents.weather.models import DailyWeather, WeatherPortResult


class WeatherPort(Protocol):
    """Weather port (S10): forecast for in-window dates, climate stats for out-of-window."""

    def forecast(self, city_id: str, day: date) -> WeatherPortResult: ...

    def climate(self, city_id: str, day: date) -> WeatherPortResult: ...


class WeatherCache(Protocol):
    """Weather cache port (S10 / ADR-0006): exact fresh cache, original source time."""

    def get(self, key: str) -> DailyWeather | None: ...

    def put(self, key: str, data: DailyWeather) -> None: ...
