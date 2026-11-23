"""S22 runner: execute one fixed case through the frozen DAG plus local revision.

The runner produces two things:

1. a *redacted* run record in the exact contract consumed by ``metrics.py``
   (case/version, hard-constraint results, fact slots with source + freshness +
   estimate flags, route feasibility, schema validity, latency, cost, degraded
   fields and local-modification hashes);
2. an observation map used by the S22 gate to check the behaviour the case
   contract declares (``expected_behaviors``).

No prompt, private itinerary or supplier payload is stored; the record only keeps
synthetic source ids, booleans, counts and hashes.
"""
from __future__ import annotations

import hashlib
import json
import sys
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
SERVICE_ROOT = REPO_ROOT / "agent-service"
for _path in (SERVICE_ROOT, Path(__file__).resolve().parent):
    if str(_path) not in sys.path:  # noqa: E402 - runner is importable both ways
        sys.path.insert(0, str(_path))

from app.agents.revision_execution import apply as apply_revision  # noqa: E402
from app.agents.common import new_context  # noqa: E402
from app.orchestrator.service import STEPS, run_stage  # noqa: E402
from jsonschema import Draft7Validator  # noqa: E402

from s22_fixtures import (  # noqa: E402
    REFERENCE_TIME,
    CaseBundle,
    CaseSpec,
    build_bundle,
    day_index_from_block,
    segment_position_from_block,
)

PLAN_SCHEMA_PATH = REPO_ROOT / "contracts" / "s19" / "plan.schema.json"
PLAN_SCHEMA = json.loads(PLAN_SCHEMA_PATH.read_text(encoding="utf-8"))

ROUTE_BLOCKING_CODES = {"ROUTE_INFEASIBLE", "DAY_OVERFLOW"}
MUST_BLOCKING_CODES = {"MUST_VISIT_CLOSED", "MUST_VISIT_MISSING", "MUST_VISIT_UNRESOLVED",
                       "MUST_VISIT_AMBIGUOUS", "MUST_VISIT_NO_COORDINATE"}
_KNOWN_DEGRADED_PREFIXES = (
    "weather.",
    "days",
    "meals",
    "lodging",
    "cards[",
    "points[",
    "must_visit",
    "revision.",
    "reservations[",
    "timeline[",
    "dates",
)

LOCAL_BEHAVIORS = frozenset(
    {
        "local_point_preserved",
        "local_day_scope",
        "cross_day_scope",
        "local_modification_fidelity",
    }
)

# Cases that declare a local-modification behaviour but no block list keep the
# contract of their sibling cases: day 1 segment 3 changes, days 2 and 3 must not.
_DEFAULT_LOCAL_AFFECTED = ["days[0].segments[2]"]
_DEFAULT_LOCAL_UNCHANGED = ["days[1]", "days[2]"]


@dataclass
class LocalOutcome:
    """Result of running the S20 local-revision path for one case."""

    status: str
    latency_ms: float
    unchanged_blocks: list[dict[str, str]] = field(default_factory=list)
    changed_days: list[int] = field(default_factory=list)
    error: str | None = None

    @property
    def preservation_rate(self) -> float:
        compared = len(self.unchanged_blocks)
        if not compared:
            return 1.0 if self.status != "failed" else 0.0
        preserved = sum(
            block["before_hash"] == block["after_hash"] for block in self.unchanged_blocks
        )
        return preserved / compared


@dataclass
class CaseRun:
    """One executed case: redacted record + gate observations."""

    record: dict[str, Any]
    observations: dict[str, Any]
    envelopes: dict[str, dict[str, Any]]
    plan: dict[str, Any] | None
    local: LocalOutcome | None


def run_dag(
    bundle: CaseBundle,
) -> tuple[dict[str, dict[str, Any]], float, str | None]:
    """Run the five fixed stages in order; stop at the first failed stage."""
    envelopes: dict[str, dict[str, Any]] = {}
    prior: dict[str, dict[str, Any]] = {}
    started = time.perf_counter()
    for step in STEPS:
        envelope = run_stage(
            step,
            {
                "task_id": f"eval-{bundle.case.case_id}",
                "trace_id": f"trace-{bundle.case.case_id}",
                "request_id": f"request-{bundle.case.case_id}",
                "schema_version": "s19-envelope-v1",
                "request": bundle.request,
                "prior": prior,
            },
            bundle.deps,
        )
        envelopes[step] = envelope
        prior[step] = envelope
        if envelope.get("status") == "failed":
            elapsed = (time.perf_counter() - started) * 1000.0
            return envelopes, elapsed, f"{step}:{envelope.get('error_code')}"
    elapsed = (time.perf_counter() - started) * 1000.0
    return envelopes, elapsed, None


