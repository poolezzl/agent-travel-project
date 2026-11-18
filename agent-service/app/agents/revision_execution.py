"""Apply a confirmed domain change using the stored plan and published facts."""

from __future__ import annotations

import re
from copy import deepcopy
from datetime import date
from typing import Any

from app.agents import attractions, reservation_rules
from app.agents.common import InputError, dedupe_sources, envelope, failure
from app.orchestrator.assemble import run_assemble
from app.orchestrator.collect import _duration, _point_base
from app.orchestrator.deps import OrchestratorDeps
from app.orchestrator.routes import run_routes

_PATH = re.compile(r"/days/([1-9][0-9]*)(?:/(segments|lodging)(?:/([1-9][0-9]*))?)?")


def apply(
    inputs: dict[str, Any], context: dict[str, Any], deps: OrchestratorDeps
) -> dict[str, Any]:
    try:
        plan = _revise(inputs, context, deps)
    except (InputError, KeyError, TypeError, ValueError, IndexError) as exc:
        message = str(exc) if isinstance(exc, InputError) else "当前版本或局部变更的结构有误"
        return failure(context, "REVISION_PLAN_UNAVAILABLE", message)
    status = "partial" if plan["degraded_fields"] else "ok"
    return envelope(context, status=status, plan=plan, source_metadata=plan["sources"])


def _revise(
    inputs: dict[str, Any], context: dict[str, Any], deps: OrchestratorDeps
) -> dict[str, Any]:
    base = inputs["current_plan"]
    scope = set(inputs["affected_days"])
    agents = set(inputs["affected_agents"])
    changes = inputs["changes"]
    if not changes or not scope or not all(type(day) is int for day in scope):
        raise InputError("缺少已确认的修改范围")
    plan = deepcopy(base)
    rows = plan["days"]
    by_day: dict[int, list[tuple[dict[str, Any], str | None, int | None]]] = {}
    for change in changes:
        match = _PATH.fullmatch(change["path"])
        if match is None:
            raise InputError("不支持的修改路径")
        day, field, position = match.groups()
        day_no = int(day)
        if not 1 <= day_no <= len(rows) or day_no not in scope:
            raise InputError("修改超出确认的日期范围")
        by_day.setdefault(day_no, []).append((change, field, int(position) if position else None))
    if set(by_day) != scope:
        raise InputError("修改与受影响日期不一致")

    trip = plan["trip"]
    city = trip["city_id"]
    sources = list(plan.get("sources", []))
    degraded = set(plan.get("degraded_fields", []))
    checks = []
    for day_no, edits in sorted(by_day.items()):
        original = base["days"][day_no - 1]
        if len(edits) == 1 and edits[0][1] == "lodging":
            if "dining-lodging" not in agents:
                raise InputError("住宿调整缺少白名单权限")
            _select_lodging(rows[day_no - 1], edits[0][0])
            continue
        if not {"route-planner", "dining-lodging"} <= agents:
            raise InputError("局部重排缺少路线或餐住白名单权限")
        chosen = _edit_segments(base, original, edits, scope, deps, city)
        assembled = _replan_day(original, chosen, trip, deps, context, agents)
        rows[day_no - 1] = assembled["plan"]["days"][0]
        sources.extend(assembled["plan"]["sources"])
        checks.extend(assembled["plan"]["quality"]["checks"])
        degraded.update(
            f"revision.days[{day_no - 1}].{field}" for field in assembled["degraded_fields"]
        )
    if rows == base["days"]:
        raise InputError("该要求没有产生实际修改")
    budget = trip.get("budget")
    if budget and _known_ticket_cost(plan, budget["currency"]) > budget["amount_minor"]:
        raise InputError("修改后已知门票合计超过行程预算")
    plan["sources"] = dedupe_sources(sources)
    plan["generated_at"] = context["generated_at"]
    plan["degraded_fields"] = sorted(degraded)
    _refresh_summary(plan, checks)
    return plan


def _edit_segments(
    base: dict[str, Any],
    original: dict[str, Any],
    edits: list[tuple[dict[str, Any], str | None, int | None]],
    scope: set[int],
    deps: OrchestratorDeps,
    city: str,
) -> list[dict[str, Any]]:
    segments = list(original["segments"])
    by_position = {segment["sequence_no"]: segment for segment in segments}
    for change, field, position in edits:
        operation = change["operation"]
        value = change.get("value") or {}
        if operation == "reorder" and field is None:
            other = value.get("swap_with_day")
            if len(edits) != 1 or type(other) is not int or other not in scope:
                raise InputError("互换必须覆盖两天")
            return list(base["days"][other - 1]["segments"])
        if field != "segments":
            raise InputError("请提供可执行的景点修改")
        if operation == "reorder" and position is None:
            continue
        if operation in {"remove", "replace"}:
            previous = by_position.get(position)
            if previous is None or previous not in segments:
                raise InputError("指定景点已变化，请重新预览")
            segments.remove(previous)
        if operation in {"add", "replace"}:
            target = value.get("target_name")
            if not isinstance(target, str) or not target:
                raise InputError("请提供目的景点的准确名称")
            matches = deps.catalog.find_pois(point_id="", name=target, city_id=city)
            if len(matches) != 1 or matches[0].category != "attraction":
                raise InputError("目的景点没有唯一的已发布记录，请提供准确名称")
            segments.append({"point_id": matches[0].poi_id, "is_must_visit": False})
        elif operation != "remove":
            raise InputError("不支持的景点操作")
    if len({segment["point_id"] for segment in segments}) != len(segments):
        raise InputError("同一天已经安排该景点")
    return segments


