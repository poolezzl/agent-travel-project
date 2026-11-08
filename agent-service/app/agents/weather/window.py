"""ALG-07 weather-window determination and supplier-timezone date helpers."""

from __future__ import annotations

from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from app.agents.weather.models import DataType


def days_ahead(today: date, target: date) -> int:
    """Calendar-day offset of ``target`` relative to the system-injected ``today``."""
    return (target - today).days


def classify(today: date, target: date, horizon_days: int) -> DataType:
    """ALG-07 split: ``0 <= days_ahead < horizon`` -> forecast, ``>= horizon`` -> climate."""
    offset = days_ahead(today, target)
    if offset < 0:
        raise ValueError(f"target {target.isoformat()} precedes today {today.isoformat()}")
    if offset < horizon_days:
        return "forecast"
    return "climate_statistics"


def enumerate_dates(start: date, end: date) -> list[date]:
    """All dates in ``[start, end]`` inclusive, in calendar order."""
    result: list[date] = []
    current = start
    while current <= end:
        result.append(current)
        current += timedelta(days=1)
    return result


def local_date(utc: datetime, tz_name: str) -> date:
    """Supplier-local calendar date for a UTC instant (DST-aware, timezone injected)."""
    return utc.astimezone(ZoneInfo(tz_name)).date()
