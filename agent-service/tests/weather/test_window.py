"""ALG-07 window determination and supplier-timezone/DST date helpers."""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

import pytest
from app.agents.weather.window import classify, days_ahead, enumerate_dates, local_date


def test_classify_boundary_within_forecast_window() -> None:
    today = date(2026, 9, 21)
    assert classify(today, today, 3) == "forecast"
    assert classify(today, today + timedelta(days=2), 3) == "forecast"
    assert classify(today, today + timedelta(days=3), 3) == "climate_statistics"


def test_classify_rejects_past_target() -> None:
    today = date(2026, 9, 21)
    with pytest.raises(ValueError):
        classify(today, today - timedelta(days=1), 3)


def test_days_ahead_across_month_and_year() -> None:
    assert days_ahead(date(2026, 9, 30), date(2026, 10, 1)) == 1
    assert days_ahead(date(2026, 12, 31), date(2027, 1, 1)) == 1
    assert days_ahead(date(2026, 2, 28), date(2026, 3, 1)) == 1


def test_enumerate_dates_cross_month_and_year() -> None:
    assert enumerate_dates(date(2026, 9, 30), date(2026, 10, 2)) == [
        date(2026, 9, 30),
        date(2026, 10, 1),
        date(2026, 10, 2),
    ]
    assert enumerate_dates(date(2026, 12, 31), date(2027, 1, 1)) == [
        date(2026, 12, 31),
        date(2027, 1, 1),
    ]


def test_local_date_dst_spring_forward() -> None:
    # New York springs forward 2026-03-08 at 07:00 UTC (02:00 EST -> 03:00 EDT).
    tz = "America/New_York"
    before = datetime(2026, 3, 8, 6, 0, tzinfo=UTC)
    after = datetime(2026, 3, 8, 7, 0, tzinfo=UTC)
    assert local_date(before, tz) == date(2026, 3, 8)
    assert local_date(after, tz) == date(2026, 3, 8)


def test_local_date_dst_fall_back() -> None:
    # New York falls back 2026-11-01 at 06:00 UTC (02:00 EDT -> 01:00 EST).
    tz = "America/New_York"
    before = datetime(2026, 11, 1, 5, 0, tzinfo=UTC)
    after = datetime(2026, 11, 1, 6, 0, tzinfo=UTC)
    assert local_date(before, tz) == date(2026, 11, 1)
    assert local_date(after, tz) == date(2026, 11, 1)


def test_local_date_shifts_utc_date_boundary() -> None:
    # 2026-01-01 04:00 UTC is still 2025-12-31 23:00 in New York (EST, -05:00).
    utc = datetime(2026, 1, 1, 4, 0, tzinfo=UTC)
    assert local_date(utc, "America/New_York") == date(2025, 12, 31)
