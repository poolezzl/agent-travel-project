"""ALG-06 degradation ladder tests: exact cache -> estimate -> unavailable."""

from __future__ import annotations

from datetime import UTC, datetime

from app.agents.route_planner.errors import RouteErrorKind
from app.agents.route_planner.mocks import MockRouteCache, MockRoutePort
from app.agents.route_planner.models import (
    ResolvedPoint,
    RouteData,
    RouteQuery,
)
from app.agents.route_planner.route_provider import RouteProvider, route_key

BEIJING = "beijing"
COORDS = {"a": (39.9042, 116.3574), "b": (39.9146, 116.4074)}


def _point(pid: str) -> ResolvedPoint:
    lat, lon = COORDS[pid]
    return ResolvedPoint(
        poi_id=pid,
        name=pid,
        city_id=BEIJING,
        latitude=lat,
        longitude=lon,
        coordinate_system="GCJ02",
    )


def _query() -> RouteQuery:
    return RouteQuery(
        from_point_id="a",
        to_point_id="b",
        transport="打车",
        city_id=BEIJING,
    )


def test_cache_hit_preserves_original_source_time():
    port = MockRoutePort(COORDS)
    cache = MockRouteCache()
    provider = RouteProvider(port, cache)

    original = RouteData(
        from_point_id="a",
        to_point_id="b",
        transport="打车",
        distance_m=5000.0,
        duration_min=20.0,
        source_record_id="src-old",
        retrieved_at=datetime(2026, 9, 20, 12, 0, 0, tzinfo=UTC),
        freshness_status="fresh",
        is_estimated=False,
    )
    cache.put(route_key(_query()), original)

    result = provider.route(_query(), _point("a"), _point("b"))

    assert result.status == "exact"
    assert result.data is not None
    assert result.data.retrieved_at == original.retrieved_at
    assert result.data.source_record_id == "src-old"
    assert port.call_count == 0  # no adapter call on cache hit


def test_cache_stale_falls_back_to_port():
    port = MockRoutePort(COORDS)
    cache = MockRouteCache()
    provider = RouteProvider(port, cache)

    stale = RouteData(
        from_point_id="a",
        to_point_id="b",
        transport="打车",
        distance_m=5000.0,
        duration_min=20.0,
        source_record_id="src-old",
        retrieved_at=datetime(2026, 9, 20, 12, 0, 0, tzinfo=UTC),
        freshness_status="stale",
        is_estimated=False,
    )
    cache.put(route_key(_query()), stale)

    result = provider.route(_query(), _point("a"), _point("b"))

    assert result.status == "exact"
    assert port.call_count == 1
    assert result.data is not None
    assert result.data.source_record_id != "src-old"


def test_auth_failure_is_unavailable_and_not_cached():
    port = MockRoutePort(COORDS)
    cache = MockRouteCache()
    provider = RouteProvider(port, cache)
    port.fail_all(RouteErrorKind.AUTH)

    result = provider.route(_query(), _point("a"), _point("b"))

    assert result.status == "unavailable"
    assert result.error_kind == RouteErrorKind.AUTH
    assert route_key(_query()) not in cache  # no negative cache


def test_quota_failure_is_unavailable_and_not_cached():
    port = MockRoutePort(COORDS)
    cache = MockRouteCache()
    provider = RouteProvider(port, cache)
    port.fail_all(RouteErrorKind.QUOTA)

    result = provider.route(_query(), _point("a"), _point("b"))

    assert result.status == "unavailable"
    assert route_key(_query()) not in cache


def test_timeout_is_unavailable_structured_conflict():
    port = MockRoutePort(COORDS)
    provider = RouteProvider(port, MockRouteCache())
    port.fail_all(RouteErrorKind.TIMEOUT)

    result = provider.route(_query(), _point("a"), _point("b"))

    assert result.status == "unavailable"
    assert result.error_kind == RouteErrorKind.TIMEOUT


def test_rate_limited_falls_back_to_straight_line_estimate():
    port = MockRoutePort(COORDS)
    provider = RouteProvider(port, MockRouteCache())
    port.fail_all(RouteErrorKind.RATE_LIMITED)

    result = provider.route(_query(), _point("a"), _point("b"))

    assert result.status == "estimated"
    assert result.data is not None
    assert result.data.is_estimated is True
    assert result.data.source_record_id == "straight-line-estimate"


def test_empty_falls_back_to_estimate():
    port = MockRoutePort(COORDS)
    provider = RouteProvider(port, MockRouteCache())
    port.fail_all(RouteErrorKind.EMPTY)

    result = provider.route(_query(), _point("a"), _point("b"))

    assert result.status == "estimated"
    assert result.data is not None
    assert result.data.is_estimated is True


def test_dirty_falls_back_to_estimate():
    port = MockRoutePort(COORDS)
    provider = RouteProvider(port, MockRouteCache())
    port.fail_all(RouteErrorKind.DIRTY)

    result = provider.route(_query(), _point("a"), _point("b"))

    assert result.status == "estimated"
    assert result.data is not None
    assert result.data.is_estimated is True


def test_estimate_requires_coordinates():
    port = MockRoutePort(COORDS)
    provider = RouteProvider(port, MockRouteCache())
    port.fail_all(RouteErrorKind.EMPTY)

    result = provider.route(_query())  # no from/to coordinates supplied

    assert result.status == "unavailable"
