"""S19 stage 5: deterministic quality checks and blocking-conflict detection."""

from __future__ import annotations

from datetime import date, datetime
from typing import Any

from app.orchestrator.common import parse_hhmm


def evaluate_quality(
    constraints: dict[str, Any],
    collected: dict[str, Any],
    schedule: dict[str, Any],
    routes: dict[str, Any],
    *,
    now: datetime,
) -> dict[str, Any]:
    """Run time, closure, reachability, reservation, budget and source checks."""
    points = {point["poi_id"]: point for point in collected.get("points", [])}
    days = routes.get("days", [])
    checks: list[dict[str, Any]] = []
    conflicts: list[dict[str, Any]] = list(schedule.get("conflicts", [])) + list(
        routes.get("conflicts", [])
    )
    stale_fields: list[str] = []
    unknown_fields: list[str] = []
    estimated_legs = 0
    source_slots = 0
    source_present = 0

    overlaps = 0
    closure_hits = 0
    reachability_gaps = 0
    reservation_gaps = 0

    for day_index, day in enumerate(days):
        segments = day.get("segments", [])
        day_date = date.fromisoformat(day["date"])
        for index, segment in enumerate(segments):
            point = points.get(segment["point_id"], {})
            start = parse_hhmm(segment["start_time"])
            end = parse_hhmm(segment["end_time"])
            if end <= start:
                overlaps += 1
            if index > 0 and parse_hhmm(segments[index - 1]["end_time"]) > start:
                overlaps += 1
            if day_date.isoweekday() in point.get("closed_weekdays", []):
                closure_hits += 1
            open_time = point.get("open_time")
            close_time = point.get("close_time")
            if open_time and start < parse_hhmm(open_time):
                closure_hits += 1
            if close_time and end > parse_hhmm(close_time):
                closure_hits += 1
            if index + 1 < len(segments):
                if segment.get("transport_to_next") is None:
                    reachability_gaps += 1
                elif segment["transport_to_next"].get("is_estimated"):
                    estimated_legs += 1
                    unknown_fields.append(f"days[{day_index}].segments[{index}].transport_to_next")
            for fact in segment.get("facts", []):
                source_slots += 1
                if fact.get("source_record_id"):
                    source_present += 1
            if not segment.get("facts"):
                unknown_fields.append(f"days[{day_index}].segments[{index}].facts")
            reservation = point.get("reservations", {}).get(day["date"])
            if point.get("is_must_visit") and point.get("reservations") and reservation is None:
                reservation_gaps += 1
            if reservation is not None:
                source_slots += 1
                if reservation.get("policy_id"):
                    source_present += 1
                if reservation.get("stale"):
                    stale_fields.append(f"days[{day_index}].reservations[{segment['point_id']}]")

        weather = day.get("weather") or {}
        if weather.get("stale"):
            stale_fields.append(f"days[{day_index}].weather")
        if weather.get("status") != "ok":
            unknown_fields.append(f"days[{day_index}].weather")

    checks.append(_check("TIME_OVERLAP", "fail" if overlaps else "pass", f"时间重叠 {overlaps} 处"))
    checks.append(
        _check(
            "OPENING_HOURS",
            "fail" if closure_hits else "pass",
            f"闭馆/开放时间冲突 {closure_hits} 处",
        )
    )
    checks.append(
        _check(
            "REACHABILITY",
            "warn" if reachability_gaps or estimated_legs else "pass",
            f"不可达路段 {reachability_gaps} 处；估算路段 {estimated_legs} 处（估算不视为硬可行）",
        )
    )
    checks.append(
        _check(
            "RESERVATION",
            "warn" if reservation_gaps else "pass",
            f"需预约但缺少已发布规则的必去点 {reservation_gaps} 处",
        )
    )

    budget_conflict = _budget_check(constraints, collected, points)
    if budget_conflict is not None:
        conflicts.append(budget_conflict)
        checks.append(_check("BUDGET", "fail", budget_conflict["message"]))
    else:
        checks.append(_check("BUDGET", "pass", "已知票价未超过用户预算上限"))

    if stale_fields:
        checks.append(_check("FRESHNESS", "warn", f"过期字段 {len(stale_fields)} 处", stale_fields))
    else:
        checks.append(_check("FRESHNESS", "pass", "未发现过期事实"))
    checks.append(
        _check(
            "SOURCES",
            "pass" if source_present == source_slots else "warn",
            f"来源完整率 {source_present}/{source_slots}",
        )
    )

    must_ids = {point["poi_id"] for point in points.values() if point.get("is_must_visit")}
    scheduled = {segment["point_id"] for day in days for segment in day.get("segments", [])}
    missing_must = sorted(must_ids - scheduled)
    if missing_must:
        conflicts.append(
            {
                "code": "MUST_VISIT_MISSING",
                "severity": "blocking",
                "message": (
                    f"必去点未进入最终方案：{', '.join(missing_must)}；系统不会静默删除必去点。"
                ),
                "affected_days": [],
                "affected_points": missing_must,
                "options": [
                    {
                        "option_id": "change_dates",
                        "label": "调整日期后重试",
                        "impact": "需要重新规划",
                    },
                    {
                        "option_id": "remove",
                        "label": "确认移除必去点",
                        "impact": "行程不再包含该点",
                    },
                ],
            }
        )

    blocking = [conflict for conflict in conflicts if conflict["severity"] == "blocking"]
    warnings = [check for check in checks if check["status"] in {"warn", "fail"}]
    if blocking or any(check["status"] == "fail" for check in checks):
        status = (
            "fail"
            if blocking
            or any(check["status"] == "fail" and check["code"] != "BUDGET" for check in checks)
            else "warn"
        )
    elif warnings:
        status = "warn"
    else:
        status = "pass"

    currency = (constraints.get("budget") or {}).get("currency", "CNY")
    estimated_cost = _estimated_cost(points, days, int(constraints.get("party_size", 1)), currency)
    total_distance = sum(
        float(segment["transport_to_next"]["distance_m"])
        for day in days
        for segment in day.get("segments", [])
        if segment.get("transport_to_next")
    )
    total_transport = sum(
        float(segment["transport_to_next"]["duration_min"])
        for day in days
        for segment in day.get("segments", [])
        if segment.get("transport_to_next")
    )

    return {
        "quality": {
            "status": status,
            "checks": checks,
            "executed_at": now.isoformat(),
        },
        "conflicts": conflicts,
        "stale_fields": sorted(set(stale_fields)),
        "unknown_fields": sorted(set(unknown_fields)),
        "summary": {
            "point_count": sum(len(day.get("segments", [])) for day in days),
            "total_distance_m": round(total_distance, 2),
            "total_transport_min": round(total_transport, 2),
            "estimated_route_legs": estimated_legs,
            "estimated_cost": estimated_cost,
            "source_completeness": round(source_present / source_slots, 4) if source_slots else 0.0,
        },
    }


