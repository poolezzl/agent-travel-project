"""S19 stage 6: integrate sub-agent outputs into the final immutable-plan candidate."""

from __future__ import annotations

from datetime import date
from typing import Any

from app.agents import dining_lodging
from app.agents.common import dedupe_sources
from app.orchestrator.common import weekday_text
from app.orchestrator.deps import OrchestratorDeps
from app.orchestrator.quality import evaluate_quality

PLAN_VERSION = "s19-plan-v1"


def run_assemble(
    constraints: dict[str, Any],
    collected: dict[str, Any],
    schedule: dict[str, Any],
    routes: dict[str, Any],
    deps: OrchestratorDeps,
    context: dict[str, Any],
) -> dict[str, Any]:
    """Merge facts, day plans, meals/lodging and quality checks into one plan candidate."""
    now = deps.now()
    quality = evaluate_quality(constraints, collected, schedule, routes, now=now)
    points = {point["poi_id"]: point for point in collected.get("points", [])}
    weather_by_date = {item["date"]: item for item in collected.get("weather", {}).get("daily", [])}

    days: list[dict[str, Any]] = []
    degraded_fields: list[str] = list(collected.get("degraded_fields", []))
    dining_sources: list[dict[str, Any]] = []
    sources: list[dict[str, Any]] = list(collected.get("source_metadata", []))

    for route_day in routes.get("days", []):
        day_date = route_day["date"]
        segments = route_day.get("segments", [])
        meals, lodging, dining_degraded = _dining_for_day(
            day_date, constraints, segments, deps, context
        )
        degraded_fields.extend(dining_degraded)
        reservations: list[dict[str, Any]] = []
        for segment in segments:
            point = points.get(segment["point_id"], {})
            card = point.get("reservations", {}).get(day_date)
            if card is None:
                continue
            enriched = dict(card)
            enriched["timeline"] = point.get("timelines", {}).get(day_date, [])
            reservations.append(enriched)
            if card.get("source_record_id"):
                pass
        for segment in segments:
            transport = segment.get("transport_to_next")
            if transport is not None:
                sources.append(
                    {
                        "source_type": "system" if transport["is_estimated"] else "map",
                        "source_record_id": transport["source_record_id"],
                        "retrieved_at": transport["retrieved_at"],
                        "valid_until": None,
                        "freshness_status": "unknown" if transport["is_estimated"] else "fresh",
                        "is_estimated": bool(transport["is_estimated"]),
                    }
                )
        days.append(
            {
                "date": day_date,
                "weekday": route_day.get("weekday") or weekday_text(date.fromisoformat(day_date)),
                "city_id": constraints["city_id"],
                "weather": weather_by_date.get(day_date) or _unknown_weather(day_date),
                "segments": segments,
                "meals": meals,
                "lodging": lodging,
                "reservations": reservations,
                "warnings": list(route_day.get("warnings", [])),
            }
        )

    conflicts = _dedupe_conflicts(quality["conflicts"])
    blocking = [conflict for conflict in conflicts if conflict["severity"] == "blocking"]
    degraded_fields.extend(quality["unknown_fields"])
    degraded_fields.extend(quality["stale_fields"])

    summary = {
        **quality["summary"],
        "degraded": bool(degraded_fields),
        "stale_fields": quality["stale_fields"],
        "unknown_fields": quality["unknown_fields"],
    }
    plan = {
        "plan_version": PLAN_VERSION,
        "generated_at": now.isoformat().replace("+00:00", "Z"),
        "sample_only": bool(deps.sample_only or collected.get("sample_only")),
        "notice": (
            "基于 S06 合成样例数据生成，不代表真实票价、开放时间或位置；"
            if deps.sample_only
            else "事实字段均携带来源；未知、估算与过期项已显式标注。"
        ),
        "trip": {
            "city_id": constraints["city_id"],
            "city_name": constraints["city_name"],
            "start_date": constraints["start_date"],
            "end_date": constraints["end_date"],
            "day_count": constraints["day_count"],
            "party_size": constraints["party_size"],
            "transport_preference": constraints["transport_preference"],
            **({"budget": constraints["budget"]} if constraints.get("budget") else {}),
            "preferences": constraints.get("preferences", []),
        },
        "summary": summary,
        "days": days,
        "quality": {
            "status": quality["quality"]["status"],
            "checks": quality["quality"]["checks"],
        },
        "conflicts": conflicts,
        "confirmation": {
            "required": bool(blocking),
            "conflict_codes": sorted({conflict["code"] for conflict in blocking}),
        },
        "sources": dedupe_sources(sources + dining_sources),
        "degraded_fields": sorted(set(degraded_fields)),
    }

    if blocking:
        status = "degraded"
        message = "方案存在需用户确认的硬冲突，确认前不会创建不可变版本"
    elif quality["quality"]["status"] == "fail":
        status = "failed"
        message = "质量检查未通过，无法形成可用方案"
    elif degraded_fields:
        status = "partial"
        message = "方案可用，但存在显式降级/缺失字段"
    else:
        status = "ok"
        message = "方案质量检查通过"

    return {
        "status": status,
        "message": message,
        "plan": plan,
        "degraded_fields": sorted(set(degraded_fields)),
        "conflicts": conflicts,
    }


