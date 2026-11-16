"""S19 stage dispatch and envelope assembly."""

from __future__ import annotations

from typing import Any

from app.agents.common import envelope, failure, new_context
from app.orchestrator.assemble import run_assemble
from app.orchestrator.collect import run_collect
from app.orchestrator.common import StageInputError
from app.orchestrator.deps import OrchestratorDeps
from app.orchestrator.request import parse_request
from app.orchestrator.routes import run_routes
from app.orchestrator.schedule import run_schedule

STEPS = ("parse", "collect", "schedule", "route", "assemble")


def run_stage(
    step: str,
    body: dict[str, Any],
    deps: OrchestratorDeps,
    *,
    context: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Run one fixed-DAG stage and return an envelope-ready payload."""
    ctx = context or _context(body)
    if step not in STEPS:
        return failure(ctx, "INVALID_STEP", f"未知编排步骤 {step!r}")

    raw_request = body.get("request")
    prior = body.get("prior") if isinstance(body.get("prior"), dict) else {}

    try:
        if step == "parse":
            parsed = parse_request(raw_request, today=deps.now().date())
            return envelope(
                ctx,
                status="ok",
                message="需求解析与预检完成",
                degraded_fields=[],
                source_metadata=[],
                stage="parse",
                output=parsed,
            )

        constraints = _constraints(raw_request, prior, deps)
        if step == "collect":
            collected = run_collect(constraints, deps, ctx)
            return envelope(
                ctx,
                status=collected["status"],
                message=collected["message"],
                degraded_fields=collected["degraded_fields"],
                source_metadata=collected["source_metadata"],
                stage="collect",
                output=collected,
            )

        collected = _prior_output(prior, "collect")
        if step == "schedule":
            scheduled = run_schedule(constraints, collected, max_points=deps.max_points_per_day)
            payload: dict[str, Any] = {
                "status": scheduled["status"],
                "message": scheduled["message"],
                "degraded_fields": scheduled["degraded_fields"],
                "source_metadata": collected.get("source_metadata", []),
                "stage": "schedule",
                "output": scheduled,
            }
            if scheduled["status"] == "failed":
                payload["error_code"] = scheduled.get("error_code", "SCHEDULE_FAILED")
            return envelope(ctx, **payload)

        scheduled = _prior_output(prior, "schedule")
        if step == "route":
            routed = run_routes(constraints, collected, scheduled, deps=deps)
            sources = list(collected.get("source_metadata", []))
            for day in routed["days"]:
                for segment in day["segments"]:
                    transport = segment.get("transport_to_next")
                    if transport:
                        sources.append(
                            {
                                "source_type": "system" if transport["is_estimated"] else "map",
                                "source_record_id": transport["source_record_id"],
                                "retrieved_at": transport["retrieved_at"],
                                "valid_until": None,
                                "freshness_status": "unknown"
                                if transport["is_estimated"]
                                else "fresh",
                                "is_estimated": bool(transport["is_estimated"]),
                            }
                        )
            return envelope(
                ctx,
                status=routed["status"],
                message=routed["message"],
                degraded_fields=routed["degraded_fields"],
                source_metadata=sources,
                stage="route",
                output=routed,
            )

        routed = _prior_output(prior, "route")
        assembled = run_assemble(constraints, collected, scheduled, routed, deps, ctx)
        if assembled["status"] == "failed":
            return envelope(
                ctx,
                status="failed",
                error_code="QUALITY_CHECK_FAILED",
                message=assembled["message"],
                source_metadata=[],
            )
        plan = assembled["plan"]
        if assembled["status"] == "degraded" and plan["confirmation"]["required"]:
            message = "方案存在需用户确认的硬冲突，确认前不会创建不可变版本"
        else:
            message = assembled["message"]
        return envelope(
            ctx,
            status=assembled["status"],
            message=message,
            degraded_fields=assembled["degraded_fields"],
            source_metadata=plan["sources"],
            plan=plan,
        )
    except StageInputError as exc:
        return failure(ctx, "INVALID_INPUT", str(exc))


def _context(body: dict[str, Any]) -> dict[str, Any]:
    context = new_context()
    for key in ("schema_version", "task_id", "trace_id", "request_id", "generated_at"):
        value = body.get(key)
        if isinstance(value, str) and value:
            context[key] = value
    return context


def _constraints(raw_request: Any, prior: dict[str, Any], deps: OrchestratorDeps) -> dict[str, Any]:
    parsed = prior.get("parse")
    if isinstance(parsed, dict):
        output = parsed.get("output")
        if isinstance(output, dict) and isinstance(output.get("constraints"), dict):
            return output["constraints"]
    return parse_request(raw_request, today=deps.now().date())["constraints"]


def _prior_output(prior: dict[str, Any], step: str) -> dict[str, Any]:
    entry = prior.get(step)
    if not isinstance(entry, dict):
        raise StageInputError(f"缺少上游步骤 {step} 的结果")
    output = entry.get("output")
    if not isinstance(output, dict):
        raise StageInputError(f"上游步骤 {step} 的结果结构非法")
    return output


__all__ = ["run_stage", "STEPS"]