def _replan_day(
    day: dict[str, Any],
    chosen: list[dict[str, Any]],
    trip: dict[str, Any],
    deps: OrchestratorDeps,
    context: dict[str, Any],
    agents: set[str],
) -> dict[str, Any]:
    day_date = day["date"]
    constraints = {**trip, "dates": [day_date]}
    old_ids = {item["point_id"] for item in day["segments"]}
    old_rules = {item["point_id"]: item for item in day["reservations"]}
    points = []
    sources = []
    for segment in chosen:
        point_id = segment["point_id"]
        matches = deps.catalog.find_pois(point_id, "", trip["city_id"])
        if len(matches) != 1 or matches[0].latitude is None or matches[0].longitude is None:
            raise InputError("景点缺少可用的已发布坐标")
        poi = matches[0]
        if date.fromisoformat(day_date).isoweekday() in poi.closed_weekdays:
            raise InputError("目标日期存在闭馆景点")
        point = _point_base(poi, is_must_visit=bool(segment.get("is_must_visit")), resolved=True)
        facts = segment.get("facts", [])
        if "duration_min" not in segment:
            if "attractions" not in agents:
                raise InputError("新增景点缺少景点资料白名单权限")
            result = attractions.run({"points": [_point_input(point)]}, deps.catalog, context)
            card = next(iter(result.get("cards", [])), None)
            facts = card.get("facts", []) if isinstance(card, dict) else []
            sources.extend(result.get("source_metadata", []))
        duration, origin = _duration(facts)
        point.update(
            facts=facts,
            duration_min=segment.get("duration_min", duration),
            duration_source=segment.get("duration_source", origin),
        )
        rule = old_rules.get(point_id)
        timeline = [] if rule is None else rule.get("timeline", [])
        if point_id not in old_ids:
            if "reservation-rules" not in agents:
                raise InputError("跨日或新增景点缺少预约规则白名单权限")
            result = reservation_rules.run(
                {"points": [_point_input(point)], "date": day_date, "city_id": poi.city_id},
                deps.catalog,
                context,
                moment=deps.now(),
            )
            rule = next(iter(result.get("reservations", [])), None)
            timeline = result.get("timeline", [])
            sources.extend(result.get("source_metadata", []))
        if rule:
            point.update(reservations={day_date: rule}, timelines={day_date: timeline})
        points.append(point)
    collected = {
        "points": points,
        "weather": {"daily": [day["weather"]]},
        "source_metadata": sources,
    }
    schedule = {"days": [{"date": day_date, "point_ids": [p["poi_id"] for p in points]}]}
    routes = run_routes(constraints, collected, schedule, deps=deps)
    actual = {item["point_id"] for item in routes["days"][0]["segments"]}
    if actual != {p["poi_id"] for p in points}:
        raise InputError("目标日期无法容纳全部已确认景点")
    outcome = run_assemble(constraints, collected, schedule, routes, deps, context)
    if outcome["status"] in {"failed", "degraded"}:
        raise InputError("局部路线或开放时间检查未通过")
    return outcome


def _point_input(point: dict[str, Any]) -> dict[str, Any]:
    return {key: point[key] for key in ("name", "latitude", "longitude")} | {
        "point_id": point["poi_id"]
    }


def _select_lodging(day: dict[str, Any], change: dict[str, Any]) -> None:
    target = (change.get("value") or {}).get("target_name")
    lodging = day["lodging"]
    candidates = lodging.get("candidates", [])
    selected = [item for item in candidates if item.get("name") == target]
    if len(selected) != 1:
        raise InputError("请提供当前已核验的住宿候选名称")
    lodging["candidates"] = selected + [item for item in candidates if item != selected[0]]


def _refresh_summary(plan: dict[str, Any], checks: list[dict[str, Any]]) -> None:
    summary = plan["summary"]
    segments = [segment for day in plan["days"] for segment in day["segments"]]
    legs = [segment["transport_to_next"] for segment in segments if segment["transport_to_next"]]
    summary.update(
        point_count=len(segments),
        total_distance_m=sum(leg["distance_m"] for leg in legs),
        total_transport_min=sum(leg["duration_min"] for leg in legs),
        estimated_route_legs=sum(bool(leg["is_estimated"]) for leg in legs),
        degraded=bool(plan["degraded_fields"]),
    )
    old_cost = summary.get("estimated_cost")
    if old_cost and old_cost.get("basis") == "known_ticket_rules":
        old_cost["amount_minor"] = _known_ticket_cost(plan, old_cost["currency"])
    if checks:
        plan["quality"]["checks"] = plan["quality"]["checks"] + checks
        if plan["quality"]["status"] == "pass" and any(
            check["status"] != "pass" for check in checks
        ):
            plan["quality"]["status"] = "warn"


def _known_ticket_cost(plan: dict[str, Any], currency: str) -> int:
    prices = {
        (day["date"], rule["point_id"]): rule["amount_minor"]
        for day in plan["days"]
        for rule in day["reservations"]
        if not rule.get("stale")
        and rule.get("currency") == currency
        and type(rule.get("amount_minor")) is int
    }
    return int(sum(prices.values())) * int(plan["trip"]["party_size"])