def run_case(case: CaseSpec) -> CaseRun:
    """Execute one case and derive metrics record plus behaviour observations."""
    bundle = build_bundle(case)
    envelopes, elapsed_ms, dag_error = run_dag(bundle)
    assembled = envelopes.get("assemble") or {}
    plan = assembled.get("plan") if isinstance(assembled.get("plan"), dict) else None

    schema_valid = False
    schema_error = None
    if plan is not None and dag_error is None:
        errors = sorted(Draft7Validator(PLAN_SCHEMA).iter_errors(plan), key=lambda e: list(e.path))
        schema_valid = not errors
        schema_error = errors[0].message if errors else None

    facts = _facts(plan, bundle)
    stale_total = sum(1 for fact in facts if fact["freshness_status"] == "stale")
    stale_displayed = sum(
        1 for fact in facts if fact["freshness_status"] == "stale" and fact["displayed_status"] == "stale"
    )
    estimated_total = sum(1 for fact in facts if fact["is_estimated"])
    estimated_visible = _estimates_visible(plan)
    degraded_fields = list((plan or {}).get("degraded_fields") or assembled.get("degraded_fields") or [])
    degradation_explained = _degradation_explained(plan, assembled, degraded_fields)

    local = _run_local_revision(bundle, plan) if needs_local_revision(case) and plan else None

    observations = _observations(
        bundle=bundle,
        plan=plan,
        assembled=assembled,
        dag_error=dag_error,
        schema_valid=schema_valid,
        stale_total=stale_total,
        stale_displayed=stale_displayed,
        estimated_total=estimated_total,
        estimated_visible=estimated_visible,
        degraded_fields=degraded_fields,
        degradation_explained=degradation_explained,
        local=local,
        facts=facts,
    )
    observations["case_contract_ok"] = _case_contract_ok(case, observations)
    observations["source_whitelist_ok"] = _whitelist_ok(case, facts)
    constraints = _constraint_results(case, observations)
    record = {
        "run_id": f"{case.case_id}-v{case.case_version}-s22",
        "case_id": case.case_id,
        "case_version": case.case_version,
        "hard_constraints": constraints,
        "facts": facts,
        "route": {"feasible": observations["route_feasible"]},
        "schema_valid": schema_valid,
        "latency_ms": round(elapsed_ms, 3),
        "cost": {"token_count": 0, "api_cost_minor": 0.0},
        "degraded_fields": degraded_fields,
        "degradation_explained": degradation_explained,
        "source_whitelist_ok": observations["source_whitelist_ok"],
        "local_modification": (
            {
                "unchanged_blocks": local.unchanged_blocks,
                "changed_days": local.changed_days,
            }
            if local is not None
            else None
        ),
        "plan_status": (plan or {}).get("quality", {}).get("status"),
        "confirmation_required": bool(((plan or {}).get("confirmation") or {}).get("required")),
        "local_modification_latency_ms": round(local.latency_ms, 3) if local else None,
        "schema_error": schema_error,
    }
    observations["pass"] = bool(
        dag_error is None
        and schema_valid
        and observations["source_whitelist_ok"]
        and observations["case_contract_ok"]
        and all(item["satisfied"] for item in constraints)
    )
    observations["dag_error"] = dag_error
    return CaseRun(
        record=record,
        observations=observations,
        envelopes=envelopes,
        plan=plan,
        local=local,
    )


