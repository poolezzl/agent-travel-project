"""Legal POI source for the S18 dining/lodging agent.

The real S10 POI adapter is owned outside this step. This module defines the
small read-only port the agent consumes, plus a synthetic JSON source used by
the local API route and tests. The source returns POI records only; price,
freshness and detour metadata are validated by the agent.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any, Protocol

EARTH_RADIUS_M = 6_371_000.0


class PoiSourceError(RuntimeError):
    """The POI source cannot be loaded or cannot serve the request."""


class PoiSource(Protocol):
    def nearby(
        self,
        *,
        city_id: str,
        category: str,
        latitude: float,
        longitude: float,
        radius_m: float,
    ) -> list[dict[str, Any]]: ...


class JsonPoiSource:
    """Read-only synthetic source; production can replace it with the S10 adapter."""

    def __init__(self, pois: list[dict[str, Any]]) -> None:
        if not isinstance(pois, list) or any(not isinstance(item, dict) for item in pois):
            raise PoiSourceError("POI 数据必须是对象数组")
        self._pois = [dict(item) for item in pois]

    @classmethod
    def load(cls, path: Path) -> JsonPoiSource:
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            raise PoiSourceError(f"无法读取 POI 数据：{path}") from exc
        if not isinstance(payload, dict) or not isinstance(payload.get("pois"), list):
            raise PoiSourceError("POI 数据缺少 pois 数组")
        return cls(payload["pois"])

    def nearby(
        self,
        *,
        city_id: str,
        category: str,
        latitude: float,
        longitude: float,
        radius_m: float,
    ) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        for poi in self._pois:
            if poi.get("city_id") != city_id or poi.get("category") != category:
                continue
            lat = poi.get("latitude")
            lon = poi.get("longitude")
            if not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
                continue
            if haversine_m(latitude, longitude, float(lat), float(lon)) <= radius_m:
                result.append(dict(poi))
        return result


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance in metres (explicit estimate basis for local data)."""
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lambda = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


__all__ = ["JsonPoiSource", "PoiSource", "PoiSourceError", "haversine_m"]
