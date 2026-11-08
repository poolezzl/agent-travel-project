"""Weather agent orchestration: ALG-07 window split -> resolve -> degrade -> advice."""

from __future__ import annotations

from app.agents.weather.advice import build_advice
from app.agents.weather.models import (
    DailyWeather,
    DataType,
    WeatherAgentResult,
    WeatherDaily,
    WeatherPlanRequest,
)
from app.agents.weather.weather_provider import WeatherProvider
from app.agents.weather.window import classify, enumerate_dates


def run_weather(request: WeatherPlanRequest, provider: WeatherProvider) -> WeatherAgentResult:
    """Run ALG-07 for a trip date range and degrade deterministically."""
    invalid = _validate_request(request)
    if invalid is not None:
        return invalid

    days = enumerate_dates(request.date_range.start, request.date_range.end)

    daily: list[WeatherDaily] = []
    failed_dates: list[str] = []
    stale_dates: list[str] = []
    timeout_count = 0

    for day in days:
        data_type = classify(request.today, day, request.forecast_horizon_days)
        result = provider.resolve(request.city_id, day, data_type)

        if result.data is None:
            failed_dates.append(day.isoformat())
            if result.error_kind is not None and result.error_kind.value == "TIMEOUT":
                timeout_count += 1
            continue

        output = _sanitize(result.data, data_type)
        if output is None:
            failed_dates.append(day.isoformat())
            continue

        if result.data.freshness_status == "stale":
            stale_dates.append(day.isoformat())
        daily.append(output)

    if not daily:
        error_code = "AGENT_TIMEOUT" if timeout_count == len(days) else "SOURCE_UNAVAILABLE"
        return _failed(error_code, "天气服务暂不可用")

    advice = build_advice(daily, request.sensitive_points, request.ask_clothing, failed_dates)

    if failed_dates:
        return WeatherAgentResult(
            status="partial",
            message=f"部分日期天气不可用: {', '.join(failed_dates)}",
            degraded_fields=["daily"],
            daily=daily,
            travel_advice=advice,
        )

    if stale_dates:
        return WeatherAgentResult(
            status="degraded",
            message=f"以下日期数据已过期: {', '.join(stale_dates)}",
            degraded_fields=["daily"],
            daily=daily,
            travel_advice=advice,
        )

    return WeatherAgentResult(status="ok", daily=daily, travel_advice=advice)


def _validate_request(request: WeatherPlanRequest) -> WeatherAgentResult | None:
    if request.date_range.start > request.date_range.end:
        return _failed("INVALID_INPUT", "date_range.start 必须 <= date_range.end")
    if request.date_range.start < request.today:
        return _failed("INVALID_INPUT", "date_range 不得早于 today")
    return None


def _sanitize(data: DailyWeather, expected_type: DataType) -> WeatherDaily | None:
    """Reject dirty port output; project the internal record to the frozen output item."""
    if data.data_type != expected_type:
        return None
    if data.temp_min > data.temp_max:
        return None
    if data.precip_prob is not None and not (0.0 <= data.precip_prob <= 1.0):
        return None
    return WeatherDaily(
        date=data.date,
        temp_min=data.temp_min,
        temp_max=data.temp_max,
        precip_prob=data.precip_prob,
        condition=data.condition,
        data_type=data.data_type,
        source_record_id=data.source_record_id,
        retrieved_at=data.retrieved_at,
        valid_until=data.valid_until,
    )


def _failed(error_code: str, message: str) -> WeatherAgentResult:
    return WeatherAgentResult(status="failed", error_code=error_code, message=message)
