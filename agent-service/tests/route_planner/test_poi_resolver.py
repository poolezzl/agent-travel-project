"""ALG-04 POI disambiguation tests: no guessing, no name-as-key, no auto-merge."""

from __future__ import annotations

from app.agents.route_planner.errors import ResolutionCode
from app.agents.route_planner.mocks import MockPoiPort
from app.agents.route_planner.models import PointCandidate, PoiRecord
from app.agents.route_planner.poi_resolver import (
    Clarify,
    Resolved,
    ResolveFail,
    resolve_poi,
)


def _record(
    poi_id: str,
    name: str,
    city_id: str = "beijing",
    lat: float = 39.9,
    lng: float = 116.3,
    system: str = "GCJ02",
) -> PoiRecord:
    return PoiRecord(
        poi_id=poi_id,
        name=name,
        city_id=city_id,
        latitude=lat,
        longitude=lng,
        coordinate_system=system,
    )


def test_resolve_by_unique_name():
    port = MockPoiPort([_record("p1", "天安门")])
    outcome = resolve_poi(PointCandidate(name="天安门"), "beijing", port)
    assert isinstance(outcome, Resolved)
    assert outcome.point.poi_id == "p1"
    assert outcome.point.coordinate_system == "GCJ02"


def test_resolve_by_poi_id_uses_port_not_candidate():
    port = MockPoiPort([_record("p1", "天安门")])
    outcome = resolve_poi(PointCandidate(poi_id="p1", name="天安门"), "beijing", port)
    assert isinstance(outcome, Resolved)
    assert outcome.point.poi_id == "p1"


def test_no_match_is_source_unavailable():
    port = MockPoiPort([_record("p1", "天安门")])
    outcome = resolve_poi(PointCandidate(name="不存在"), "beijing", port)
    assert isinstance(outcome, ResolveFail)
    assert outcome.code == ResolutionCode.SOURCE_UNAVAILABLE


def test_missing_name_clarifies():
    port = MockPoiPort([_record("p1", "天安门")])
    outcome = resolve_poi(PointCandidate(poi_id="p1"), "beijing", port)
    assert isinstance(outcome, Clarify)
    assert outcome.code == ResolutionCode.MISSING_CONTEXT


def test_ambiguous_name_returns_candidates_for_clarification():
    port = MockPoiPort([_record("p1", "天安门"), _record("p2", "天安门")])
    outcome = resolve_poi(PointCandidate(name="天安门"), "beijing", port)
    assert isinstance(outcome, Clarify)
    assert outcome.code == ResolutionCode.AMBIGUOUS_NAME
    assert {c.poi_id for c in outcome.candidates} == {"p1", "p2"}


def test_cross_city_fails():
    port = MockPoiPort([_record("p1", "天安门", city_id="shanghai")])
    outcome = resolve_poi(PointCandidate(poi_id="p1", name="天安门"), "beijing", port)
    assert isinstance(outcome, ResolveFail)
    assert outcome.code == ResolutionCode.CROSS_CITY


def test_missing_coordinate_fails():
    port = MockPoiPort([_record("p1", "天安门", lat=None, lng=None)])
    outcome = resolve_poi(PointCandidate(name="天安门"), "beijing", port)
    assert isinstance(outcome, ResolveFail)
    assert outcome.code == ResolutionCode.MISSING_COORDINATE


def test_coordinate_system_mismatch_fails():
    port = MockPoiPort([_record("p1", "天安门", system="WGS84")])
    outcome = resolve_poi(PointCandidate(name="天安门"), "beijing", port)
    assert isinstance(outcome, ResolveFail)
    assert outcome.code == ResolutionCode.COORDINATE_SYSTEM_MISMATCH
