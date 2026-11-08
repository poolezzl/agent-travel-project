"""Weather agent orchestration tests (ALG-07 degradation + advice)."""

from __future__ import annotations

from datetime import date

from app.agents.weather.agent import run_weather
from app.agents.weather.errors import WeatherErrorKind
from app.agents.weather.mocks import MockWeatherPort, make_climate, make_forecast
from app.agents.weather.weather_provider import WeatherProvider, weather_key

from .conftest import CITY, FIXED_NOW, TZ, make_request


def _run(request, port, cache):
    provider = WeatherProvider(port, cache, TZ, now=lambda: FIXED_NOW)
    return run_weather(request, provider)


def test_mixed_forecast_climate_ok(port, cache) -> None:
    result = _run(make_request(), port, cache)

    assert result.status == "ok"
    assert [d.data_type for d in result.daily] == [
        "forecast",
        "forecast",
        "forecast",
        "climate_statistics",
    ]
    assert result.daily[-1].precip_prob is None  # climate stats: no reliable probability


def test_inverted_date_range_fails(port, cache) -> None:
    result = _run(make_request(start=date(2026, 9, 24), end=date(2026, 9, 21)), port, cache)

    assert result.status == "failed"
    assert result.error_code == "INVALID_INPUT"
    assert result.daily == []


def test_start_before_today_fails(port, cache) -> None:
    result = _run(make_request(start=date(2026, 9, 20), today=date(2026, 9, 21)), port, cache)

    assert result.status == "failed"
    assert result.error_code == "INVALID_INPUT"


def test_extreme_temp_risk_and_infeasible_point(cache) -> None:
    hot = make_forecast(CITY, date(2026, 9, 21)).model_copy(
        update={"temp_min": 28.0, "temp_max": 36.0}
    )
    port = MockWeatherPort(forecast={date(2026, 9, 21): hot})

    result = _run(make_request(sensitive_points=["热气球"]), port, cache)

    assert result.status == "ok"
    assert result.travel_advice is not None
    assert any(r.startswith("EXTREME_TEMP:") for r in result.travel_advice.risks)
    assert result.travel_advice.sensitive_points[0].feasible is False


def test_heavy_rain_risk(cache) -> None:
    rainy = make_forecast(CITY, date(2026, 9, 21)).model_copy(update={"precip_prob": 0.9})
    port = MockWeatherPort(forecast={date(2026, 9, 21): rainy})

    result = _run(make_request(), port, cache)

    assert result.status == "ok"
    assert result.travel_advice is not None
    assert any(r.startswith("HEAVY_RAIN:") for r in result.travel_advice.risks)


def test_single_day_failure_partial(port, cache) -> None:
    port.fail("forecast", date(2026, 9, 21), WeatherErrorKind.EMPTY)

    result = _run(make_request(), port, cache)

    assert result.status == "partial"
    assert "2026-09-21" in (result.message or "")
    assert len(result.daily) == 3


def test_all_days_timeout_failed(port, cache) -> None:
    port.fail_all(WeatherErrorKind.TIMEOUT)

    result = _run(make_request(), port, cache)

    assert result.status == "failed"
    assert result.error_code == "AGENT_TIMEOUT"
    assert result.message == "天气服务暂不可用"
    assert result.daily == []


def test_all_days_unreachable_failed_no_sunny_default(port, cache) -> None:
    port.fail_all(WeatherErrorKind.UNREACHABLE)

    result = _run(make_request(), port, cache)

    assert result.status == "failed"
    assert result.error_code == "SOURCE_UNAVAILABLE"
    assert result.message == "天气服务暂不可用"
    assert result.daily == []


def test_dirty_temp_min_gt_temp_max_fails_day(cache) -> None:
    dirty = make_forecast(CITY, date(2026, 9, 21)).model_copy(
        update={"temp_min": 25.0, "temp_max": 20.0}
    )
    port = MockWeatherPort(forecast={date(2026, 9, 21): dirty})

    result = _run(make_request(), port, cache)

    assert result.status == "partial"
    assert "2026-09-21" in (result.message or "")


def test_stale_climate_degraded(port, cache) -> None:
    stale_day = date(2026, 9, 24)
    cache.put(
        weather_key(CITY, stale_day, "climate_statistics", TZ),
        make_climate(CITY, stale_day).model_copy(update={"freshness_status": "stale"}),
    )
    port.fail("climate", stale_day, WeatherErrorKind.UNREACHABLE)

    result = _run(make_request(), port, cache)

    assert result.status == "degraded"
    assert result.degraded_fields == ["daily"]
    assert "2026-09-24" in (result.message or "")


def test_clothing_only_when_asked(port, cache) -> None:
    without = _run(make_request(ask_clothing=False), port, cache)
    with_clothing = _run(make_request(ask_clothing=True), port, cache)

    assert without.travel_advice is not None
    assert without.travel_advice.clothing is None
    assert with_clothing.travel_advice is not None
    assert with_clothing.travel_advice.clothing is not None


def test_same_input_twice_identical(port, cache) -> None:
    request = make_request(sensitive_points=["热气球"], ask_clothing=True)

    first = _run(request, port, cache)
    second = _run(request, port, cache)

    assert first.model_dump() == second.model_dump()
