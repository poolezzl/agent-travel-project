"""S19 stage 3: day allocation under hard-constraint priority (ADR-0011).

Must-visit points are never deleted silently. When no open day exists, the stage returns a
blocking conflict with explicit options; the caller must persist ``AWAITING_CONFIRMATION``.
"""

from __future__ import annotations

from datetime import date
from typing import Any

from app.orchestrator.common import (
    DAY_WINDOW_END,
    DAY_WINDOW_START,
    MAX_POINTS_PER_DAY,
    WEEKDAY_TEXT,
    parse_hhmm,
)

# Reserve time for lunch and keep a small buffer for legs not yet computed.
_DAY_BUDGET_MIN = parse_hhmm(DAY_WINDOW_END) - parse_hhmm(DAY_WINDOW_START) - 60 - 40
_ESTIMATED_LEG_MIN = 20.0


def run_schedule(
    constraints: dict[str, Any],
    collected: dict[str, Any],
    *,
    max_points: int = MAX_POINTS_PER_DAY,
    prefer_lower_ticket_cost: bool = False,
) -> dict[str, Any]:
    """Allocate collected points to trip days, protecting must-visit points."""
    dates = [date.fromisoformat(value) for value in constraints["dates"]]
    points = {point["poi_id"]: point for point in collected.get("points", [])}
    must_ids = [poi_id for poi_id, point in points.items() if point.get("is_must_visit")]

    conflicts: list[dict[str, Any]] = []
    warnings: list[str] = []
    degraded: list[str] = []

    for name in collected.get("unresolved_must_visit", []):
        conflicts.append(
            _conflict(
                "MUST_VISIT_UNRESOLVED",
                f"必去点「{name}」在已发布知识库中没有唯一记录，不能凭名称猜测事实或坐标。",
                options=[
                    {"option_id": "remove", "label": "移除该必去点", "impact": "行程不再包含该点"},
                    {
                        "option_id": "rename",
                        "label": "提供准确名称或内部 POI ID",
                        "impact": "需要重新提交规划",
                    },
                ],
            )
        )
        degraded.append("must_visit")
    for name in collected.get("ambiguous_must_visit", []):
        conflicts.append(
            _conflict(
                "MUST_VISIT_AMBIGUOUS",
                f"必去点「{name}」对应多个同名 POI，系统不会自动合并。",
                options=[
                    {
                        "option_id": "pick",
                        "label": "指定其中一个 POI",
                        "impact": "需要用户确认具体地点",
                    },
                    {"option_id": "remove", "label": "移除该必去点", "impact": "行程不再包含该点"},
                ],
            )
        )
        degraded.append("must_visit")

    if not points:
        return {
            "status": "failed",
            "message": "该城市暂无已发布且带坐标的景点，无法形成日级方案",
            "error_code": "NO_PUBLISHED_FACTS",
            "days": [],
            "conflicts": conflicts,
            "warnings": warnings,
            "degraded_fields": ["points"],
        }

    assignment: dict[str, list[str]] = {day.isoformat(): [] for day in dates}
    durations = {
        poi_id: float(point.get("duration_min") or 90.0) for poi_id, point in points.items()
    }

    for poi_id in sorted(must_ids):
        point = points[poi_id]
        if point.get("latitude") is None or point.get("longitude") is None:
            conflicts.append(
                _conflict(
                    "MUST_VISIT_NO_COORDINATE",
                    f"必去点「{point['name']}」缺少坐标，无法验证可达性。",
                    affected_points=[poi_id],
                    options=[
                        {
                            "option_id": "remove",
                            "label": "移除该必去点",
                            "impact": "行程不再包含该点",
                        },
                        {
                            "option_id": "supply",
                            "label": "补充已发布坐标",
                            "impact": "需要数据运营补齐后重试",
                        },
                    ],
                )
            )
            degraded.append("must_visit")
            continue
        open_days = [
            day
            for day in dates
            if point["closed_weekdays"] == [] or day.isoweekday() not in point["closed_weekdays"]
        ]
        if not open_days:
            conflicts.append(
                _conflict(
                    "MUST_VISIT_CLOSED",
                    f"必去点「{point['name']}」在行程日期内全部闭馆，系统不会删除必去点，也不会安排闭馆时段。",
                    affected_days=[day.isoformat() for day in dates],
                    affected_points=[poi_id],
                    options=[
                        {
                            "option_id": "change_dates",
                            "label": "调整行程日期",
                            "impact": "需要重新提交规划",
                        },
                        {
                            "option_id": "remove",
                            "label": "确认移除该必去点",
                            "impact": "行程不再包含该点",
                        },
                    ],
                )
            )
            degraded.append("must_visit")
            continue
        target = min(open_days, key=lambda day: (len(assignment[day.isoformat()]), day))
        assignment[target.isoformat()].append(poi_id)

    non_must = sorted(poi_id for poi_id in points if poi_id not in set(must_ids))
    if prefer_lower_ticket_cost:
        currency = (constraints.get("budget") or {}).get("currency", "CNY")
        non_must.sort(key=lambda poi_id: _ticket_priority(points[poi_id], currency))
    for poi_id in non_must:
        point = points[poi_id]
        for day in dates:
            if point["closed_weekdays"] and day.isoweekday() in point["closed_weekdays"]:
                continue
            chosen = assignment[day.isoformat()]
            if len(chosen) >= max_points:
                continue
            used = sum(durations[item] for item in chosen) + _ESTIMATED_LEG_MIN * max(
                0, len(chosen)
            )
            if used + durations[poi_id] + _ESTIMATED_LEG_MIN > _DAY_BUDGET_MIN:
                continue
            chosen.append(poi_id)
            break

    days: list[dict[str, Any]] = []
    for day in dates:
        ids = assignment[day.isoformat()]
        ordered = [
            poi_id
            for poi_id in sorted(
                ids, key=lambda item: (not points[item].get("is_must_visit"), item)
            )
        ]
        if not ordered:
            warnings.append(f"{day.isoformat()} 没有可安排的已发布景点")
        days.append(
            {
                "date": day.isoformat(),
                "weekday": WEEKDAY_TEXT[day.weekday()],
                "point_ids": ordered,
                "selection": [
                    {
                        "poi_id": poi_id,
                        "reason": "must_visit"
                        if points[poi_id].get("is_must_visit")
                        else "catalog_candidate",
                        "is_must_visit": bool(points[poi_id].get("is_must_visit")),
                    }
                    for poi_id in ordered
                ],
            }
        )

    blocking = any(conflict["severity"] == "blocking" for conflict in conflicts)
    if blocking:
        status = "degraded"
        message = "存在需要确认的必去点冲突，确认前不会创建不可变版本"
    elif warnings:
        status = "partial"
        message = "；".join(warnings[:5])
        degraded.append("days")
    else:
        status = "ok"
        message = "日级分配完成，必去点已保护"

    return {
        "status": status,
        "message": message,
        "days": days,
        "conflicts": conflicts,
        "warnings": warnings,
        "degraded_fields": sorted(set(degraded)),
    }


def _ticket_priority(point: dict[str, Any], currency: str) -> tuple[bool, int]:
    known = [
        rule["amount_minor"]
        for rule in point.get("reservations", {}).values()
        if rule.get("currency") == currency and type(rule.get("amount_minor")) is int
    ]
    return (not known, max(known, default=0))


def _conflict(
    code: str,
    message: str,
    *,
    options: list[dict[str, str]],
    affected_days: list[str] | None = None,
    affected_points: list[str] | None = None,
) -> dict[str, Any]:
    return {
        "code": code,
        "severity": "blocking",
        "message": message,
        "affected_days": affected_days or [],
        "affected_points": affected_points or [],
        "options": options,
    }


__all__ = ["run_schedule"]
