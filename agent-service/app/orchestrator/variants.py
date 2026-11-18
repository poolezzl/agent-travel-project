"""Generate concrete alternative plans using the one persisted collection result."""

from __future__ import annotations

import hashlib
import json
from copy import deepcopy
from datetime import datetime
from time import perf_counter
from typing import Any

from app.orchestrator.assemble import run_assemble
from app.orchestrator.collect import _duration
from app.orchestrator.common import StageInputError
from app.orchestrator.deps import OrchestratorDeps
from app.orchestrator.routes import run_routes
from app.orchestrator.schedule import run_schedule

_ALTERNATIVES = (
    ("relaxed", "低密度", "步行", 1),
    ("compact", "高密度", "公交", 6),
)


def fresh_collection(collected: dict[str, Any], now: datetime) -> dict[str, Any]:
    """Exclude expired facts once, before preparing any of the requested plans."""
    result = deepcopy(collected)
    sources = result.get("source_metadata", [])
    stale = {source["source_record_id"] for source in sources if _expired(source, now)}
    if not stale:
        return result
    result["source_metadata"] = [item for item in sources if item["source_record_id"] not in stale]
    for point in result["points"]:
        point["facts"] = [
            item for item in point.get("facts", []) if item.get("source_record_id") not in stale
        ]
        point["duration_min"], point["duration_source"] = _duration(point["facts"])
        rules = point.get("reservations", {})
        removed = [
            day
            for day, rule in rules.items()
            if rule.get("stale") or rule.get("source_record_id") in stale
        ]
        for day in removed:
            rules.pop(day)
            point.get("timelines", {}).pop(day, None)
    weather = result["weather"]
    weather["daily"] = [
        item for item in weather.get("daily", []) if item.get("source_record_id") not in stale
    ]
    result["degraded_fields"] = sorted(
        set(result.get("degraded_fields", [])) | {"expired_sources_omitted"}
    )
    return result


def generate_variants(
    constraints: dict[str, Any],
    collected: dict[str, Any],
    primary: dict[str, Any],
    primary_schedule: dict[str, Any],
    primary_routes: dict[str, Any],
    deps: OrchestratorDeps,
    context: dict[str, Any],
) -> list[dict[str, Any]]:
    count = constraints["plan_count"]
    if count == 1:
        return []
    now = deps.now()
    sources = collected.get("source_metadata", [])
    # Unknown or expired facts cannot serve as the common evidence for alternatively priced plans.
    if not sources or any(_expired(source, now) for source in sources):
        raise StageInputError("多方案需要同一批有效的采集来源，请刷新后重试")
    budget = constraints.get("budget")
    if budget and primary["summary"]["estimated_cost"]["amount_minor"] > budget["amount_minor"]:
        raise StageInputError("基准方案的已知门票费用超过预算")
    snapshot = _digest(collected)
    seen = {_signature(primary)}
    variants = [_entry(snapshot, "balanced", "基准方案", primary, None)]
    for strategy, label, transport, limit in _ALTERNATIVES:
        started = perf_counter()
        alternative = dict(constraints)
        if alternative["transport_preference"] == "混合":
            alternative["transport_preference"] = transport
        schedule = run_schedule(
            alternative,
            collected,
            max_points=min(limit, deps.max_points_per_day),
            prefer_lower_ticket_cost=strategy == "relaxed",
        )
        if schedule["status"] == "failed":
            continue
        cached_routes = {}
        if alternative["transport_preference"] == constraints["transport_preference"]:
            cached_routes = {
                (row["date"], tuple(row["point_ids"])): route
                for row, route in zip(primary_schedule["days"], primary_routes["days"], strict=True)
            }
        routes = run_routes(alternative, collected, schedule, deps=deps, cached_days=cached_routes)
        outcome = run_assemble(
            alternative,
            collected,
            schedule,
            routes,
            deps,
            context,
            reusable_days={day["date"]: day for day in primary["days"]},
        )
        if outcome["status"] in {"failed", "degraded"}:
            continue
        plan = outcome["plan"]
        if budget and plan["summary"]["estimated_cost"]["amount_minor"] > budget["amount_minor"]:
            continue
        signature = _signature(plan)
        if signature in seen:
            continue
        seen.add(signature)
        variants.append(
            _entry(snapshot, strategy, label, plan, round((perf_counter() - started) * 1000))
        )
        if len(variants) == count:
            return variants
    raise StageInputError("采集数据不足以生成所选数量的不同可行方案")


def _expired(source: dict[str, Any], now: datetime) -> bool:
    until = source.get("valid_until")
    return source.get("freshness_status") == "stale" or bool(
        until and datetime.fromisoformat(until.replace("Z", "+00:00")) <= now
    )


def _signature(plan: dict[str, Any]) -> str:
    return _digest(
        [
            (
                day["date"],
                [
                    (
                        segment["point_id"],
                        segment["start_time"],
                        segment["end_time"],
                        (segment.get("transport_to_next") or {}).get("mode"),
                    )
                    for segment in day["segments"]
                ],
            )
            for day in plan["days"]
        ]
    )


def _entry(
    snapshot: str, strategy: str, label: str, plan: dict[str, Any], duration_ms: int | None
) -> dict[str, Any]:
    summary = plan["summary"]
    checks = plan["quality"]["checks"]
    return {
        "variant_id": _digest([snapshot, _signature(plan)])[:32],
        "strategy": strategy,
        "difference_summary": (
            f"{label}，{summary['point_count']} 个点，"
            f"交通约 {summary['total_transport_min']:.0f} 分钟"
        ),
        "shared_snapshot_hash": snapshot,
        "quality_score": round(100 * sum(row["status"] == "pass" for row in checks) / len(checks))
        if checks
        else None,
        "strategy_duration_ms": duration_ms,
        "estimated_trip_cost": summary.get("estimated_cost"),
        "plan": plan,
    }


def _digest(value: Any) -> str:
    canonical = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode()).hexdigest()
