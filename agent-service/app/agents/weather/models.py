"""Internal data models for the weather agent (S13).

``DailyWeather`` is the superset of the frozen ``agents/03-weather/output.schema.json``
``daily[]`` item; ``WeatherDaily`` is the exact output projection. ``TravelAdvice`` is frozen
by 2026-11-24's S12/S13 contract-gap closure: all four fields are required, unknown keys are
rejected, and risk codes are restricted to the same pattern as the JSON Schema.
"""

from __future__ import annotations

from datetime import date, datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.agents.weather.errors import WeatherErrorKind

DataType = Literal["forecast", "climate_statistics"]
RiskCode = Annotated[
    str,
    Field(
        pattern=(
            r"^(HEAVY_RAIN|EXTREME_TEMP|NON_FORECAST|DATA_MISSING):"
            r"\d{4}-\d{2}-\d{2}(,\d{4}-\d{2}-\d{2})*$"
        )
    ),
]


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

    model_config = ConfigDict(extra="forbid")

    point: str
    feasible: bool
    risks: list[RiskCode]


class TravelAdvice(BaseModel):
    """Frozen advice structure: derived from ``daily[]`` only, with no independent source."""

    model_config = ConfigDict(extra="forbid")

    sensitive_points: list[SensitivePointAdvice]
    risks: list[RiskCode]
    alternatives: list[date]
    clothing: str | None


class WeatherAgentResult(BaseModel):
    """Envelope-ready weather payload. System fields are injected by the gateway."""

    status: Literal["ok", "partial", "degraded", "failed"]
    message: str | None = None
    error_code: str | None = None
    degraded_fields: list[str] = Field(default_factory=list)
    daily: list[WeatherDaily] = Field(default_factory=list)
    travel_advice: TravelAdvice | None = None
