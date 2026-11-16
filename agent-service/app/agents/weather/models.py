"""Internal data models for the weather agent (S13).

``DailyWeather`` is the superset of the frozen ``agents/03-weather/output.schema.json``
``daily[]`` item; ``WeatherDaily`` is the exact output projection. The ``travel_advice``
structure is not frozen, so ``TravelAdvice`` is a documented internal model (PENDING-R2/R1).
"""

from __future__ import annotations

from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel, Field, model_validator

from app.agents.weather.errors import WeatherErrorKind

DataType = Literal["forecast", "climate_statistics"]


class DateRange(BaseModel):
    """Inclusive trip date range."""

    start: date
    end: date


class WeatherPlanRequest(BaseModel):
    """Internal weather request (superset of ``agents/03-weather/input.schema.json``)."""

    city_id: str
    date_range: DateRange
    today: date
    forecast_horizon_days: int = Field(ge=0)
    sensitive_points: list[str] = Field(default_factory=list)
    # Internal (PENDING-R1): the frozen input schema has no clothing-advice switch; upstream
    # passes it via internal task context. Clothing advice is only produced when True.
    ask_clothing: bool = False


class DailyWeather(BaseModel):
    """One day's weather as returned by the weather port (superset of output daily item)."""

    city_id: str
    date: date
    temp_min: float
    temp_max: float
    precip_prob: float | None = None
    condition: str
    data_type: DataType
    source_record_id: str
    retrieved_at: datetime
    valid_until: datetime | None = None
    freshness_status: Literal["fresh", "stale", "unknown"] = "fresh"
    forecast_published_at: datetime | None = None


class WeatherPortError(BaseModel):
    """Typed failure returned by the weather port."""

    kind: WeatherErrorKind
    message: str


WeatherPortResult = DailyWeather | WeatherPortError


class WeatherDaily(BaseModel):
    """Output ``daily[]`` item (exactly the frozen output-schema fields)."""

    date: date
    temp_min: float
    temp_max: float
    precip_prob: float | None = Field(default=None, ge=0.0, le=1.0)
    condition: str
    data_type: DataType
    source_record_id: str
    retrieved_at: datetime
    valid_until: datetime | None = None
    forecast_published_at: datetime | None = None

    @model_validator(mode="after")
    def _check_temps(self) -> WeatherDaily:
        if self.temp_min > self.temp_max:
            raise ValueError("temp_min must be <= temp_max")
        return self


class SensitivePointAdvice(BaseModel):
    """Feasibility judgement for one weather-sensitive point."""

    point: str
    feasible: bool
    risks: list[str] = Field(default_factory=list)


class TravelAdvice(BaseModel):
    """Documented internal advice model (structure is PENDING-R2/R1)."""

    sensitive_points: list[SensitivePointAdvice] = Field(default_factory=list)
    risks: list[str] = Field(default_factory=list)
    alternatives: list[date] = Field(default_factory=list)
    clothing: str | None = None


class WeatherAgentResult(BaseModel):
    """Envelope-ready weather payload. System fields are injected by the gateway."""

    status: Literal["ok", "partial", "degraded", "failed"]
    message: str | None = None
    error_code: str | None = None
    degraded_fields: list[str] = Field(default_factory=list)
    daily: list[WeatherDaily] = Field(default_factory=list)
    travel_advice: TravelAdvice | None = None
