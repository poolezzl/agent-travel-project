"""Standard city id -> QWeather ``location`` mapping (mirrors S10 ``app.adapters.cities``).

The mapping is configuration, not weather fact. It matches
``backend/src/main/resources/application.yml`` exactly; ``QWEATHER_CITY_MAP`` (JSON object)
may override or extend it without a code change. An unknown city is a configuration error and
never falls back to a guessed location.
"""

from __future__ import annotations

import json
from collections.abc import Mapping

DEFAULT_QWEATHER_CITIES: dict[str, str] = {
    "beijing": "101010100",
    "shanghai": "101020100",
    "hangzhou": "101210101",
    "nanjing": "101190101",
    "suzhou": "101190401",
    "chengdu": "101270101",
    "xian": "101110101",
    "chongqing": "101040100",
    "guangzhou": "101280101",
    "xiamen": "101230201",
}


def normalize_city_id(city_id: str) -> str:
    return city_id.strip().lower()


class CityRegistry:
    """Resolve a standard city key to the supplier location id."""

    def __init__(self, mapping: Mapping[str, str] | None = None) -> None:
        self._cities = {
            normalize_city_id(key): value.strip()
            for key, value in (mapping or DEFAULT_QWEATHER_CITIES).items()
        }

    @classmethod
    def from_json(cls, raw: str) -> CityRegistry:
        """Build from a JSON object; an empty string keeps the built-in mapping."""
        if not raw.strip():
            return cls()
        parsed = json.loads(raw)
        if not isinstance(parsed, dict):
            raise ValueError("QWEATHER_CITY_MAP must be a JSON object")
        merged = dict(DEFAULT_QWEATHER_CITIES)
        for key, value in parsed.items():
            if not isinstance(key, str) or not isinstance(value, str):
                raise ValueError("QWEATHER_CITY_MAP entries must be strings")
            merged[key] = value
        return cls(merged)

    def resolve(self, city_id: str) -> str | None:
        return self._cities.get(normalize_city_id(city_id))
