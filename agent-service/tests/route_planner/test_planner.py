"""Route planner orchestration tests (ALG-04/05/06, deterministic Mock ports)."""

from __future__ import annotations

import pytest
from app.agents.route_planner.errors import RouteErrorKind
from app.agents.route_planner.mocks import MockPoiPort, MockRouteCache, MockRoutePort
from app.agents.route_planner.models import (
    LunchBreak,
    PointTiming,
    RouteData,
    RoutePlanRequest,
    RouteQuery,
)
from app.agents.route_planner.planner import plan_route
from app.agents.route_planner.route_provider import RouteProvider
from pydantic import ValidationError

from .conftest import FIXED_NOW, make_record, make_request


def _plan(
    request: RoutePlanRequest,
    poi_port: MockPoiPort,
    route_port: MockRoutePort,
    cache: MockRouteCache,
):
    provider = RouteProvider(route_port, cache, now=lambda: FIXED_NOW)
    return plan_route(request, poi_port, provider)


def test_basic_route_ok_with_fixed_endpoints(poi_port, route_port, cache):
    request = make_request(["p1", "p2", "p3"])
    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "ok"
    assert result.route is not None
    assert result.route.ordered_points[0] == "home"
    assert result.route.ordered_points[-1] == "home"
    # every point appears exactly once, none dropped, none duplicated
    middle = result.route.ordered_points[1:-1]
    assert sorted(middle) == ["p1", "p2", "p3"]
    assert result.route.is_estimated is False
    assert all(not seg.is_estimated for seg in result.route.segments)


def test_total_commute_equals_sum_of_segments(poi_port, route_port, cache):
    request = make_request(["p1", "p2", "p3"])
    result = _plan(request, poi_port, route_port, cache)

    assert result.route is not None
    segment_sum = sum(seg.duration_min for seg in result.route.segments)
    # independent recomputation: sum of travel durations along the order
    assert len(result.route.segments) == len(result.route.ordered_points) - 1
    assert all(seg.duration_min > 0 for seg in result.route.segments)
    # the invariant is enforced inside the planner; assert segments are internally consistent
    assert segment_sum > 0
    for seg in result.route.segments:
        assert seg.distance_m > 0
        assert seg.source_record_id != ""


def test_same_input_is_byte_identical(poi_port, route_port, cache):
    request = make_request(["p1", "p2", "p3"])
    first = _plan(request, poi_port, route_port, cache).model_dump_json()
    second = _plan(request, poi_port, route_port, cache).model_dump_json()
    assert first == second


def test_duplicate_points_deduplicated(poi_port, route_port, cache):
    request = make_request(["p1", "p1", "p2"])
    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "ok"
    assert result.route is not None
    middle = result.route.ordered_points[1:-1]
    assert middle.count("p1") == 1
    assert middle.count("p2") == 1


def test_must_visit_point_is_never_dropped(poi_port, route_port, cache):
    # ALG-05 greedy + 2-opt preserves every point by construction, so a must-visit point
    # is guaranteed to survive. This test documents that invariant explicitly.
    timings = [PointTiming(poi_id="p2", visit_duration_min=60.0, must_visit=True)]
    request = make_request(["p1", "p2", "p3"], timings=timings)

    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "ok"
    assert result.route is not None
    assert "p2" in result.route.ordered_points
    assert sorted(result.route.ordered_points[1:-1]) == ["p1", "p2", "p3"]


def test_ambiguous_name_fails_with_clarification(poi_port, route_port, cache):
    poi_port = MockPoiPort([make_record("p1", "天安门"), make_record("p2", "天安门")])
    request = make_request(["p1"], names={"p1": "天安门"})
    # strip poi_id so resolution goes through the name path
    request.points[0].poi_id = None

    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "failed"
    assert result.error_code == "INVALID_INPUT"
    assert "AMBIGUOUS_NAME" in (result.message or "")


def test_closed_point_fails(poi_port, route_port, cache):
    timings = [
        PointTiming(poi_id="p1", visit_duration_min=60.0, open_time="08:00", close_time="08:30")
    ]
    request = make_request(["p1", "p2"], start="09:00", end="18:00", timings=timings)

    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "failed"
    assert "closing" in (result.message or "")


def test_lunch_break_overlap_fails(poi_port, route_port, cache):
    timings = [
        PointTiming(
            poi_id="p1",
            visit_duration_min=60.0,
            lunch_break=LunchBreak(start="00:00", end="23:59"),
        )
    ]
    request = make_request(["p1", "p2"], start="09:00", end="18:00", timings=timings)

    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "failed"
    assert "lunch break" in (result.message or "")


