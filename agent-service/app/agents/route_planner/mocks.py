"""Deterministic mock adapters for the route planner ports (test doubles).

These are NOT the real S10 adapters (owned by R5). They exist so S12 can be developed and
tested on dev-r2 with byte-identical reproducibility. Real adapter wiring is PENDING-R5.
"""

from __future__ import annotations

from datetime import UTC, datetime

from app.agents.route_planner.errors import RouteErrorKind
from app.agents.route_planner.models import (
    PoiRecord,
    RouteData,
    RoutePortError,
    RoutePortResult,
    RouteQuery,
)
from app.algorithms.geometry import haversine_m, straight_line_duration_min

FIXED_RETRIEVED_AT = datetime(2026, 9, 21, 8, 0, 0, tzinfo=UTC)


class MockPoiPort:
    """In-memory POI port backed by an explicit record list."""

    def __init__(self, records: list[PoiRecord]):
        self._records = {record.poi_id: record for record in records}

    def lookup(self, name: str, city_id: str) -> list[PoiRecord]:
        return [r for r in self._records.values() if r.name == name and r.city_id == city_id]

    def get(self, poi_id: str) -> PoiRecord | None:
        return self._records.get(poi_id)


class MockRouteCache:
    """In-memory route cache. Freshness is a field on ``RouteData``, not a wall-clock TTL."""

    def __init__(self) -> None:
        self._store: dict[str, RouteData] = {}

    def get(self, key: str) -> RouteData | None:
        return self._store.get(key)

    def put(self, key: str, data: RouteData) -> None:
        self._store[key] = data

    def __contains__(self, key: str) -> bool:
        return key in self._store


class MockRoutePort:
    """Deterministic route port: haversine distance at a fixed speed, or a scripted error."""

    def __init__(
        self,
        coords: dict[str, tuple[float, float]],
        speed_kmh: float = 40.0,
        retrieved_at: datetime = FIXED_RETRIEVED_AT,
    ):
        self._coords = coords
        self._speed_kmh = speed_kmh
        self._retrieved_at = retrieved_at
        self._failures: dict[tuple[str, str], RouteErrorKind] = {}
        self._fail_all: RouteErrorKind | None = None
        self.call_count = 0

    def fail(self, from_id: str, to_id: str, kind: RouteErrorKind) -> None:
        self._failures[(from_id, to_id)] = kind

    def fail_all(self, kind: RouteErrorKind) -> None:
        self._fail_all = kind

    def route(self, query: RouteQuery) -> RoutePortResult:
        self.call_count += 1
        key = (query.from_point_id, query.to_point_id)
        kind = self._failures.get(key, self._fail_all)
        if kind is not None:
            return RoutePortError(kind=kind, message=f"mock {kind.value}")

        lat1, lon1 = self._coords[query.from_point_id]
        lat2, lon2 = self._coords[query.to_point_id]
        distance_m = haversine_m(lat1, lon1, lat2, lon2)
        duration_min = straight_line_duration_min(distance_m, self._speed_kmh)
        return RouteData(
            from_point_id=query.from_point_id,
            to_point_id=query.to_point_id,
            transport=query.transport,
            distance_m=round(distance_m, 2),
            duration_min=round(duration_min, 2),
            source_record_id=f"src-route:{query.from_point_id}:{query.to_point_id}",
            retrieved_at=self._retrieved_at,
            freshness_status="fresh",
            is_estimated=False,
        )
