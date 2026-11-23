"""S19 stage 2: parallel collection from published knowledge, weather and rules.

Only the already-frozen sub-agents are called. A failing sub-agent degrades its own slice
and never blocks the whole plan; every fact keeps its source record id.
"""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from typing import Any

from app.agents import attractions, reservation_rules
from app.agents.common import new_context
from app.agents.weather.agent import run_weather
from app.agents.weather.models import DateRange, WeatherDaily, WeatherPlanRequest
from app.knowledge.catalog import PublishedCatalog
from app.orchestrator.common import DEFAULT_VISIT_MINUTES
from app.orchestrator.deps import OrchestratorDeps

_DURATION_RANGE = re.compile(r"(\d+(?:\.\d+)?)\s*(?:到|至|-|~)\s*(\d+(?:\.\d+)?)\s*(小时|分钟)")
_DURATION_SINGLE = re.compile(r"(\d+(?:\.\d+)?)\s*(小时|分钟)")


def run_collect(
    constraints: dict[str, Any],
    deps: OrchestratorDeps,
    context: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Collect attraction cards, weather and reservation rules for the trip window."""
    ctx = context or new_context()
    catalog = deps.catalog
    city_id = str(constraints["city_id"])
    dates = [date.fromisoformat(value) for value in constraints["dates"]]
    now = deps.now()

    catalog_points = _catalog_points(catalog, city_id)
    must_visit, unresolved, ambiguous = _resolve_must_visit(
        catalog, city_id, constraints.get("must_visit") or []
    )
    must_ids = {poi.poi_id for poi in must_visit}

    points = [
        _point_base(
            poi,
            is_must_visit=poi.poi_id in must_ids,
            resolved=poi.poi_id in must_ids or not must_ids,
        )
        for poi in catalog_points
    ]

    point_inputs = [
        {
            "point_id": point["poi_id"],
            "name": point["name"],
            "latitude": point["latitude"],
            "longitude": point["longitude"],
        }
        for point in points
    ]

    degraded: list[str] = []
    sources: list[dict[str, Any]] = []

    with ThreadPoolExecutor(max_workers=4) as pool:
        attractions_future = pool.submit(
            attractions.run,
            {"points": point_inputs, "city_id": city_id},
            catalog,
            ctx,
            moment=now,
        )
        weather_future = pool.submit(_weather_slice, constraints, deps)
        reservation_futures = {
            day: pool.submit(
                reservation_rules.run,
                {"points": point_inputs, "date": day.isoformat(), "city_id": city_id},
                catalog,
                ctx,
                moment=now,
            )
            for day in dates
        }
        attractions_result = attractions_future.result()
        weather_result = weather_future.result()
        reservation_results = {day: future.result() for day, future in reservation_futures.items()}

    cards = {
        card.get("point_id"): card
        for card in attractions_result.get("cards", [])
        if isinstance(card, dict)
    }
    for point in points:
        card = cards.get(point["poi_id"], {"point_id": point["poi_id"], "source": "unknown"})
        point["attraction"] = card
        point["facts"] = [fact for fact in card.get("facts", []) if isinstance(fact, dict)]
        duration, source = _duration(point["facts"])
        point["duration_min"] = duration
        point["duration_source"] = source
        if card.get("source") == "unknown":
            degraded.append(f"points[{point['poi_id']}].attraction")
        if source == "assumed":
            degraded.append(f"points[{point['poi_id']}].duration")

    for day, result in reservation_results.items():
        card_list = [card for card in result.get("reservations", []) if isinstance(card, dict)]
        timeline = [item for item in result.get("timeline", []) if isinstance(item, dict)]
        by_point = {card.get("point_id"): card for card in card_list}
        for point in points:
            card = by_point.get(point["poi_id"])
            if card is not None:
                point.setdefault("reservations", {})[day.isoformat()] = card
        for point in points:
            items = [item for item in timeline if item.get("point_id") == point["poi_id"]]
            if items:
                point.setdefault("timelines", {})[day.isoformat()] = items
        sources.extend(item for item in result.get("source_metadata", []) if isinstance(item, dict))
        degraded.extend(result.get("degraded_fields", []))

    sources.extend(
        item for item in attractions_result.get("source_metadata", []) if isinstance(item, dict)
    )
    degraded.extend(attractions_result.get("degraded_fields", []))
    sources.extend(weather_result["sources"])
    degraded.extend(weather_result["degraded_fields"])

    if unresolved or ambiguous:
        degraded.extend(["must_visit"] * (len(unresolved) + len(ambiguous)))

    status = "ok"
    if unresolved or ambiguous or weather_result["status"] != "ok":
        status = "partial"
    if all(point["attraction"].get("source") == "unknown" for point in points):
        status = "degraded"

    payload: dict[str, Any] = {
        "status": status,
        "message": _message(unresolved, ambiguous, weather_result["weather"]),
        "points": points,
        "weather": weather_result["weather"],
        "unresolved_must_visit": unresolved,
        "ambiguous_must_visit": ambiguous,
        "source_metadata": _dedupe_sources(sources),
        "degraded_fields": sorted(set(degraded)),
        "catalog_version": catalog.catalog_version,
        "sample_only": deps.sample_only,
        "collected_at": now.isoformat(),
    }
    return payload


def _catalog_points(catalog: PublishedCatalog, city_id: str) -> list[Any]:
    return [
        poi
        for poi in catalog.pois
        if poi.city_id == city_id and poi.category == "attraction" and poi.latitude is not None
    ]


def _resolve_must_visit(
    catalog: PublishedCatalog,
    city_id: str,
    names: list[Any],
) -> tuple[list[Any], list[str], list[str]]:
    resolved: list[Any] = []
    unresolved: list[str] = []
    ambiguous: list[str] = []
    seen: set[str] = set()
    for name in names:
        text = str(name).strip()
        matches = catalog.find_pois(point_id="", name=text, city_id=city_id)
        if len(matches) == 1 and matches[0].poi_id not in seen:
            resolved.append(matches[0])
            seen.add(matches[0].poi_id)
        elif len(matches) > 1:
            ambiguous.append(text)
        else:
            unresolved.append(text)
    return resolved, unresolved, ambiguous


def _point_base(poi: Any, *, is_must_visit: bool, resolved: bool) -> dict[str, Any]:
    return {
        "poi_id": poi.poi_id,
        "name": poi.name,
        "district": poi.district,
        "latitude": poi.latitude,
        "longitude": poi.longitude,
        "open_time": poi.open_time,
        "close_time": poi.close_time,
        "closed_weekdays": list(poi.closed_weekdays),
        "is_must_visit": is_must_visit,
        "resolved": resolved,
    }


def _duration(facts: list[dict[str, Any]]) -> tuple[float, str]:
    for fact in facts:
        if fact.get("fact_type") != "suggested_duration":
            continue
        text = str(fact.get("value") or "")
        range_match = _DURATION_RANGE.search(text)
        if range_match:
            low, high, unit = range_match.groups()
            minutes = (float(low) + float(high)) / 2
            return (minutes * 60 if unit == "小时" else minutes, "knowledge")
        single = _DURATION_SINGLE.search(text)
        if single:
            amount, unit = single.groups()
            minutes = float(amount) * (60 if unit == "小时" else 1)
            return minutes, "knowledge"
    return DEFAULT_VISIT_MINUTES, "assumed"


def _weather_slice(constraints: dict[str, Any], deps: OrchestratorDeps) -> dict[str, Any]:
    start = date.fromisoformat(constraints["start_date"])
    end = date.fromisoformat(constraints["end_date"])
    request = WeatherPlanRequest(
        city_id=constraints["city_id"],
        date_range=DateRange(start=start, end=end),
        today=deps.now().date(),
        forecast_horizon_days=deps.forecast_horizon_days,
        sensitive_points=[],
    )
    result = run_weather(request, deps.weather_provider)  # type: ignore[arg-type]
    daily = [item.model_dump(mode="json") for item in result.daily]
    sources: list[dict[str, Any]] = []
    degraded: list[str] = []
    by_date: dict[str, dict[str, Any]] = {}
    now = deps.now()

    for item in daily:
        parsed = WeatherDaily.model_validate(item)
        valid_until = parsed.valid_until
        stale = (
            parsed.data_type == "climate_statistics"
            or (valid_until is not None and valid_until < now)
            or (valid_until is None and parsed.data_type == "forecast")
        )
        row = {
            "status": "degraded" if stale else "ok",
            "date": parsed.date.isoformat(),
            "condition": parsed.condition,
            "temp_min": parsed.temp_min,
            "temp_max": parsed.temp_max,
            "precip_prob": parsed.precip_prob,
            "data_type": parsed.data_type,
            "source_record_id": parsed.source_record_id,
            "retrieved_at": parsed.retrieved_at.isoformat(),
            "valid_until": valid_until.isoformat() if valid_until is not None else None,
            "stale": stale,
        }
        if stale:
            degraded.append(f"weather.{parsed.date.isoformat()}")
            row["message"] = "天气数据已过期或仅为气候统计，不得作为确定性约束"
        by_date[row["date"]] = row
        sources.append(
            {
                "source_type": "weather",
                "source_record_id": parsed.source_record_id,
                "retrieved_at": parsed.retrieved_at.isoformat(),
                "valid_until": row["valid_until"],
                "freshness_status": "stale" if stale else "fresh",
                "is_estimated": False,
            }
        )

    for value in constraints["dates"]:
        if value not in by_date:
            by_date[value] = {
                "status": "unknown",
                "date": value,
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
            degraded.append(f"weather.{value}")

    return {
        "weather": {
            "status": result.status,
            "message": result.message,
            "daily": [by_date[value] for value in constraints["dates"]],
        },
        "status": result.status,
        "degraded_fields": sorted(set(degraded)),
        "sources": sources,
    }


def _message(unresolved: list[str], ambiguous: list[str], weather: dict[str, Any]) -> str:
    parts: list[str] = []
    if unresolved:
        parts.append(f"以下必去点暂无已发布记录：{'、'.join(unresolved)}")
    if ambiguous:
        parts.append(f"以下必去点存在同名冲突：{'、'.join(ambiguous)}")
    if weather["status"] != "ok":
        parts.append(str(weather["message"] or "天气服务暂不可用"))
    return "；".join(parts) or "已发布资讯、天气与预约规则采集完成"


def _dedupe_sources(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[str, str, str]] = set()
    result: list[dict[str, Any]] = []
    for item in items:
        key = (
            str(item.get("source_type", "")),
            str(item.get("source_record_id", "")),
            str(item.get("retrieved_at", "")),
        )
        if key in seen:
            continue
        seen.add(key)
        result.append(item)
    return result


__all__ = ["run_collect"]
