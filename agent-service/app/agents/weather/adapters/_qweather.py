"""Shared QWeather field parsing and error-code mapping (mirrors S10 ``RealQWeatherAdapter``).

Vendor payloads are untrusted: every field is type- and range-checked before it becomes a
``DailyWeather``. Anything that cannot be mapped is dropped, never guessed.
"""

from __future__ import annotations

import hashlib
from typing import Any

from app.agents.weather.errors import WeatherErrorKind

FORECAST_PATH = "/v7/weather/{days}d"
CLIMATE_PATH = "/v7/historical/weather"

_AUTH_CODES = frozenset({"401", "403"})
_QUOTA_CODES = frozenset({"402"})
_RATE_CODES = frozenset({"429"})
_EMPTY_CODES = frozenset({"204"})
_INVALID_CODES = frozenset({"400", "404"})
_UNAVAILABLE_CODES = frozenset({"500", "503"})


def error_kind(status_code: int, body: object) -> WeatherErrorKind:
    """Map QWeather HTTP status + response ``code`` to a stable ``WeatherErrorKind``."""
    if status_code in (401, 403):
        return WeatherErrorKind.AUTH
    if status_code == 429:
        return _code_kind(body) or WeatherErrorKind.RATE_LIMITED
    if status_code >= 500:
        return WeatherErrorKind.UNREACHABLE
    if status_code < 200 or status_code >= 300:
        return WeatherErrorKind.DIRTY
    return _code_kind(body) or WeatherErrorKind.DIRTY


def _code_kind(body: object) -> WeatherErrorKind | None:
    code = response_code(body)
    if code in _AUTH_CODES:
        return WeatherErrorKind.AUTH
    if code in _QUOTA_CODES:
        return WeatherErrorKind.QUOTA
    if code in _RATE_CODES:
        return WeatherErrorKind.RATE_LIMITED
    if code in _EMPTY_CODES:
        return WeatherErrorKind.EMPTY
    if code in _INVALID_CODES:
        return WeatherErrorKind.DIRTY
    if code in _UNAVAILABLE_CODES:
        return WeatherErrorKind.UNREACHABLE
    return None


def response_code(body: object) -> str:
    """QWeather may return ``code`` as a string or number."""
    if not isinstance(body, dict):
        return ""
    code = body.get("code")
    if isinstance(code, str):
        return code.strip()
    if isinstance(code, int):
        return str(code)
    return ""


def text(node: object, field: str) -> str:
    if not isinstance(node, dict):
        return ""
    value = node.get(field)
    if value is None:
        return ""
    return str(value).strip()


def parse_number(value: object) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        parsed = float(value)
    elif isinstance(value, str):
        try:
            parsed = float(value.strip())
        except ValueError:
            return None
    else:
        return None
    return parsed if parsed == parsed and abs(parsed) != float("inf") else None


def daily_items(body: object) -> list[Any]:
    """Accept ``daily[]`` (forecast) and ``daily[]``/``weatherDaily.daily[]`` (historical)."""
    if not isinstance(body, dict):
        return []
    direct = body.get("daily")
    if isinstance(direct, list):
        return direct
    wrapper = body.get("weatherDaily")
    if isinstance(wrapper, dict):
        nested = wrapper.get("daily")
        if isinstance(nested, list):
            return nested
    return []


def source_record_id(*parts: str) -> str:
    """Deterministic, non-reversible source id (mirrors S10 SHA-256 source id)."""
    digest = hashlib.sha256("|".join(parts).encode()).hexdigest()[:16]
    return f"qweather:{digest}"
