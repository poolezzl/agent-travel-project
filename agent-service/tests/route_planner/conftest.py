"""Shared fixtures for route planner tests (deterministic, no network)."""

from __future__ import annotations

from datetime import UTC, datetime

import pytest
from app.agents.route_planner.mocks import MockPoiPort, MockRouteCache, MockRoutePort
from app.agents.route_planner.models import (
    PointCandidate,
    PointTiming,
    PoiRecord,
    RoutePlanRequest,
    TimeWindow,
)
from app.agents.route_planner.route_provider import RouteProvider

BEIJING = "beijing"

# Three points in Beijing, laid out west-to-east. Distances are a few km apart.
COORDS: dict[str, tuple[float, float]] = {
    "p1": (39.9042, 116.3574),  # roughly Tiananmen
    "p2": (39.9146, 116.4074),  # roughly Wangfujing
    "p3": (39.9367, 116.4171),  # roughly north-east
    "home": (39.8997, 116.3912),
}

FIXED_NOW = datetime(2026, 9, 21, 8, 0, 0, tzinfo=UTC)


def make_record(poi_id: str, name: str, city_id: str = BEIJING) -> PoiRecord:
    lat, lon = COORDS[poi_id]
    return PoiRecord(
        poi_id=poi_id,
        name=name,
        city_id=city_id,
        latitude=lat,
        longitude=lon,
        coordinate_system="GCJ02",
    )


@pytest.fixture
def poi_port() -> MockPoiPort:
    return MockPoiPort(
        [
            make_record("p1", "天安门"),
            make_record("p2", "王府井"),
            make_record("p3", "雍和宫"),
            make_record("home", "酒店"),
        ]
    )


@pytest.fixture
def cache() -> MockRouteCache:
    return MockRouteCache()


@pytest.fixture
def route_port() -> MockRoutePort:
    return MockRoutePort(COORDS)


@pytest.fixture
def provider(route_port: MockRoutePort, cache: MockRouteCache) -> RouteProvider:
    return RouteProvider(route_port, cache, now=lambda: FIXED_NOW)


def make_request(
    point_ids: list[str],
    *,
    names: dict[str, str] | None = None,
    origin_id: str | None = "home",
    destination_id: str | None = "home",
    start: str = "09:00",
    end: str = "18:00",
    timings: list[PointTiming] | None = None,
) -> RoutePlanRequest:
    names = names or {}
    points = [
        PointCandidate(
            poi_id=pid,
            name=names.get(pid, pid),
            city_id=BEIJING,
            latitude=COORDS[pid][0],
            longitude=COORDS[pid][1],
            coordinate_system="GCJ02",
        )
        for pid in point_ids
    ]
    origin = (
        PointCandidate(
            poi_id=origin_id,
            name="酒店",
            city_id=BEIJING,
            latitude=COORDS[origin_id][0],
            longitude=COORDS[origin_id][1],
            coordinate_system="GCJ02",
        )
        if origin_id is not None
        else None
    )
    destination = (
        PointCandidate(
            poi_id=destination_id,
            name="酒店",
            city_id=BEIJING,
            latitude=COORDS[destination_id][0],
            longitude=COORDS[destination_id][1],
            coordinate_system="GCJ02",
        )
        if destination_id is not None
        else None
    )
    return RoutePlanRequest(
        date="2026-10-01",
        city_id=BEIJING,
        coordinate_system="GCJ02",
        points=points,
        origin=origin,
        destination=destination,
        transport_preference="打车",
        time_window=TimeWindow(start=start, end=end),
        point_timings=timings or [],
    )
