"""S19 fixed-DAG stage tests: contracts, conflict handling and degradation."""

from __future__ import annotations

import json
from datetime import date
from pathlib import Path
from typing import Any

import pytest
from app.agents.route_planner.errors import RouteErrorKind
from app.agents.route_planner.mocks import MockRouteCache, MockRoutePort
from app.agents.route_planner.route_provider import RouteProvider
from app.agents.weather.mocks import MockWeatherCache
from app.agents.weather.weather_provider import WeatherProvider
from app.knowledge.catalog import PublishedCatalog
from app.orchestrator.deps import OrchestratorDeps, UnavailableWeatherPort
from app.orchestrator.request import parse_request
from app.orchestrator.routes import run_routes
from app.orchestrator.service import run_stage
from jsonschema import Draft7Validator

ROOT = Path(__file__).resolve().parents[3]
PLAN_SCHEMA = json.loads(
    (ROOT / "contracts" / "s19" / "plan.schema.json").read_text(encoding="utf-8")
)
STEPS = ("parse", "collect", "schedule", "route", "assemble")


def _dag(deps: OrchestratorDeps, payload: dict[str, Any]) -> dict[str, dict[str, Any]]:
    prior: dict[str, dict[str, Any]] = {}
    for step in STEPS:
        body = {"task_id": "task-s19-test", "request": payload, "prior": prior}
        envelope = run_stage(step, body, deps)
        assert envelope["status"] != "failed", (step, envelope)
        prior[step] = envelope
    return prior


def test_parse_normalises_chinese_city_and_rejects_past_dates(deps: OrchestratorDeps) -> None:
    parsed = parse_request(
        {
            "city": "北京",
            "start_date": "2026-11-20",
            "end_date": "2026-11-22",
            "party_size": 2,
            "must_visit": ["故宫博物院"],
            "preferences": "历史、亲子",
        },
        today=date(2026, 11, 16),
    )

    assert parsed["constraints"]["city_id"] == "beijing"
    assert parsed["constraints"]["dates"] == ["2026-11-20", "2026-11-21", "2026-11-22"]
    assert parsed["constraints"]["preferences"] == ["历史", "亲子"]

    from app.orchestrator.common import StageInputError

    with pytest.raises(StageInputError):
        parse_request(
            {"city": "北京", "start_date": "2026-11-01", "end_date": "2026-11-03"},
            today=date(2026, 11, 16),
        )
    with pytest.raises(StageInputError):
        parse_request(
            {"city": "火星", "start_date": "2026-11-20", "end_date": "2026-11-22"},
            today=date(2026, 11, 16),
        )


def test_full_dag_plan_matches_the_frozen_contract(
    deps: OrchestratorDeps, request_payload: dict[str, Any]
) -> None:
    prior = _dag(deps, request_payload)

    plan = prior["assemble"]["plan"]
    Draft7Validator(PLAN_SCHEMA).validate(plan)
    assert plan["plan_version"] == "s19-plan-v1"
    assert plan["trip"]["city_id"] == "beijing"
    assert len(plan["days"]) == 3
    assert plan["sample_only"] is True
    assert plan["confirmation"]["required"] is False
    assert any(segment["facts"] for day in plan["days"] for segment in day["segments"])
    assert any(not segment["facts"] for day in plan["days"] for segment in day["segments"])
    assert prior["route"]["output"]["days"][0]["segments"][0]["transport_to_next"] is not None
    assert (
        prior["route"]["output"]["days"][0]["segments"][0]["transport_to_next"]["is_estimated"]
        is True
    )


