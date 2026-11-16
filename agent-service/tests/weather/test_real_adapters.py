"""Real QWeather adapter + cache integration tests (S13 Step 4).

Every HTTP call is served by ``httpx.MockTransport`` — the suite never contacts a real
supplier. Inputs and expected outputs are fixed explicitly per case.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, date, datetime, timedelta

import httpx
import pytest
from app.agents.weather.adapters import CityRegistry, QWeatherAdapter, RedisWeatherCache
from app.agents.weather.agent import run_weather
from app.agents.weather.errors import WeatherErrorKind
from app.agents.weather.models import DailyWeather, DateRange, WeatherPlanRequest
from app.agents.weather.weather_provider import WeatherProvider, weather_key
from app.agents.weather.worker import run_weather_step

BASE_URL = "https://devapi.qweather.com"
TZ = "Asia/Shanghai"
CITY = "beijing"
TODAY = date(2026, 9, 21)
FIXED_NOW = datetime(2026, 9, 21, 8, 0, 0, tzinfo=UTC)
TTL = 3600
RETENTION = 30 * 24 * 3600

Handler = Callable[[httpx.Request], httpx.Response]


def fc(day: str, tmin: float, tmax: float, pop: str = "20", text: str = "多云") -> dict[str, str]:
    return {
        "fxDate": day,
        "tempMin": str(tmin),
        "tempMax": str(tmax),
        "textDay": text,
        "pop": pop,
    }


def climate_day(day: str, tmin: float, tmax: float, text: str = "晴") -> dict[str, str]:
    return {"date": day, "tempMin": str(tmin), "tempMax": str(tmax), "textDay": text}


def handler(
    *,
    forecast: list[dict[str, str]] | None = None,
    climate: list[dict[str, str]] | None = None,
    status: int = 200,
    body: object | None = None,
    exc: Exception | None = None,
    raw: str | None = None,
    calls: list[str] | None = None,
) -> Handler:
    def handle(request: httpx.Request) -> httpx.Response:
        if calls is not None:
            calls.append(request.url.path)
        if request.url.path == "/v7/historical/weather":
            return httpx.Response(200, json={"code": "200", "daily": climate or []})
        if request.url.path.startswith("/v7/weather/"):
            if exc is not None:
                raise exc
            if raw is not None:
                return httpx.Response(status, text=raw)
            if body is not None:
                return httpx.Response(status, json=body)
            return httpx.Response(status, json={"code": "200", "daily": forecast or []})
        return httpx.Response(404, json={"code": "404"})

    return handle


def adapter(
    h: Handler,
    *,
    zone: str = TZ,
    days: int = 3,
    cities: dict[str, str] | None = None,
    now: datetime = FIXED_NOW,
    ttl: int = TTL,
) -> QWeatherAdapter:
    client = httpx.Client(base_url=BASE_URL, transport=httpx.MockTransport(h))
    registry = CityRegistry(cities) if cities is not None else CityRegistry()
    return QWeatherAdapter(
        client,
        "test-key",
        registry,
        zone,
        days,
        cache_ttl_seconds=ttl,
        now=lambda: now,
    )


class FakeStore:
    """Redis double honouring ``setex`` TTL against an injectable clock."""

    def __init__(self, clock: list[datetime]) -> None:
        self._clock = clock
        self._data: dict[str, tuple[str, datetime]] = {}

    def get(self, key: str) -> str | None:
        entry = self._data.get(key)
        if entry is None:
            return None
        value, expires_at = entry
        if self._clock[0] >= expires_at:
            del self._data[key]
            return None
        return value

    def setex(self, key: str, ttl_seconds: int, value: str) -> None:
        self._data[key] = (value, self._clock[0] + timedelta(seconds=ttl_seconds))

    @property
    def keys(self) -> list[str]:
        now = self._clock[0]
        return [key for key, (_, expires_at) in self._data.items() if now < expires_at]


def provider_for(
    port: QWeatherAdapter,
    clock: list[datetime],
    *,
    timezone: str = TZ,
) -> tuple[WeatherProvider, RedisWeatherCache, FakeStore]:
    store = FakeStore(clock)
    cache = RedisWeatherCache(
        store,
        ttl_seconds=TTL,
        climate_retention_seconds=RETENTION,
        now=lambda: clock[0],
    )
    return WeatherProvider(port, cache, timezone, now=lambda: clock[0]), cache, store


def request(*, start: date, end: date, today: date = TODAY, horizon: int = 3) -> WeatherPlanRequest:
    return WeatherPlanRequest(
        city_id=CITY,
        date_range=DateRange(start=start, end=end),
        today=today,
        forecast_horizon_days=horizon,
        sensitive_points=["天安门"],
    )


# ----------------------------------------------------------- window routing


def test_forecast_in_window_returns_forecast_type() -> None:
    calls: list[str] = []
    port = adapter(handler(forecast=[fc("2026-09-21", 15, 24)], calls=calls))

    result = port.forecast(CITY, date(2026, 9, 21))

    assert isinstance(result, DailyWeather)
    assert result.data_type == "forecast"
    assert result.temp_min == 15.0
    assert result.temp_max == 24.0
    assert result.precip_prob == 0.2
    assert result.condition == "多云"
    assert result.city_id == CITY
    assert calls == ["/v7/weather/3d"]
    assert port.forecast_horizon_days == 3
    assert port.timezone == TZ


def test_forecast_maps_supplier_update_time() -> None:
    body = {
        "code": "200",
        "updateTime": "2026-09-21T08:00+08:00",
        "daily": [fc("2026-09-21", 15, 24)],
    }
    port = adapter(handler(body=body))

    result = port.forecast(CITY, date(2026, 9, 21))

    assert isinstance(result, DailyWeather)
    assert result.forecast_published_at == datetime(2026, 9, 21, 0, 0, tzinfo=UTC)


def test_forecast_tier_drives_endpoint_not_hardcoded() -> None:
    calls: list[str] = []
    port = adapter(handler(forecast=[fc("2026-09-21", 15, 24)], calls=calls), days=7)

    port.forecast(CITY, date(2026, 9, 21))

    assert calls == ["/v7/weather/7d"]
    assert port.forecast_horizon_days == 7


def test_out_of_window_uses_climate_endpoint() -> None:
    calls: list[str] = []
    port = adapter(handler(climate=[climate_day("2026-09-25", 12, 22)], calls=calls))

    result = port.climate(CITY, date(2026, 9, 25))

    assert isinstance(result, DailyWeather)
    assert result.data_type == "climate_statistics"
    assert result.precip_prob is None
    assert calls == ["/v7/historical/weather"]


def test_agent_switches_endpoint_at_window_boundary() -> None:
    calls: list[str] = []
    port = adapter(
        handler(
            forecast=[
                fc("2026-09-21", 15, 24),
                fc("2026-09-22", 16, 25),
                fc("2026-09-23", 17, 26),
            ],
            climate=[climate_day("2026-09-24", 12, 21)],
            calls=calls,
        )
    )
    clock = [FIXED_NOW]
    provider, _, _ = provider_for(port, clock)

    result = run_weather(request(start=TODAY, end=date(2026, 9, 24)), provider)

    assert result.status == "ok"
    assert [item.data_type for item in result.daily] == [
        "forecast",
        "forecast",
        "forecast",
        "climate_statistics",
    ]
    assert result.daily[3].precip_prob is None
    assert "/v7/weather/3d" in calls
    assert "/v7/historical/weather" in calls


@pytest.mark.parametrize(
    ("zone", "retrieved_at", "day", "expected_day_end"),
    [
        # New York springs forward on 2026-03-08 (EST -> EDT), so 03-09 00:00 is 04:00Z.
        (
            "America/New_York",
            datetime(2026, 3, 8, 6, 0, tzinfo=UTC),
            date(2026, 3, 8),
            datetime(2026, 3, 9, 4, 0, tzinfo=UTC),
        ),
        # New York falls back on 2026-11-01 (EDT -> EST), so 11-02 00:00 is 05:00Z.
        (
            "America/New_York",
            datetime(2026, 11, 1, 5, 0, tzinfo=UTC),
            date(2026, 11, 1),
            datetime(2026, 11, 2, 5, 0, tzinfo=UTC),
        ),
    ],
)
def test_supplier_timezone_dst_day_end(
    zone: str,
    retrieved_at: datetime,
    day: date,
    expected_day_end: datetime,
) -> None:
    port = adapter(
        handler(forecast=[fc(day.isoformat(), 10, 18)]),
        zone=zone,
        cities={"new_york": "NY_LOC"},
        now=retrieved_at,
        ttl=7 * 24 * 3600,
    )

    result = port.forecast("new_york", day)

    assert isinstance(result, DailyWeather)
    assert result.valid_until == expected_day_end


def test_supplier_timezone_cross_month_day_end() -> None:
    port = adapter(
        handler(forecast=[fc("2026-09-30", 10, 18)]),
        now=datetime(2026, 9, 30, 12, 0, tzinfo=UTC),
        ttl=7 * 24 * 3600,
    )

    result = port.forecast(CITY, date(2026, 9, 30))

    assert isinstance(result, DailyWeather)
    # 2026-10-01 00:00 Asia/Shanghai (UTC+8) == 2026-09-30T16:00Z.
    assert result.valid_until == datetime(2026, 9, 30, 16, 0, tzinfo=UTC)


# ------------------------------------------------------------------ cache


def test_cache_hit_avoids_http_and_preserves_retrieved_at() -> None:
    calls: list[str] = []
    port = adapter(handler(forecast=[fc("2026-09-21", 15, 24)], calls=calls))
    clock = [FIXED_NOW]
    provider, _, _ = provider_for(port, clock)

    first = run_weather(request(start=TODAY, end=TODAY), provider)
    calls_after_first = list(calls)
    second = run_weather(request(start=TODAY, end=TODAY), provider)

    assert calls == calls_after_first
    assert first.daily == second.daily
    assert first.daily[0].retrieved_at == FIXED_NOW


def test_expired_forecast_is_not_replayed() -> None:
    port = adapter(handler(forecast=[]))
    clock = [FIXED_NOW]
    provider, cache, store = provider_for(port, clock)
    key = weather_key(CITY, TODAY, "forecast", TZ)
    cache.put(
        key,
        DailyWeather(
            city_id=CITY,
            date=TODAY,
            temp_min=15.0,
            temp_max=24.0,
            precip_prob=0.2,
            condition="多云",
            data_type="forecast",
            source_record_id="qweather:old",
            retrieved_at=FIXED_NOW,
            valid_until=FIXED_NOW + timedelta(hours=1),
            freshness_status="fresh",
        ),
    )
    clock[0] = FIXED_NOW + timedelta(hours=2)

    assert cache.get(key) is None
    result = run_weather(request(start=TODAY, end=TODAY), provider)

    assert result.status == "failed"
    assert result.message == "天气服务暂不可用"
    assert result.daily == []


def test_expired_climate_is_replayed_as_stale() -> None:
    port = adapter(handler(status=503))
    clock = [FIXED_NOW]
    provider, cache, store = provider_for(port, clock)
    key = weather_key(CITY, date(2026, 9, 25), "climate_statistics", TZ)
    cache.put(
        key,
        DailyWeather(
            city_id=CITY,
            date=date(2026, 9, 25),
            temp_min=12.0,
            temp_max=22.0,
            precip_prob=None,
            condition="晴",
            data_type="climate_statistics",
            source_record_id="qweather:climate-old",
            retrieved_at=FIXED_NOW,
            valid_until=FIXED_NOW + timedelta(hours=1),
            freshness_status="fresh",
        ),
    )
    clock[0] = FIXED_NOW + timedelta(hours=2)

    day = provider.resolve(CITY, date(2026, 9, 25), "climate_statistics")

    assert day.data is not None
    assert day.data.freshness_status == "stale"


def test_auth_and_quota_write_no_negative_cache() -> None:
    for status, body in ((401, {"code": "401"}), (200, {"code": "402"})):
        port = adapter(handler(status=status, body=body))
        clock = [FIXED_NOW]
        provider, _, store = provider_for(port, clock)

        day = provider.resolve(CITY, TODAY, "forecast")

        assert day.data is None
        assert day.error_kind in {WeatherErrorKind.AUTH, WeatherErrorKind.QUOTA}
        assert store.keys == []


def test_cache_key_is_not_shared_across_city_timezone_or_supplier() -> None:
    clock = [FIXED_NOW]
    store = FakeStore(clock)
    cache = RedisWeatherCache(
        store,
        ttl_seconds=TTL,
        climate_retention_seconds=RETENTION,
        now=lambda: clock[0],
        key_prefix="qweather",
    )
    cache.put(
        weather_key(CITY, TODAY, "forecast", TZ),
        DailyWeather(
            city_id=CITY,
            date=TODAY,
            temp_min=15.0,
            temp_max=24.0,
            precip_prob=0.2,
            condition="多云",
            data_type="forecast",
            source_record_id="qweather:beijing",
            retrieved_at=FIXED_NOW,
            valid_until=FIXED_NOW + timedelta(hours=1),
        ),
    )

    # A different city must never reuse another city's weather.
    assert cache.get(weather_key("shanghai", TODAY, "forecast", TZ)) is None
    # A different supplier timezone or data type is also a different key.
    assert cache.get(weather_key(CITY, TODAY, "forecast", "America/New_York")) is None
    assert cache.get(weather_key(CITY, TODAY, "climate_statistics", TZ)) is None
    # A different supplier version never reuses the entry either.
    other_supplier = RedisWeatherCache(
        store, ttl_seconds=TTL, now=lambda: clock[0], key_prefix="other-vendor-v2"
    )
    assert other_supplier.get(weather_key(CITY, TODAY, "forecast", TZ)) is None
    # The exact key still hits, proving the cache itself works.
    assert cache.get(weather_key(CITY, TODAY, "forecast", TZ)) is not None


# ------------------------------------------------------------ error mapping


@pytest.mark.parametrize(
    ("kwargs", "expected"),
    [
        ({"status": 429, "body": {"code": "429"}}, WeatherErrorKind.RATE_LIMITED),
        ({"status": 401, "body": {"code": "401"}}, WeatherErrorKind.AUTH),
        ({"status": 200, "body": {"code": "402"}}, WeatherErrorKind.QUOTA),
        ({"status": 503, "body": {"code": "503"}}, WeatherErrorKind.UNREACHABLE),
        ({"forecast": []}, WeatherErrorKind.EMPTY),
        ({"raw": "<html>not json</html>"}, WeatherErrorKind.DIRTY),
        ({"forecast": [fc("2026-09-21", 25, 15)]}, WeatherErrorKind.DIRTY),
        ({"forecast": [fc("2026-09-21", 15, 24, pop="150")]}, WeatherErrorKind.DIRTY),
        ({"forecast": [{"fxDate": "2026-09-21", "tempMin": "15"}]}, WeatherErrorKind.DIRTY),
    ],
)
def test_error_and_dirty_mapping(kwargs: dict[str, object], expected: WeatherErrorKind) -> None:
    port = adapter(handler(**kwargs))  # type: ignore[arg-type]

    result = port.forecast(CITY, TODAY)

    assert not isinstance(result, DailyWeather)
    assert result.kind is expected


def test_timeout_maps_to_timeout() -> None:
    port = adapter(handler(exc=httpx.ReadTimeout("slow")))

    result = port.forecast(CITY, TODAY)

    assert not isinstance(result, DailyWeather)
    assert result.kind is WeatherErrorKind.TIMEOUT


def test_supplier_failure_never_defaults_to_sunny() -> None:
    port = adapter(handler(status=429, body={"code": "429"}))
    clock = [FIXED_NOW]
    provider, _, _ = provider_for(port, clock)

    result = run_weather(request(start=TODAY, end=TODAY), provider)

    assert result.status == "failed"
    assert result.message == "天气服务暂不可用"
    assert result.daily == []


def test_unknown_city_is_an_explicit_error() -> None:
    port = adapter(handler())

    result = port.forecast("atlantis", TODAY)

    assert not isinstance(result, DailyWeather)
    assert result.kind is WeatherErrorKind.DIRTY


# ------------------------------------------------------- end-to-end determinism

INPUT = {
    "city_id": "beijing",
    "date_range": {"start": "2026-09-21", "end": "2026-09-23"},
    "today": "2026-09-21",
    "forecast_horizon_days": 3,
    "sensitive_points": ["天安门"],
}


def _run_fixed_input() -> str:
    port = adapter(
        handler(
            forecast=[
                fc("2026-09-21", 15, 24, pop="20", text="多云"),
                fc("2026-09-22", 16, 25, pop="60", text="小雨"),
                fc("2026-09-23", 17, 26, pop="0", text="晴"),
            ]
        )
    )
    clock = [FIXED_NOW]
    provider, _, _ = provider_for(port, clock)
    request_obj = WeatherPlanRequest.model_validate(INPUT)
    return run_weather(request_obj, provider).model_dump_json()


def test_fixed_input_matches_expected_daily_output() -> None:
    port = adapter(
        handler(
            forecast=[
                fc("2026-09-21", 15, 24, pop="20", text="多云"),
                fc("2026-09-22", 16, 25, pop="60", text="小雨"),
                fc("2026-09-23", 17, 26, pop="0", text="晴"),
            ]
        )
    )
    clock = [FIXED_NOW]
    provider, _, _ = provider_for(port, clock)

    result = run_weather(WeatherPlanRequest.model_validate(INPUT), provider)
    payload = result.model_dump(mode="json")

    assert payload["status"] == "ok"
    assert [(item["date"], item["data_type"]) for item in payload["daily"]] == [
        ("2026-09-21", "forecast"),
        ("2026-09-22", "forecast"),
        ("2026-09-23", "forecast"),
    ]
    assert [item["temp_min"] for item in payload["daily"]] == [15.0, 16.0, 17.0]
    assert [item["temp_max"] for item in payload["daily"]] == [24.0, 25.0, 26.0]
    assert [item["precip_prob"] for item in payload["daily"]] == [0.2, 0.6, 0.0]
    assert [item["condition"] for item in payload["daily"]] == ["多云", "小雨", "晴"]
    assert all(str(item["source_record_id"]).startswith("qweather:") for item in payload["daily"])
    assert all(item["retrieved_at"] == "2026-09-21T08:00:00Z" for item in payload["daily"])
    assert payload["travel_advice"] is not None


def test_fixed_input_is_byte_identical_across_two_runs() -> None:
    assert _run_fixed_input() == _run_fixed_input()


# ------------------------------------------------------------- S11 mounting


def test_step_reads_horizon_from_adapter_and_is_idempotent() -> None:
    def run() -> str:
        port = adapter(
            handler(
                forecast=[
                    fc("2026-09-21", 15, 24),
                    fc("2026-09-22", 16, 25),
                    fc("2026-09-23", 17, 26),
                ],
                climate=[climate_day("2026-09-24", 12, 21)],
            ),
            days=3,
        )
        clock = [FIXED_NOW]
        provider, _, _ = provider_for(port, clock)
        result = run_weather_step(
            "task-1",
            2,
            [request(start=TODAY, end=date(2026, 9, 24), horizon=0)],
            provider,
            port,
            now=lambda: clock[0],
        )
        # The request says horizon=0, but the adapter's real tier (3) must win.
        assert [item.data_type for item in result.daily] == [
            "forecast",
            "forecast",
            "forecast",
            "climate_statistics",
        ]
        return result.model_dump_json()

    assert run() == run()


def test_step_all_failed_returns_empty_daily() -> None:
    port = adapter(handler(status=503, body={"code": "503"}))
    clock = [FIXED_NOW]
    provider, _, _ = provider_for(port, clock)

    result = run_weather_step(
        "task-1", 1, [request(start=TODAY, end=TODAY)], provider, port, now=lambda: clock[0]
    )

    assert result.status == "failed"
    assert result.message == "天气服务暂不可用"
    assert result.daily == []
    assert result.error_code is not None


def test_step_partial_when_one_day_fails() -> None:
    port = adapter(handler(forecast=[fc("2026-09-21", 15, 24)]))
    clock = [FIXED_NOW]
    provider, _, _ = provider_for(port, clock)

    result = run_weather_step(
        "task-1",
        1,
        [request(start=TODAY, end=date(2026, 9, 22))],
        provider,
        port,
        now=lambda: clock[0],
    )

    assert result.status == "partial"
    assert result.degraded_fields == ["daily"]
    assert [item.date for item in result.daily] == [TODAY]


def test_step_degraded_when_climate_served_stale() -> None:
    port = adapter(handler(status=503, body={"code": "503"}))
    clock = [FIXED_NOW]
    provider, cache, _ = provider_for(port, clock)
    day = date(2026, 9, 25)
    cache.put(
        weather_key(CITY, day, "climate_statistics", TZ),
        DailyWeather(
            city_id=CITY,
            date=day,
            temp_min=12.0,
            temp_max=22.0,
            precip_prob=None,
            condition="气候统计（非预报）",
            data_type="climate_statistics",
            source_record_id="qweather:climate-old",
            retrieved_at=FIXED_NOW,
            valid_until=FIXED_NOW + timedelta(hours=1),
        ),
    )
    clock[0] = FIXED_NOW + timedelta(hours=2)

    result = run_weather_step(
        "task-1", 1, [request(start=day, end=day)], provider, port, now=lambda: clock[0]
    )

    assert result.status == "degraded"
    assert result.degraded_fields == ["daily"]
    assert [item.date for item in result.daily] == [day]
    assert result.source_metadata[0]["freshness_status"] == "stale"


def test_step_projects_weather_source_metadata() -> None:
    port = adapter(handler(forecast=[fc("2026-09-21", 15, 24)]))
    clock = [FIXED_NOW]
    provider, _, _ = provider_for(port, clock)

    result = run_weather_step(
        "task-1", 1, [request(start=TODAY, end=TODAY)], provider, port, now=lambda: clock[0]
    )

    assert result.source_metadata
    item = result.source_metadata[0]
    assert item["source_type"] == "weather"
    assert str(item["source_record_id"]).startswith("qweather:")
    assert item["retrieved_at"] == "2026-09-21T08:00:00Z"
    assert item["freshness_status"] == "fresh"
    assert item["is_estimated"] is False
