"""Sensitive-activity feasibility and machine-readable weather risks (ALG-07 step 3).

Advice only references already-returned daily facts; it never promises safety and never
invents brands. Risk thresholds live in ``config.py`` (PENDING-R2/R3).
"""

from __future__ import annotations

from datetime import date

from app.agents.weather.config import (
    EXTREME_TEMP_MAX_C,
    EXTREME_TEMP_MIN_C,
    HEAVY_RAIN_PRECIP_PROB,
)
from app.agents.weather.models import SensitivePointAdvice, TravelAdvice, WeatherDaily

RISK_HEAVY_RAIN = "HEAVY_RAIN"
RISK_EXTREME_TEMP = "EXTREME_TEMP"
RISK_NON_FORECAST = "NON_FORECAST"
RISK_DATA_MISSING = "DATA_MISSING"


def build_advice(
    daily: list[WeatherDaily],
    sensitive_points: list[str],
    ask_clothing: bool,
    missing_dates: list[str],
) -> TravelAdvice:
    """Build machine-readable advice from already-returned facts only."""
    severe = _severe_risks(daily)
    risks = severe + _non_forecast_risks(daily)
    if missing_dates:
        risks.append(f"{RISK_DATA_MISSING}:{','.join(missing_dates)}")

    risky_dates = _risky_dates(daily)
    point_advice = [_judge_point(point, severe, missing_dates) for point in sensitive_points]

    alternatives = [d.date for d in daily if d.date not in risky_dates] if risky_dates else []
    clothing = _clothing(daily) if ask_clothing else None

    return TravelAdvice(
        sensitive_points=point_advice,
        risks=risks,
        alternatives=alternatives,
        clothing=clothing,
    )


def _severe_risks(daily: list[WeatherDaily]) -> list[str]:
    risks: list[str] = []
    for day in daily:
        if day.precip_prob is not None and day.precip_prob >= HEAVY_RAIN_PRECIP_PROB:
            risks.append(f"{RISK_HEAVY_RAIN}:{day.date.isoformat()}")
        if day.temp_min <= EXTREME_TEMP_MIN_C or day.temp_max >= EXTREME_TEMP_MAX_C:
            risks.append(f"{RISK_EXTREME_TEMP}:{day.date.isoformat()}")
    return risks


def _non_forecast_risks(daily: list[WeatherDaily]) -> list[str]:
    return [
        f"{RISK_NON_FORECAST}:{day.date.isoformat()}"
        for day in daily
        if day.data_type == "climate_statistics"
    ]


def _risky_dates(daily: list[WeatherDaily]) -> set[date]:
    result: set[date] = set()
    for day in daily:
        if day.precip_prob is not None and day.precip_prob >= HEAVY_RAIN_PRECIP_PROB:
            result.add(day.date)
        if day.temp_min <= EXTREME_TEMP_MIN_C or day.temp_max >= EXTREME_TEMP_MAX_C:
            result.add(day.date)
    return result


def _judge_point(
    point: str,
    severe: list[str],
    missing_dates: list[str],
) -> SensitivePointAdvice:
    risks = list(severe)
    feasible = not severe and not missing_dates
    if missing_dates:
        risks.append(f"{RISK_DATA_MISSING}:{','.join(missing_dates)}")
    return SensitivePointAdvice(point=point, feasible=feasible, risks=risks)


def _clothing(daily: list[WeatherDaily]) -> str:
    hottest = max(daily, key=lambda d: d.temp_max)
    coldest = min(daily, key=lambda d: d.temp_min)
    return (
        f"行程期内最低约 {coldest.temp_min:.0f}°C、最高约 {hottest.temp_max:.0f}°C，"
        "建议按当天气温增减衣物。"
    )