def test_assembled_day_contains_meals_and_lodging_candidates(
    deps: OrchestratorDeps, request_payload: dict[str, Any]
) -> None:
    # A single attraction keeps both meal anchors and the lodging anchor near the
    # synthetic dining POIs; the three-attraction default can put the last anchor
    # outside the 1.5 km/3 km search radii, which is a valid degradation but not
    # what this regression test is about.
    raw = json.loads(
        (ROOT / "agent-service" / "resources" / "published_catalog.json").read_text(
            encoding="utf-8"
        )
    )
    raw["pois"] = [item for item in raw["pois"] if item["poi_id"] == "POI-BJ-001"]
    raw["entries"] = [item for item in raw["entries"] if item["poi_id"] == "POI-BJ-001"]
    raw["rules"] = [item for item in raw["rules"] if item["poi_id"] == "POI-BJ-001"]
    single = OrchestratorDeps(
        catalog=PublishedCatalog.from_payload(raw),
        poi_source=deps.poi_source,
        weather_provider=deps.weather_provider,
        now=deps.now,
        forecast_horizon_days=deps.forecast_horizon_days,
    )
    payload = {
        **request_payload,
        "start_date": "2026-11-24",
        "end_date": "2026-11-24",
    }

    prior = _dag(single, payload)
    day = prior["assemble"]["plan"]["days"][0]

    assert day["meals"]
    assert all(meal["candidates"] for meal in day["meals"])
    assert day["lodging"]["candidates"]


def test_closed_must_visit_requires_confirmation_instead_of_removal(
    deps: OrchestratorDeps, request_payload: dict[str, Any]
) -> None:
    payload = {
        **request_payload,
        "start_date": "2026-11-16",
        "end_date": "2026-11-16",
        "must_visit": ["故宫博物院"],
    }
    prior: dict[str, dict[str, Any]] = {}
    for step in STEPS[:-1]:
        envelope = run_stage(
            step, {"task_id": "task-closed", "request": payload, "prior": prior}, deps
        )
        assert envelope["status"] != "failed", (step, envelope)
        prior[step] = envelope

    scheduled = prior["schedule"]["output"]
    codes = [conflict["code"] for conflict in scheduled["conflicts"]]
    assert "MUST_VISIT_CLOSED" in codes

    assembled = run_stage(
        "assemble", {"task_id": "task-closed", "request": payload, "prior": prior}, deps
    )
    plan = assembled["plan"]
    assert assembled["status"] == "degraded"
    assert plan["confirmation"]["required"] is True
    assert "MUST_VISIT_CLOSED" in plan["confirmation"]["conflict_codes"]
    blockers = [conflict for conflict in plan["conflicts"] if conflict["severity"] == "blocking"]
    assert blockers and blockers[0]["options"]


def test_explicit_budget_cap_is_blocking(
    deps: OrchestratorDeps, request_payload: dict[str, Any]
) -> None:
    payload = {
        **request_payload,
        "start_date": "2026-11-20",
        "end_date": "2026-11-20",
        "budget": {"currency": "CNY", "amount_minor": 1000},
    }
    prior = _dag(deps, payload)
    plan = prior["assemble"]["plan"]

    assert plan["confirmation"]["required"] is True
    assert "BUDGET_EXCEEDED" in plan["confirmation"]["conflict_codes"]


def test_unavailable_weather_degrades_without_crashing(
    deps: OrchestratorDeps, request_payload: dict[str, Any]
) -> None:
    deps = OrchestratorDeps(
        catalog=deps.catalog,
        poi_source=deps.poi_source,
        weather_provider=WeatherProvider(
            UnavailableWeatherPort(), MockWeatherCache(), "Asia/Shanghai", now=deps.now
        ),
        now=deps.now,
        forecast_horizon_days=3,
    )

    parsed = run_stage("parse", {"request": request_payload}, deps)
    collected = run_stage(
        "collect",
        {"request": request_payload, "prior": {"parse": parsed}},
        deps,
    )

    assert collected["status"] in {"partial", "degraded"}
    output = collected["output"]
    assert output["weather"]["status"] == "failed" or output["weather"]["status"] == "partial"
    assert all(day["status"] == "unknown" for day in output["weather"]["daily"])


