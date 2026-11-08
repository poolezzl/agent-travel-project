"""QWeather adapter implementing ``WeatherPort`` by calling QWeather Web APIs directly.

``forecast`` hits ``/v7/weather/{N}d`` (N = the configured, real forecast tier) and returns the
record whose ``fxDate`` equals the requested day; a date the supplier does not cover yields
``EMPTY`` — never a fabricated "sunny" record. ``climate`` hits the historical weather endpoint
and flags the result ``climate_statistics`` with ``precip_prob=None`` because a historical
observation carries no precipitation probability.

The supplier timezone is injected (``ZoneInfo``), never hard-coded, and is used only to place
the end of the supplier day for ``valid_until``. The adapter never decides what "today" is —
``today`` comes from the caller (``run_weather`` request).
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import httpx

from app.agents.weather.adapters._qweather import (
    CLIMATE_PATH,
    FORECAST_PATH,
    daily_items,
    error_kind,
    parse_number,
    response_code,
    source_record_id,
    text,
)
from app.agents.weather.adapters.cities import CityRegistry, normalize_city_id
from app.agents.weather.errors import WeatherErrorKind
from app.agents.weather.models import DailyWeather, WeatherPortError, WeatherPortResult

_DEFAULT_ALIAS = "qweather"


class QWeatherAdapter:
    def __init__(
        self,
        client: httpx.Client,
        api_key: str,
        city_registry: CityRegistry,
        zone_id: str,
        forecast_days: int,
        alias: str = _DEFAULT_ALIAS,
        cache_ttl_seconds: int = 3600,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        if not 1 <= forecast_days <= 30:
            raise ValueError("forecast_days must be between 1 and 30")
        self._client = client
        self._api_key = api_key
        self._cities = city_registry
        self._zone = ZoneInfo(zone_id)
        self._zone_id = zone_id
        self._forecast_days = forecast_days
        self._alias = alias
        self._cache_ttl = timedelta(seconds=cache_ttl_seconds)
        self._now = now if now is not None else lambda: datetime.now(UTC)

    @property
    def forecast_horizon_days(self) -> int:
        """The supplier's real forecast tier (drives ``/v7/weather/{N}d``)."""
        return self._forecast_days

    @property
    def timezone(self) -> str:
        """Supplier IANA timezone injected at construction (never hard-coded here)."""
        return self._zone_id

    def forecast(self, city_id: str, day: date) -> WeatherPortResult:
        location = self._cities.resolve(city_id)
        if location is None:
            return _error(WeatherErrorKind.DIRTY, "标准城市未配置和风天气映射")

        body, failure = self._get(
            FORECAST_PATH.format(days=self._forecast_days),
            {"location": location},
            "和风天气预报请求失败",
        )
        if failure is not None:
            return failure

        record = _find_day(daily_items(body), day)
        if record is None:
            return _error(WeatherErrorKind.EMPTY, "请求日期不在和风天气实际预报窗口内")

        parsed = _parse_day(record, day)
        if parsed is None:
            return _error(WeatherErrorKind.DIRTY, "和风天气逐日数据无效")
        temp_min, temp_max, precip_prob, condition = parsed

        retrieved_at = self._now()
        valid_until = min(retrieved_at + self._cache_ttl, self._day_end(day))
        return DailyWeather(
            city_id=normalize_city_id(city_id),
            date=day,
            temp_min=temp_min,
            temp_max=temp_max,
            precip_prob=precip_prob,
            condition=condition,
            data_type="forecast",
            source_record_id=source_record_id(
                "qweather",
                normalize_city_id(city_id),
                location,
                day.isoformat(),
                self._alias,
                str(self._forecast_days),
            ),
            retrieved_at=retrieved_at,
            valid_until=valid_until,
            freshness_status="fresh",
        )

    def climate(self, city_id: str, day: date) -> WeatherPortResult:
        location = self._cities.resolve(city_id)
        if location is None:
            return _error(WeatherErrorKind.DIRTY, "标准城市未配置和风天气映射")

        body, failure = self._get(
            CLIMATE_PATH,
            {"location": location, "date": day.strftime("%Y%m%d")},
            "和风天气气候统计请求失败",
        )
        if failure is not None:
            return failure

        record = _find_day(daily_items(body), day)
        if record is None:
            return _error(WeatherErrorKind.EMPTY, "和风天气无该日气候统计")

        parsed = _parse_day(record, day)
        if parsed is None:
            return _error(WeatherErrorKind.DIRTY, "和风天气气候统计数据无效")
        temp_min, temp_max, _precip_prob, condition = parsed

        retrieved_at = self._now()
        return DailyWeather(
            city_id=normalize_city_id(city_id),
            date=day,
            temp_min=temp_min,
            temp_max=temp_max,
            # A historical observation has no probability: null, never an invented value.
            precip_prob=None,
            condition=condition,
            data_type="climate_statistics",
            source_record_id=source_record_id(
                "qweather-climate",
                normalize_city_id(city_id),
                location,
                day.isoformat(),
                self._alias,
            ),
            retrieved_at=retrieved_at,
            valid_until=retrieved_at + self._cache_ttl,
            freshness_status="fresh",
        )

    def _get(
        self,
        path: str,
        params: dict[str, str],
        failure_message: str,
    ) -> tuple[object, WeatherPortResult | None]:
        try:
            response = self._client.get(path, params={**params, "key": self._api_key})
        except httpx.TimeoutException:
            return None, _error(WeatherErrorKind.TIMEOUT, "和风天气请求超时")
        except httpx.HTTPError:
            return None, _error(WeatherErrorKind.UNREACHABLE, "和风天气不可达")

        try:
            body: object = response.json()
        except ValueError:
            return None, _error(WeatherErrorKind.DIRTY, "和风天气响应不是有效 JSON")

        if response.status_code != 200:
            return None, _error(error_kind(response.status_code, body), failure_message)
        if response_code(body) != "200":
            return None, _error(error_kind(200, body), failure_message)
        return body, None

    def _day_end(self, day: date) -> datetime:
        """End of the supplier-local day, DST-aware for the injected zone."""
        return datetime.combine(day + timedelta(days=1), time.min, tzinfo=self._zone)


def _find_day(items: list[object], day: date) -> object | None:
    for item in items:
        if text(item, "fxDate") == day.isoformat() or text(item, "date") == day.isoformat():
            return item
    return None


def _parse_day(
    item: object,
    day: date,
) -> tuple[float, float, float | None, str] | None:
    """Map one supplier record; ``None`` means dirty (mismatched date / temps / probability)."""
    record_date = text(item, "fxDate") or text(item, "date")
    if record_date != day.isoformat():
        return None

    temp_min = parse_number(text(item, "tempMin"))
    temp_max = parse_number(text(item, "tempMax"))
    if temp_min is None or temp_max is None or temp_min > temp_max:
        return None

    condition = text(item, "textDay") or text(item, "textNight")
    if not condition:
        return None

    pop = parse_number(text(item, "pop"))
    precip_prob: float | None = None
    if pop is not None:
        if not 0.0 <= pop <= 100.0:
            return None
        precip_prob = pop / 100.0
    return temp_min, temp_max, precip_prob, condition


def _error(kind: WeatherErrorKind, message: str) -> WeatherPortError:
    return WeatherPortError(kind=kind, message=message)