def _dining_for_day(
    day_date: str,
    constraints: dict[str, Any],
    segments: list[dict[str, Any]],
    deps: OrchestratorDeps,
    context: dict[str, Any],
) -> tuple[list[dict[str, Any]], dict[str, Any], list[str]]:
    empty_lodging = {"date": day_date, "area": None, "anchor_point_id": None, "candidates": []}
    if not segments:
        return [], empty_lodging, ["meals", "lodging"]
    draft = {
        "city_id": constraints["city_id"],
        "days": [
            {
                "date": day_date,
                "segments": [
                    {
                        "point_id": segment["point_id"],
                        "name": segment["name"],
                        "district": segment.get("district"),
                        "latitude": _point_coordinate(segment["point_id"], deps)["latitude"],
                        "longitude": _point_coordinate(segment["point_id"], deps)["longitude"],
                        "start_time": segment["start_time"],
                        "end_time": segment["end_time"],
                    }
                    for segment in segments
                ],
            }
        ],
    }
    result = dining_lodging.run(
        {
            "date": day_date,
            "itinerary_draft": draft,
            "taste_preference": constraints.get("preferences") or [],
            "budget": constraints.get("budget"),
            "companion": {},
        },
        context,
        poi_source=deps.poi_source,
        moment=deps.now(),
    )
    if result.get("status") == "failed":
        return [], empty_lodging, ["meals", "lodging"]
    meals = [item for item in result.get("meals", []) if isinstance(item, dict)]
    lodging_items = [item for item in result.get("lodging", []) if isinstance(item, dict)]
    lodging = lodging_items[0] if lodging_items else empty_lodging
    degraded = [
        f"meals.{item.get('meal')}" for item in meals if len(item.get("candidates", [])) < 2
    ]
    if not lodging.get("candidates"):
        degraded.append("lodging")
    return meals, lodging, degraded


def _point_coordinate(poi_id: str, deps: OrchestratorDeps) -> dict[str, Any]:
    for poi in deps.catalog.pois:
        if poi.poi_id == poi_id:
            return {"latitude": poi.latitude, "longitude": poi.longitude}
    return {"latitude": None, "longitude": None}


def _unknown_weather(day_date: str) -> dict[str, Any]:
    return {
        "status": "unknown",
        "date": day_date,
        "condition": None,
        "temp_min": None,
        "temp_max": None,
        "precip_prob": None,
        "data_type": None,
        "source_record_id": None,
        "retrieved_at": None,
        "valid_until": None,
        "stale": False,
        "message": "天气服务暂不可用，未生成天气事实",
    }


def _dedupe_conflicts(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[str, str]] = set()
    result: list[dict[str, Any]] = []
    for item in items:
        key = (str(item.get("code")), str(item.get("message")))
        if key in seen:
            continue
        seen.add(key)
        result.append(item)
    return result


__all__ = ["run_assemble", "PLAN_VERSION"]