def _facts(plan: dict[str, Any] | None, bundle: CaseBundle) -> list[dict[str, Any]]:
    """Project the plan onto fact slots: every slot keeps a source and a display state."""
    if not plan:
        return []
    freshness = {
        str(item.get("source_record_id")): str(item.get("freshness_status") or "unknown")
        for item in plan.get("sources") or []
        if item.get("source_record_id")
    }
    stale_marked = bool(
        (plan.get("summary") or {}).get("stale_fields")
    ) or any(
        token in str(field)
        for field in plan.get("degraded_fields") or []
        for token in ("freshness_status", "weather.", ".stale")
    )
    rules = {
        rule.policy_id: rule.source.source_record_id for rule in bundle.catalog.rules
    }
    facts: list[dict[str, Any]] = []
    for day in plan.get("days") or []:
        weather = day.get("weather") or {}
        status = "ok" if weather.get("status") == "ok" else "unknown"
        facts.append(
            {
                "slot": f"days[{day.get('date')}].weather",
                "source_record_id": str(weather.get("source_record_id") or ""),
                "freshness_status": "stale" if weather.get("stale") else status,
                "is_estimated": False,
                "displayed_status": "stale" if weather.get("stale") else status,
            }
        )
        for segment in day.get("segments") or []:
            for fact in segment.get("facts") or []:
                source_id = str(fact.get("source_record_id") or "")
                state = freshness.get(source_id, "unknown")
                shown = "stale" if state == "stale" and stale_marked else ("stale" if state == "stale" else state)
                facts.append(
                    {
                        "slot": f"{day.get('date')}/{segment.get('point_id')}/{fact.get('chunk_id')}",
                        "source_record_id": source_id,
                        "freshness_status": state,
                        "is_estimated": False,
                        "displayed_status": shown,
                    }
                )
            transport = segment.get("transport_to_next")
            if isinstance(transport, dict):
                facts.append(
                    {
                        "slot": f"{day.get('date')}/{segment.get('point_id')}/transport",
                        "source_record_id": str(transport.get("source_record_id") or ""),
                        "freshness_status": "unknown" if transport.get("is_estimated") else "fresh",
                        "is_estimated": bool(transport.get("is_estimated")),
                        "displayed_status": "unknown" if transport.get("is_estimated") else "fresh",
                    }
                )
        for card in day.get("reservations") or []:
            policy_id = str(card.get("policy_id") or "")
            facts.append(
                {
                    "slot": f"{day.get('date')}/reservation/{policy_id}",
                    "source_record_id": rules.get(policy_id, ""),
                    "freshness_status": "stale" if card.get("stale") else "fresh",
                    "is_estimated": False,
                    "displayed_status": "stale" if card.get("stale") else "fresh",
                }
            )
    return facts


def _estimates_visible(plan: dict[str, Any] | None) -> bool:
    if not plan:
        return False
    legs = [
        segment.get("transport_to_next")
        for day in plan.get("days") or []
        for segment in day.get("segments") or []
        if segment.get("transport_to_next")
    ]
    if not legs:
        return True
    marked = all(bool(leg.get("is_estimated")) for leg in legs)
    surfaced = any(
        "days.segments.is_estimated" in str(field) or "transport_to_next" in str(field)
        for field in plan.get("degraded_fields") or []
    )
    return marked and surfaced


def _degradation_explained(
    plan: dict[str, Any] | None,
    assembled: dict[str, Any],
    degraded_fields: list[str],
) -> bool:
    if not degraded_fields:
        return True
    if plan is None:
        return False
    explained = all(
        str(field).startswith(_KNOWN_DEGRADED_PREFIXES) for field in degraded_fields
    )
    message = str(assembled.get("message") or "")
    summary_degraded = bool((plan.get("summary") or {}).get("degraded"))
    return explained and summary_degraded and bool(message)


def _summary_from_days(plan: dict[str, Any]) -> dict[str, float]:
    legs = [
        segment.get("transport_to_next")
        for day in plan.get("days") or []
        for segment in day.get("segments") or []
        if segment.get("transport_to_next")
    ]
    return {
        "point_count": float(sum(len(day.get("segments") or []) for day in plan.get("days") or [])),
        "estimated_legs": float(sum(bool(leg.get("is_estimated")) for leg in legs)),
        "unmarked_legs": float(sum(not bool(leg.get("is_estimated")) for leg in legs)),
    }


def _checks(plan: dict[str, Any] | None) -> dict[str, str]:
    if not plan:
        return {}
    quality = plan.get("quality") or {}
    return {
        str(check.get("code")): str(check.get("status"))
        for check in quality.get("checks") or []
        if isinstance(check, dict)
    }


def _conflict_codes(plan: dict[str, Any] | None) -> list[str]:
    if not plan:
        return []
    return [str(item.get("code")) for item in plan.get("conflicts") or []]