def _budget_check(
    constraints: dict[str, Any],
    collected: dict[str, Any],
    points: dict[str, Any],
) -> dict[str, Any] | None:
    budget = constraints.get("budget")
    if not budget:
        return None
    total = 0
    currency = budget["currency"]
    unknown_prices = 0
    for point in points.values():
        if not point.get("is_must_visit"):
            continue
        cards = point.get("reservations", {}).values()
        chosen = next(iter(cards), None) if cards else None
        if chosen is None:
            unknown_prices += 1
            continue
        if chosen.get("currency") != currency:
            unknown_prices += 1
            continue
        total += int(chosen.get("amount_minor") or 0) * int(constraints.get("party_size", 1))
    if total > int(budget["amount_minor"]):
        return {
            "code": "BUDGET_EXCEEDED",
            "severity": "blocking",
            "message": (
                f"已知门票至少 {total} {currency} minor，超过用户预算 {budget['amount_minor']}；"
                "系统不会静默超预算。"
            ),
            "affected_days": list(constraints.get("dates", [])),
            "affected_points": [],
            "options": [
                {
                    "option_id": "raise_budget",
                    "label": "提高预算上限",
                    "impact": "需要重新提交规划",
                },
                {
                    "option_id": "remove",
                    "label": "减少必去点或付费项目",
                    "impact": "需要重新提交规划",
                },
            ],
        }
    if unknown_prices:
        return {
            "code": "BUDGET_UNKNOWN",
            "severity": "warning",
            "message": f"{unknown_prices} 个必去点缺少可比票价，预算只做已知项校验。",
            "affected_days": [],
            "affected_points": [],
            "options": [],
        }
    return None


def _estimated_cost(
    points: dict[str, Any], days: list[dict[str, Any]], party_size: int, currency: str
) -> dict[str, Any]:
    total = 0
    for day in days:
        for segment in day["segments"]:
            rule = points.get(segment["point_id"], {}).get("reservations", {}).get(day["date"])
            if rule and not rule.get("stale") and rule.get("currency") == currency:
                total += int(rule.get("amount_minor") or 0)
    return {"currency": currency, "amount_minor": total * party_size, "basis": "known_ticket_rules"}


def _check(code: str, status: str, message: str, fields: list[str] | None = None) -> dict[str, Any]:
    return {"code": code, "status": status, "message": message, "fields": fields or []}


__all__ = ["evaluate_quality"]