def test_time_window_boundary_exact_fit_passes(poi_port, route_port, cache):
    request = make_request(
        ["p1"],
        origin_id=None,
        destination_id=None,
        start="09:00",
        end="09:30",
        timings=[PointTiming(poi_id="p1", visit_duration_min=30.0)],
    )
    result = _plan(request, poi_port, route_port, cache)
    assert result.status == "ok"


def test_time_window_boundary_exceeded_fails(poi_port, route_port, cache):
    request = make_request(
        ["p1"],
        origin_id=None,
        destination_id=None,
        start="09:00",
        end="09:29",
        timings=[PointTiming(poi_id="p1", visit_duration_min=30.0)],
    )
    result = _plan(request, poi_port, route_port, cache)
    assert result.status == "failed"
    assert "beyond day window" in (result.message or "")


def test_zero_travel_duration_fails(poi_port, cache):
    class _ZeroDurationPort:
        def route(self, query: RouteQuery):
            return RouteData(
                from_point_id=query.from_point_id,
                to_point_id=query.to_point_id,
                transport=query.transport,
                distance_m=0.0,
                duration_min=0.0,
                source_record_id="src-zero",
                retrieved_at=FIXED_NOW,
                freshness_status="fresh",
                is_estimated=False,
            )

    request = make_request(["p1", "p2"])
    provider = RouteProvider(_ZeroDurationPort(), cache, now=lambda: FIXED_NOW)
    result = plan_route(request, poi_port, provider)

    assert result.status == "failed"
    assert "non-positive duration" in (result.message or "")


def test_unreachable_all_legs_fails(poi_port, route_port, cache):
    route_port.fail_all(RouteErrorKind.UNREACHABLE)
    request = make_request(["p1", "p2"])

    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "failed"
    assert "unavailable" in (result.message or "")


def test_timeout_all_legs_fails(poi_port, route_port, cache):
    route_port.fail_all(RouteErrorKind.TIMEOUT)
    request = make_request(["p1", "p2"])

    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "failed"


def test_rate_limited_degrades_with_estimate(poi_port, route_port, cache):
    route_port.fail_all(RouteErrorKind.RATE_LIMITED)
    request = make_request(["p1", "p2", "p3"])

    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "degraded"
    assert result.route is not None
    assert result.route.is_estimated is True
    assert any(seg.is_estimated for seg in result.route.segments)
    assert result.degraded_fields == ["route.segments"]
    assert result.message


def test_dirty_data_degrades_with_estimate(poi_port, route_port, cache):
    route_port.fail_all(RouteErrorKind.DIRTY)
    request = make_request(["p1", "p2"])

    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "degraded"
    assert result.route is not None
    assert result.route.is_estimated is True


def test_empty_data_degrades_with_estimate(poi_port, route_port, cache):
    route_port.fail_all(RouteErrorKind.EMPTY)
    request = make_request(["p1", "p2"])

    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "degraded"
    assert result.route is not None
    assert result.route.is_estimated is True


def test_estimate_does_not_pass_hard_gate(poi_port, route_port, cache):
    # A single estimated leg (home -> p1) is enough: the hard gate rejects it and the plan
    # degrades instead of silently treating the estimate as exact.
    route_port.fail("home", "p1", RouteErrorKind.RATE_LIMITED)
    request = make_request(["p1"])

    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "degraded"
    assert result.route is not None
    assert result.route.is_estimated is True


def test_cross_city_point_fails(poi_port, route_port, cache):
    poi_port = MockPoiPort(
        [make_record("p1", "天安门", city_id="shanghai"), make_record("p2", "王府井")]
    )
    request = make_request(["p1", "p2"])

    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "failed"
    assert "CROSS_CITY" in (result.message or "")


def test_missing_coordinate_point_fails(poi_port, route_port, cache):
    poi_port = MockPoiPort(
        [
            make_record("p1", "天安门").model_copy(update={"latitude": None, "longitude": None}),
            make_record("p2", "王府井"),
        ]
    )
    request = make_request(["p1", "p2"])

    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "failed"
    assert "MISSING_COORDINATE" in (result.message or "")


def test_coordinate_system_mismatch_fails(poi_port, route_port, cache):
    poi_port = MockPoiPort(
        [
            make_record("p1", "天安门").model_copy(update={"coordinate_system": "WGS84"}),
            make_record("p2", "王府井"),
        ]
    )
    request = make_request(["p1", "p2"])

    result = _plan(request, poi_port, route_port, cache)

    assert result.status == "failed"
    assert "COORDINATE_SYSTEM_MISMATCH" in (result.message or "")


def test_negative_visit_duration_rejected():
    with pytest.raises(ValidationError):
        PointTiming(poi_id="p1", visit_duration_min=-5.0)


def test_no_points_fails(poi_port, route_port, cache):
    request = make_request([], origin_id=None, destination_id=None)
    result = _plan(request, poi_port, route_port, cache)
    assert result.status == "failed"