def _observations(
    *,
    bundle: CaseBundle,
    plan: dict[str, Any] | None,
    assembled: dict[str, Any],
    dag_error: str | None,
    schema_valid: bool,
    stale_total: int,
    stale_displayed: int,
    estimated_total: int,
    estimated_visible: bool,
    degraded_fields: list[str],
    degradation_explained: bool,
    local: LocalOutcome | None,
    facts: list[dict[str, Any]],
) -> dict[str, Any]:
    codes = _conflict_codes(plan)
    checks = _checks(plan)
    scheduled_ids = {
        str(segment.get("point_id"))
        for day in (plan or {}).get("days") or []
        for segment in day.get("segments") or []
    }
    confirmation_required = bool(((plan or {}).get("confirmation") or {}).get("required"))
    completeness = float(((plan or {}).get("summary") or {}).get("source_completeness") or 0.0)
    must_ids = set(bundle.must_visit_poi_ids)
    must_scheduled = must_ids <= scheduled_ids if must_ids else False
    must_blocked_with_confirmation = bool(must_ids & set(_affected_points(plan))) and confirmation_required
    behaviors = bundle.case.behavior_set

    observations: dict[str, Any] = {
        "plan_present": plan is not None,
        "schema_valid": schema_valid,
        "dag_error": dag_error,
        "confirmation_required": confirmation_required,
        "conflict_codes": codes,
        "quality_checks": checks,
        "source_completeness": completeness,
        "source_complete": completeness >= 1.0 and bool(facts),
        "stale_total": stale_total,
        "stale_displayed": stale_displayed,
        "stale_visible": stale_total > 0 and stale_displayed == stale_total,
        "estimated_total": estimated_total,
        "estimated_visible": estimated_visible,
        "degraded_field_count": len(degraded_fields),
        "degradation_explained": degradation_explained,
        "must_visit_scheduled": must_scheduled,
        "must_visit_protected": must_scheduled or must_blocked_with_confirmation,
        "local_preservation_rate": local.preservation_rate if local else None,
        "local_status": local.status if local else None,
        "local_latency_ms": local.latency_ms if local else None,
        "route_feasible": _route_feasible(plan),
        "behaviors": {},
    }
    observations["behaviors"] = _behavior_results(
        behaviors=behaviors,
        bundle=bundle,
        plan=plan,
        codes=codes,
        checks=checks,
        scheduled_ids=scheduled_ids,
        confirmation_required=confirmation_required,
        observations=observations,
        local=local,
    )
    return observations


def _affected_points(plan: dict[str, Any] | None) -> set[str]:
    result: set[str] = set()
    for conflict in (plan or {}).get("conflicts") or []:
        for point in conflict.get("affected_points") or []:
            result.add(str(point))
    return result


def _route_feasible(plan: dict[str, Any] | None) -> bool:
    if not plan:
        return False
    codes = set(_conflict_codes(plan))
    if codes & ROUTE_BLOCKING_CODES:
        return False
    days = plan.get("days") or []
    if not days:
        return False
    all_segments = [segment for day in days for segment in day.get("segments") or []]
    if not all_segments:
        return False
    for day in days:
        for segment in day.get("segments") or []:
            transport = segment.get("transport_to_next")
            if transport is not None and not isinstance(transport, dict):
                return False
    return True


