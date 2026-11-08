"""S11 mounting: per-day aggregation, source_metadata projection and idempotency."""

from __future__ import annotations

from datetime import UTC, datetime

from app.agents.route_planner.errors import RouteErrorKind
from app.agents.route_planner.mocks import MockPoiPort, MockRouteCache, MockRoutePort
from app.agents.route_planner.models import (
    PointCandidate,
    PoiRecord,
    RoutePlanRequest,
    TimeWindow,
)
from app.agents.route_planner.route_provider import RouteProvider
from app.agents.route_planner.worker import run_route_step

TASK_ID = "task-1"
FIXED_NOW = datetime(2026, 9, 21, 8, 0, 0, tzinfo=UTC)
CITY = "beijing"

COORDS = {
    "home": (39.8997, 116.3912),
    "p1": (39.9042, 116.3574),
    "p2": (39.9146, 116.4074),
    "p3": (39.9367, 116.4171),
}


def _poi_port() -> MockPoiPort:
    records = [
        PoiRecord(
            poi_id=poi_id,
            name=name,
            city_id=CITY,
            latitude=lat,
            longitude=lon,
            coordinate_system="GCJ02",
        )
        for poi_id, (lat, lon), name in (
            ("home", COORDS["home"], "酒店"),
            ("p1", COORDS["p1"], "天安门"),
            ("p2", COORDS["p2"], "王府井"),
            ("p3", COORDS["p3"], "雍和宫"),
        )
    ]
    return MockPoiPort(records)


def _request(day: str, point_ids: tuple[str, ...] = ("p1", "p2")) -> RoutePlanRequest:
    names = {"p1": "天安门", "p2": "王府井", "p3": "雍和宫"}
    return RoutePlanRequest(
        date=day,
        city_id=CITY,
        coordinate_system="GCJ02",
        points=[
            PointCandidate(poi_id=poi_id, name=names[poi_id], city_id=CITY) for poi_id in point_ids
        ],
        origin=PointCandidate(poi_id="home", name="酒店", city_id=CITY),
        destination=PointCandidate(poi_id="home", name="酒店", city_id=CITY),
        transport_preference="打车",
        time_window=TimeWindow(start="09:00", end="18:00"),
    )


def _provider(port: MockRoutePort) -> RouteProvider:
    return RouteProvider(port, MockRouteCache(), now=lambda: FIXED_NOW)


def test_single_day_ok() -> None:
    result = run_route_step(
        TASK_ID, 1, [_request("2026-10-01")], _poi_port(), _provider(MockRoutePort(COORDS))
    )

    assert result.status == "ok"
    assert result.message is None
    assert result.degraded_fields == []
    assert [day.day for day in result.days] == ["2026-10-01"]
    assert result.days[0].route is not None
    assert result.days[0].route.is_estimated is False


def test_all_days_failed_returns_single_error_code() -> None:
    port = MockRoutePort(COORDS)
    port.fail_all(RouteErrorKind.AUTH)

    result = run_route_step(
        TASK_ID, 1, [_request("2026-10-01"), _request("2026-10-02")], _poi_port(), _provider(port)
    )

    assert result.status == "failed"
    assert result.error_code is not None
    assert result.message is not None
    assert len(result.days) == 2


def test_one_failed_day_is_partial_with_day_field() -> None:
    port = MockRoutePort(COORDS)
    port.fail("home", "p3", RouteErrorKind.AUTH)
    port.fail("p3", "home", RouteErrorKind.AUTH)

    result = run_route_step(
        TASK_ID,
        1,
        [_request("2026-10-01"), _request("2026-10-02", ("p3",))],
        _poi_port(),
        _provider(port),
    )

    assert result.status == "partial"
    assert result.message is not None
    assert "day:2026-10-02" in result.degraded_fields
    assert [day.status for day in result.days] == ["ok", "failed"]


def test_degraded_day_surfaces_route_segments_field() -> None:
    port = MockRoutePort(COORDS)
    port.fail_all(RouteErrorKind.RATE_LIMITED)

    result = run_route_step(TASK_ID, 1, [_request("2026-10-01")], _poi_port(), _provider(port))

    assert result.status == "degraded"
    assert result.message is not None
    assert "route.segments" in result.degraded_fields
    assert result.days[0].route is not None
    assert result.days[0].route.is_estimated is True


def test_source_metadata_projection_shape() -> None:
    result = run_route_step(
        TASK_ID, 1, [_request("2026-10-01")], _poi_port(), _provider(MockRoutePort(COORDS))
    )

    assert result.source_metadata
    for item in result.source_metadata:
        assert item["source_type"] == "map"
        assert item["retrieved_at"] == "2026-09-21T08:00:00Z"
        assert item["freshness_status"] == "fresh"
        assert item["is_estimated"] is False
        assert str(item["source_record_id"]).startswith("src-route:")
        assert item["valid_until"] is None


def test_estimated_segments_project_system_source() -> None:
    port = MockRoutePort(COORDS)
    port.fail_all(RouteErrorKind.RATE_LIMITED)

    result = run_route_step(TASK_ID, 1, [_request("2026-10-01")], _poi_port(), _provider(port))

    assert result.source_metadata
    assert {item["source_type"] for item in result.source_metadata} == {"system"}
    assert all(item["is_estimated"] is True for item in result.source_metadata)
    assert all(item["freshness_status"] == "unknown" for item in result.source_metadata)


def test_same_attempt_is_byte_identical() -> None:
    def run() -> str:
        return run_route_step(
            TASK_ID, 3, [_request("2026-10-01")], _poi_port(), _provider(MockRoutePort(COORDS))
        ).model_dump_json()

    assert run() == run()


def test_different_attempt_changes_only_fingerprint() -> None:
    def run(attempt: int) -> dict[str, object]:
        payload = run_route_step(
            TASK_ID,
            attempt,
            [_request("2026-10-01")],
            _poi_port(),
            _provider(MockRoutePort(COORDS)),
        ).model_dump(mode="json")
        payload.pop("fingerprint")
        return payload

    assert run(1) == run(2)


def test_no_days_is_invalid_input() -> None:
    result = run_route_step(TASK_ID, 1, [], _poi_port(), _provider(MockRoutePort(COORDS)))

    assert result.status == "failed"
    assert result.error_code == "INVALID_INPUT"
    assert result.days == []
