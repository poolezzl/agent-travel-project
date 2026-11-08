"""S11 mounting adapter for the ``route`` task step (task_step=ROUTING).

S11 calls ``MockAgentExecutor.execute(taskId, stepName, attemptNo)`` with ``stepName="route"``
(``TaskWorkDefinitions``) and persists the returned ``WorkerResult(status, result,
degradedFields)`` under the unique key ``(task_id, step_id, attempt_no)`` plus a canonical
JSON hash. This module is the Python counterpart of that executor call: it runs ``plan_route``
per day and projects the result.

Envelope system fields (``schema_version``/``task_id``/``trace_id``/``request_id``/
``generated_at``) are injected by the S11 worker / gateway — never by ``plan_route``. Only
``source_metadata`` is projected here, from each segment's ``source_record_id`` /
``retrieved_at`` / ``freshness_status`` / ``is_estimated``.

Idempotency: the payload is a pure function of the day requests, the injected ports, and the
retrieved-at values held by the ALG-14 cache. For the same ``(task_id, day, attempt_no)`` the
bytes are identical; ``fingerprint`` exposes the canonical hash S11 records.
"""

from __future__ import annotations

import hashlib
import json
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

from app.agents.route_planner.models import RouteOutput, RoutePlanRequest, RouteSegment
from app.agents.route_planner.planner import plan_route
from app.agents.route_planner.ports import PoiPort
from app.agents.route_planner.route_provider import RouteProvider

StepStatus = Literal["ok", "partial", "degraded", "failed"]
DayStatus = Literal["ok", "degraded", "failed"]


class DayRouteOutcome(BaseModel):
    """One day's route result inside the S11 step payload."""

    day: str
    status: DayStatus
    message: str | None = None
    error_code: str | None = None
    degraded_fields: list[str] = Field(default_factory=list)
    route: RouteOutput | None = None


class RouteStepResult(BaseModel):
    """``WorkerResult.result`` payload for the route step (envelope fields excluded)."""

    status: StepStatus
    message: str | None = None
    error_code: str | None = None
    degraded_fields: list[str] = Field(default_factory=list)
    days: list[DayRouteOutcome]
    source_metadata: list[dict[str, object]] = Field(default_factory=list)
    fingerprint: str


def run_route_step(
    task_id: str,
    attempt_no: int,
    day_requests: list[RoutePlanRequest],
    poi_port: PoiPort,
    route_provider: RouteProvider,
) -> RouteStepResult:
    """Run ``plan_route`` for each day and aggregate to one S11 step payload.

    ``task_id``/``attempt_no`` participate only in the fingerprint: the payload itself is
    already deterministic for identical inputs, and S11 owns the uniqueness key.
    """
    outcomes = [_run_day(request, poi_port, route_provider) for request in day_requests]
    status, message, error_code, degraded_fields = _aggregate(outcomes)
    source_metadata = _project_source_metadata(outcomes)

    fingerprint = _fingerprint(
        task_id,
        attempt_no,
        {
            "status": status,
            "message": message,
            "error_code": error_code,
            "degraded_fields": degraded_fields,
            "days": [outcome.model_dump(mode="json") for outcome in outcomes],
            "source_metadata": source_metadata,
        },
    )
    return RouteStepResult(
        status=status,
        message=message,
        error_code=error_code,
        degraded_fields=degraded_fields,
        days=outcomes,
        source_metadata=source_metadata,
        fingerprint=fingerprint,
    )


def _run_day(
    request: RoutePlanRequest,
    poi_port: PoiPort,
    route_provider: RouteProvider,
) -> DayRouteOutcome:
    result = plan_route(request, poi_port, route_provider)
    return DayRouteOutcome(
        day=request.date,
        status=result.status,
        message=result.message,
        error_code=result.error_code,
        degraded_fields=result.degraded_fields,
        route=result.route,
    )


def _aggregate(
    outcomes: list[DayRouteOutcome],
) -> tuple[StepStatus, str | None, str | None, list[str]]:
    if not outcomes:
        return "failed", "no day requests supplied", "INVALID_INPUT", []

    failed = [outcome for outcome in outcomes if outcome.status == "failed"]
    degraded = [outcome for outcome in outcomes if outcome.status == "degraded"]

    if len(failed) == len(outcomes):
        # Whole step failed: the envelope requires error_code + message.
        return (
            "failed",
            _join_messages(failed) or "route planning failed",
            failed[0].error_code or "SOURCE_UNAVAILABLE",
            [],
        )

    if failed:
        # Some days failed, some succeeded: partial. degraded_fields must be non-empty.
        return (
            "partial",
            _join_messages(failed),
            None,
            [f"day:{outcome.day}" for outcome in failed] + _reroute_fields(degraded),
        )

    if degraded:
        return (
            "degraded",
            _join_messages(degraded),
            None,
            _reroute_fields(degraded),
        )

    return "ok", None, None, []


def _reroute_fields(degraded: list[DayRouteOutcome]) -> list[str]:
    fields = [field for outcome in degraded for field in outcome.degraded_fields]
    if not fields:
        fields = ["route.segments"]
    return sorted(set(fields))


def _join_messages(outcomes: list[DayRouteOutcome]) -> str | None:
    messages = [outcome.message for outcome in outcomes if outcome.message]
    return "; ".join(messages) if messages else None


def _project_source_metadata(outcomes: list[DayRouteOutcome]) -> list[dict[str, object]]:
    """Project segment sources into the output-schema ``source_metadata`` items.

    Exact segments come from the map supplier (``source_type="map"``); straight-line
    estimates come from the system (``source_type="system"``) and are flagged estimated.
    """
    items: list[dict[str, object]] = []
    seen: set[tuple[str, str]] = set()
    for outcome in outcomes:
        if outcome.route is None:
            continue
        for segment in outcome.route.segments:
            item = _source_item(segment)
            key = (str(item["source_type"]), str(item["source_record_id"]))
            if key in seen:
                continue
            seen.add(key)
            items.append(item)
    return items


def _source_item(segment: RouteSegment) -> dict[str, object]:
    return {
        "source_type": "system" if segment.is_estimated else "map",
        "source_record_id": segment.source_record_id,
        "retrieved_at": _as_utc_iso(segment.retrieved_at),
        "valid_until": None,
        "freshness_status": "unknown" if segment.is_estimated else "fresh",
        "is_estimated": segment.is_estimated,
    }


def _as_utc_iso(value: datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")


def _fingerprint(task_id: str, attempt_no: int, payload: dict[str, object]) -> str:
    canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(f"{task_id}:{attempt_no}:{canonical}".encode()).hexdigest()