def _behavior_results(
    *,
    behaviors: set[str],
    bundle: CaseBundle,
    plan: dict[str, Any] | None,
    codes: list[str],
    checks: dict[str, str],
    scheduled_ids: set[str],
    confirmation_required: bool,
    observations: dict[str, Any],
    local: LocalOutcome | None,
) -> dict[str, bool]:
    results: dict[str, bool] = {}
    closed_points = {
        poi.poi_id for poi in bundle.catalog.pois if poi.closed_weekdays
    }
    same_name_ids = {
        poi.poi_id for poi in bundle.catalog.pois if poi.name == bundle.must_visit_name
    }
    scheduled = _summary_from_days(plan) if plan else {"point_count": 0.0, "unmarked_legs": 0.0}

    for behavior in sorted(behaviors):
        if behavior == "source_complete":
            results[behavior] = observations["source_complete"]
        elif behavior in {"stale_displayed", "stale_visible"}:
            results[behavior] = observations["stale_visible"]
        elif behavior == "forecast_window":
            results[behavior] = _weather_types(plan) == {"forecast"} and _weather_valid_until(plan)
        elif behavior == "climate_statistics":
            results[behavior] = _weather_types(plan) == {"climate_statistics"} and all(
                bool((day.get("weather") or {}).get("stale")) for day in (plan or {}).get("days") or []
            )
        elif behavior == "closed_conflict":
            results[behavior] = bool(
                confirmation_required
                and "MUST_VISIT_CLOSED" in codes
                and closed_points & _affected_points(plan)
                and not (closed_points & scheduled_ids)
            )
        elif behavior in {"same_name_poi", "manual_disambiguation"}:
            # Two published points share one name: the plan must keep them distinct
            # (never collapse them into a single point) and must ask the user.
            merged = len(same_name_ids & scheduled_ids) == 1
            results[behavior] = bool(
                len(same_name_ids) >= 2
                and "MUST_VISIT_AMBIGUOUS" in codes
                and confirmation_required
                and not merged
            )
        elif behavior == "unknown_fact":
            results[behavior] = _no_segment_facts(plan) and bool(observations["degraded_field_count"])
        elif behavior == "degraded_explained":
            results[behavior] = observations["degradation_explained"] and plan is not None
        elif behavior == "route_feasible":
            results[behavior] = (
                observations["route_feasible"]
                and scheduled["point_count"] > 0
                and scheduled["unmarked_legs"] == 0
            )
        elif behavior == "budget_respected":
            results[behavior] = checks.get("BUDGET") == "pass"
        elif behavior in {
            "local_point_preserved",
            "local_day_scope",
            "cross_day_scope",
            "local_modification_fidelity",
        }:
            results[behavior] = _local_ok(bundle, local)
        else:
            results[behavior] = False
    return results


def _local_ok(bundle: CaseBundle, local: LocalOutcome | None) -> bool:
    if local is None or local.status == "failed" or local.error:
        return False
    if local.preservation_rate < 1.0:
        return False
    affected, _ = _effective_blocks(bundle.case)
    declared = {day_index_from_block(block) + 1 for block in affected}
    return set(local.changed_days) == declared


def needs_local_revision(case: CaseSpec) -> bool:
    """A case needs the S20 revision path when it declares blocks or a local behaviour."""
    return bool(case.local_modification) or bool(LOCAL_BEHAVIORS & case.behavior_set)


def _effective_blocks(case: CaseSpec) -> tuple[list[str], list[str]]:
    local = case.local_modification or {}
    affected = [str(block) for block in local.get("affected_blocks") or []]
    unchanged = [str(block) for block in local.get("unchanged_blocks") or []]
    if affected:
        return affected, unchanged
    return list(_DEFAULT_LOCAL_AFFECTED), list(_DEFAULT_LOCAL_UNCHANGED)


def _no_segment_facts(plan: dict[str, Any] | None) -> bool:
    segments = [
        segment
        for day in (plan or {}).get("days") or []
        for segment in day.get("segments") or []
    ]
    return bool(segments) and all(not segment.get("facts") for segment in segments)


def _weather_types(plan: dict[str, Any] | None) -> set[str]:
    return {
        str((day.get("weather") or {}).get("data_type"))
        for day in (plan or {}).get("days") or []
    }


def _weather_valid_until(plan: dict[str, Any] | None) -> bool:
    for day in (plan or {}).get("days") or []:
        if not (day.get("weather") or {}).get("valid_until"):
            return False
    return True


def _case_contract_ok(case: CaseSpec, observations: dict[str, Any]) -> bool:
    behaviors = observations.get("behaviors") or {}
    return bool(behaviors) and all(behaviors.values())


def _constraint_results(
    case: CaseSpec, observations: dict[str, Any]
) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for constraint in case.constraints:
        constraint_id = str(constraint.get("id"))
        if constraint_id == "constraint-must-visit":
            satisfied = bool(observations["must_visit_protected"])
        elif constraint_id == "constraint-source":
            satisfied = bool(observations["source_complete"] and observations["source_whitelist_ok"])
        else:
            satisfied = bool(observations["case_contract_ok"])
        results.append({"id": constraint_id, "satisfied": satisfied})
    return results