def test_empty_catalog_fails_closed_with_a_stable_code(
    deps: OrchestratorDeps, request_payload: dict[str, Any]
) -> None:
    empty = OrchestratorDeps(
        catalog=PublishedCatalog(catalog_version="empty", pois=(), entries=(), rules=()),
        poi_source=deps.poi_source,
        weather_provider=deps.weather_provider,
        now=deps.now,
    )

    parsed = run_stage("parse", {"request": request_payload}, empty)
    collected = run_stage(
        "collect", {"request": request_payload, "prior": {"parse": parsed}}, empty
    )
    scheduled = run_stage(
        "schedule",
        {"request": request_payload, "prior": {"parse": parsed, "collect": collected}},
        empty,
    )

    assert scheduled["status"] == "failed"
    assert scheduled["error_code"] == "NO_PUBLISHED_FACTS"


def test_unknown_step_and_missing_upstream_are_rejected(deps: OrchestratorDeps) -> None:
    unknown = run_stage("teleport", {"request": {}}, deps)
    assert unknown["status"] == "failed"
    assert unknown["error_code"] == "INVALID_STEP"

    missing = run_stage("route", {"request": {}}, deps)
    assert missing["status"] == "failed"
    assert missing["error_code"] == "INVALID_INPUT"


def test_route_stage_marks_every_unverified_leg_as_estimated(
    deps: OrchestratorDeps, request_payload: dict[str, Any]
) -> None:
    parsed = run_stage("parse", {"request": request_payload}, deps)
    collected = run_stage("collect", {"request": request_payload, "prior": {"parse": parsed}}, deps)
    scheduled = run_stage(
        "schedule",
        {"request": request_payload, "prior": {"parse": parsed, "collect": collected}},
        deps,
    )

    routed = run_routes(parsed["output"]["constraints"], collected["output"], scheduled["output"])
    legs = [
        segment["transport_to_next"]
        for day in routed["days"]
        for segment in day["segments"]
        if segment["transport_to_next"] is not None
    ]
    assert legs
    assert all(leg["is_estimated"] for leg in legs)
    assert all(leg["source_record_id"] == "straight-line-estimate" for leg in legs)


def test_route_provider_failure_blocks_protected_points(
    deps: OrchestratorDeps, request_payload: dict[str, Any]
) -> None:
    parsed = run_stage("parse", {"request": request_payload}, deps)
    collected = run_stage("collect", {"request": request_payload, "prior": {"parse": parsed}}, deps)
    scheduled = run_stage(
        "schedule",
        {"request": request_payload, "prior": {"parse": parsed, "collect": collected}},
        deps,
    )
    port = MockRoutePort({})
    port.fail_all(RouteErrorKind.TIMEOUT)
    failing = RouteProvider(port, MockRouteCache())

    routed = run_routes(
        parsed["output"]["constraints"],
        collected["output"],
        scheduled["output"],
        route_provider=failing,
    )

    assert routed["days"][0]["route_status"] == "failed"
    assert any(conflict["code"] == "ROUTE_INFEASIBLE" for conflict in routed["conflicts"])
    assert routed["status"] == "degraded"


def test_missing_knowledge_rules_and_weather_still_degrades_to_a_plan(
    deps: OrchestratorDeps, request_payload: dict[str, Any]
) -> None:
    bare = OrchestratorDeps(
        catalog=PublishedCatalog(
            catalog_version="bare",
            pois=deps.catalog.pois,
            entries=(),
            rules=(),
            sample_only=True,
        ),
        poi_source=deps.poi_source,
        weather_provider=WeatherProvider(
            UnavailableWeatherPort(), MockWeatherCache(), "Asia/Shanghai", now=deps.now
        ),
        now=deps.now,
        forecast_horizon_days=3,
    )

    prior = _dag(bare, request_payload)

    plan = prior["assemble"]["plan"]
    Draft7Validator(PLAN_SCHEMA).validate(plan)
    assert plan["days"]
    assert any(
        segment["duration_source"] == "assumed"
        for day in plan["days"]
        for segment in day["segments"]
    )
    assert plan["summary"]["degraded"] is True
    assert plan["degraded_fields"]


def test_plan_example_matches_the_contract() -> None:
    example = json.loads(
        (ROOT / "contracts" / "examples" / "s19" / "plan-ok.json").read_text(encoding="utf-8")
    )
    Draft7Validator(PLAN_SCHEMA).validate(example)
