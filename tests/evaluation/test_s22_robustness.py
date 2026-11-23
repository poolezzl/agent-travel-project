"""S22 robustness drills at the orchestration boundary.

Every drill keeps the same contract as the gate: a degradation must stay visible and
must never be replaced by an invented fact.  These tests are offline and deterministic.
"""
from __future__ import annotations

import copy
import json
import sys
from dataclasses import replace
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from s22_fixtures import REFERENCE_TIME, build_bundle, load_cases  # noqa: E402
from s22_runner import PLAN_SCHEMA, run_dag  # noqa: E402
from app.agents.route_planner.errors import RouteErrorKind  # noqa: E402
from app.agents.route_planner.mocks import MockRouteCache, MockRoutePort  # noqa: E402
from app.agents.route_planner.route_provider import RouteProvider  # noqa: E402
from app.agents.weather.mocks import MockWeatherCache  # noqa: E402
from app.agents.weather.weather_provider import WeatherProvider  # noqa: E402
from app.knowledge.catalog import PublishedCatalog  # noqa: E402
from app.orchestrator.deps import OrchestratorDeps, UnavailableWeatherPort  # noqa: E402
from app.orchestrator.service import STEPS, run_stage  # noqa: E402
from jsonschema import Draft7Validator  # noqa: E402

CASE_ID = "BJ-01"


def _case(case_id: str = CASE_ID):
    return next(case for case in load_cases() if case.case_id == case_id)


def test_weather_outage_stays_visible_and_never_invents_forecast() -> None:
    bundle = build_bundle(_case())
    deps = replace(
        bundle.deps,
        weather_provider=WeatherProvider(
            UnavailableWeatherPort(), MockWeatherCache(), "Asia/Shanghai", now=lambda: REFERENCE_TIME
        ),
    )

    envelopes, _, error = _dag(bundle, deps)

    plan = envelopes["assemble"]["plan"]
    assert error is None
    Draft7Validator(PLAN_SCHEMA).validate(plan)
    assert envelopes["collect"]["status"] in {"partial", "degraded"}
    for day in plan["days"]:
        assert day["weather"]["status"] == "unknown"
        assert day["weather"]["source_record_id"] is None
    assert any(field.startswith("weather.") for field in plan["degraded_fields"])
    assert plan["summary"]["degraded"] is True


def test_route_outage_requires_confirmation_instead_of_dropping_must_visit() -> None:
    bundle = build_bundle(_case())
    port = MockRoutePort({})
    port.fail_all(RouteErrorKind.TIMEOUT)
    deps = replace(
        bundle.deps,
        route_provider_factory=lambda poi_port: RouteProvider(port, MockRouteCache()),
    )

    envelopes, _, error = _dag(bundle, deps)

    plan = envelopes["assemble"]["plan"]
    assert error is None
    codes = {conflict["code"] for conflict in plan["conflicts"]}
    assert "ROUTE_INFEASIBLE" in codes
    assert plan["confirmation"]["required"] is True
    assert envelopes["assemble"]["status"] == "degraded"
    assert any(
        conflict["affected_points"] for conflict in plan["conflicts"] if conflict["severity"] == "blocking"
    )


def test_missing_knowledge_and_rules_degrade_without_fabricating_facts() -> None:
    bundle = build_bundle(_case())
    empty = PublishedCatalog(
        catalog_version="s22-empty",
        pois=bundle.catalog.pois,
        entries=(),
        rules=(),
        sample_only=True,
    )
    deps = replace(bundle.deps, catalog=empty)

    envelopes, _, error = _dag(bundle, deps)

    plan = envelopes["assemble"]["plan"]
    assert error is None
    Draft7Validator(PLAN_SCHEMA).validate(plan)
    segments = [segment for day in plan["days"] for segment in day["segments"]]
    assert segments
    assert all(not segment["facts"] for segment in segments)
    assert any("cards[" in field or "points[" in field for field in plan["degraded_fields"])
    assert plan["summary"]["source_completeness"] == 0.0


def test_dining_source_failure_degrades_meals_and_lodging() -> None:
    bundle = build_bundle(_case())

    class BrokenPoiSource:
        def nearby(self, **_kwargs):
            raise RuntimeError("poi source unreachable")

    deps = replace(bundle.deps, poi_source=BrokenPoiSource())

    envelopes, _, error = _dag(bundle, deps)

    plan = envelopes["assemble"]["plan"]
    assert error is None
    assert all(not day["meals"] for day in plan["days"])
    assert all(not day["lodging"]["candidates"] for day in plan["days"])
    assert "meals" in plan["degraded_fields"] or "lodging" in plan["degraded_fields"]


def test_repeating_a_stage_with_identical_bytes_is_idempotent() -> None:
    bundle = build_bundle(_case())
    body = {"task_id": "s22-idempotent", "request": bundle.request, "prior": {}}

    first_parse = run_stage("parse", body, bundle.deps)
    second_parse = run_stage("parse", body, bundle.deps)

    # Envelope system fields (trace/request/generated_at) are injected per call; the stage
    # output itself must be byte-identical for identical input bytes.
    assert _stable(first_parse["output"]) == _stable(second_parse["output"])

    first_collect = run_stage("collect", {**body, "prior": {"parse": first_parse}}, bundle.deps)
    second_collect = run_stage("collect", {**body, "prior": {"parse": first_parse}}, bundle.deps)

    assert _stable(first_collect["output"]) == _stable(second_collect["output"])


def test_out_of_order_upstream_result_is_rejected() -> None:
    bundle = build_bundle(_case())
    body = {"task_id": "s22-order", "request": bundle.request}

    missing_collect = run_stage("route", {**body, "prior": {}}, bundle.deps)

    assert missing_collect["status"] == "failed"
    assert missing_collect["error_code"] == "INVALID_INPUT"


def test_unknown_stage_fails_closed_with_a_stable_code() -> None:
    bundle = build_bundle(_case())

    envelope = run_stage("teleport", {"task_id": "s22-step", "request": bundle.request}, bundle.deps)

    assert envelope["status"] == "failed"
    assert envelope["error_code"] == "INVALID_STEP"
    assert set(STEPS) == {"parse", "collect", "schedule", "route", "assemble"}


def _dag(bundle, deps: OrchestratorDeps):
    envelopes: dict[str, dict] = {}
    prior: dict[str, dict] = {}
    for step in STEPS:
        envelope = run_stage(
            step,
            {
                "task_id": f"drill-{bundle.case.case_id}",
                "trace_id": "drill-trace",
                "request_id": "drill-request",
                "schema_version": "s19-envelope-v1",
                "request": bundle.request,
                "prior": prior,
            },
            deps,
        )
        envelopes[step] = envelope
        prior[step] = envelope
        if envelope.get("status") == "failed":
            return envelopes, 0.0, f"{step}:{envelope.get('error_code')}"
    return envelopes, 0.0, None


def _stable(envelope):
    value = copy.deepcopy(envelope)
    value.pop("generated_at", None)
    return json.loads(json.dumps(value, ensure_ascii=False, sort_keys=True))


__all__ = [
    "test_weather_outage_stays_visible_and_never_invents_forecast",
    "test_route_outage_requires_confirmation_instead_of_dropping_must_visit",
    "test_missing_knowledge_and_rules_degrade_without_fabricating_facts",
    "test_dining_source_failure_degrades_meals_and_lodging",
    "test_repeating_a_stage_with_identical_bytes_is_idempotent",
    "test_out_of_order_upstream_result_is_rejected",
    "test_unknown_stage_fails_closed_with_a_stable_code",
]