def _run_local_revision(bundle: CaseBundle, plan: dict[str, Any]) -> LocalOutcome:
    """Apply one declared local modification through the S20 revision path."""
    changes, affected_days, agents = _local_request(bundle, plan)
    context = new_context()
    context["generated_at"] = REFERENCE_TIME.isoformat().replace("+00:00", "Z")
    inputs = {
        "current_plan": plan,
        "affected_days": affected_days,
        "affected_agents": agents,
        "changes": changes,
    }
    started = time.perf_counter()
    try:
        result = apply_revision(inputs, context, bundle.deps)
        latency = (time.perf_counter() - started) * 1000.0
    except Exception as exc:  # noqa: BLE001 - surfaced as a failed drill, never hidden
        return LocalOutcome(
            status="failed",
            latency_ms=(time.perf_counter() - started) * 1000.0,
            error=f"{type(exc).__name__}: {exc}",
        )
    if result.get("status") == "failed":
        return LocalOutcome(
            status="failed",
            latency_ms=latency,
            error=str(result.get("error_code") or "REVISION_FAILED"),
        )
    new_plan = result.get("plan") or {}
    before_days = plan.get("days") or []
    after_days = new_plan.get("days") or []
    unchanged: list[dict[str, str]] = []
    _, unchanged_blocks = _effective_blocks(bundle.case)
    for block in unchanged_blocks:
        index = day_index_from_block(str(block))
        unchanged.append(
            {
                "before_hash": _day_hash(before_days[index]) if index < len(before_days) else "",
                "after_hash": _day_hash(after_days[index]) if index < len(after_days) else "",
            }
        )
    changed = [
        index + 1
        for index in range(min(len(before_days), len(after_days)))
        if _day_hash(before_days[index]) != _day_hash(after_days[index])
    ]
    return LocalOutcome(
        status=str(result.get("status") or "ok"),
        latency_ms=latency,
        unchanged_blocks=unchanged,
        changed_days=changed,
    )


def _local_request(
    bundle: CaseBundle, plan: dict[str, Any]
) -> tuple[list[dict[str, Any]], list[int], list[str]]:
    """Translate the fixture's declared affected blocks into S20 revision changes."""
    behaviors = bundle.case.behavior_set
    all_agents = ["attractions", "reservation-rules", "route-planner", "dining-lodging"]
    days = plan.get("days") or []
    affected_blocks, _ = _effective_blocks(bundle.case)
    if "cross_day_scope" in behaviors:
        first = day_index_from_block(affected_blocks[0]) + 1
        second = day_index_from_block(affected_blocks[1]) + 1
        changes = [
            {
                "path": f"/days/{first}",
                "operation": "reorder",
                "value": {"swap_with_day": second},
            },
            {
                "path": f"/days/{second}",
                "operation": "reorder",
                "value": {"swap_with_day": first},
            },
        ]
        return changes, [first, second], all_agents

    block = affected_blocks[0]
    day_no = day_index_from_block(block) + 1
    position = segment_position_from_block(block) or 1
    target = _replacement_name(bundle, days[day_no - 1])
    changes = [
        {
            "path": f"/days/{day_no}/segments/{position}",
            "operation": "replace",
            "value": {"target_name": target},
        }
    ]
    return changes, [day_no], all_agents


def _replacement_name(bundle: CaseBundle, day: dict[str, Any]) -> str:
    scheduled = {segment.get("point_id") for segment in day.get("segments") or []}
    for poi in bundle.catalog.pois:
        if poi.poi_id not in scheduled and not poi.closed_weekdays:
            return poi.name
    raise ValueError(f"{bundle.case.case_id}: no replacement point available")


def _day_hash(day: Any) -> str:
    payload = json.dumps(day, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _whitelist_ok(case: CaseSpec, facts: list[dict[str, Any]]) -> bool:
    """Every cited fact must use a source record id the case explicitly allows.

    ``straight-line-estimate`` is the documented system marker for a computed leg, not a
    supplier fact; empty ids are already penalised by the source-completeness metric.
    """
    allowed = set(case.sources) | {"straight-line-estimate"}
    return all(
        not fact["source_record_id"] or fact["source_record_id"] in allowed
        for fact in facts
    )


def utc_timestamp() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


__all__ = [
    "CaseRun",
    "LocalOutcome",
    "PLAN_SCHEMA",
    "run_case",
    "run_dag",
    "utc_timestamp",
]