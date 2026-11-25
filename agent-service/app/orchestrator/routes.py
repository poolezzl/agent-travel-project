"""S19 stage 4: per-day route ordering and time slots.

Reuses the S12 deterministic planner (ALG-04/05/06). With no real map provider configured the
provider falls back to straight-line estimates; every estimated leg stays explicitly marked and
is never presented as a hard-feasibility proof.
"""

from __future__ import annotations

from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from typing import Any

from app.agents.route_planner.errors import RouteErrorKind
from app.agents.route_planner.mocks import MockRouteCache
from app.agents.route_planner.models import (
    PointCandidate,
    PointTiming,
    PoiRecord,
    RoutePlanRequest,
    RoutePortError,
    RoutePortResult,
    RouteQuery,
    TimeWindow,
)
from app.agents.route_planner.planner import plan_route
from app.agents.route_planner.route_provider import RouteProvider
from app.orchestrator.common import (
    DAY_WINDOW_END,
    DAY_WINDOW_START,
    LUNCH_END,
    LUNCH_START,
    format_hhmm,
    parse_hhmm,
)
from app.orchestrator.deps import OrchestratorDeps


class CatalogPoiPort:
    """Authoritative POI port backed by the published catalog (S15/S06)."""

    def __init__(self, records: list[PoiRecord]) -> None:
        self._records = {record.poi_id: record for record in records}

    def lookup(self, name: str, city_id: str) -> list[PoiRecord]:
        return [
            record
            for record in self._records.values()
            if record.name == name and record.city_id == city_id
        ]

    def get(self, poi_id: str) -> PoiRecord | None:
        return self._records.get(poi_id)


class EstimateOnlyRoutePort:
    """No map provider: every leg must degrade to a straight-line estimate."""

    def route(self, query: RouteQuery) -> RoutePortResult:
        return RoutePortError(kind=RouteErrorKind.EMPTY, message="route provider not configured")


def build_route_provider(
    mode: str = "estimate", now: Callable[[], Any] | None = None
) -> RouteProvider:
    """Estimate-only provider. ``now`` keeps estimated legs on the orchestrator clock."""
    return RouteProvider(EstimateOnlyRoutePort(), MockRouteCache(), now=now)


def run_routes(
    constraints: dict[str, Any],
    collected: dict[str, Any],
    schedule: dict[str, Any],
    *,
    deps: OrchestratorDeps | None = None,
    route_provider: RouteProvider | None = None,
    cached_days: dict[tuple[str, tuple[str, ...]], dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Order each day's points and assign deterministic time slots."""
    city_id = str(constraints["city_id"])
    catalog_points = {point["poi_id"]: point for point in collected.get("points", [])}
    poi_port = CatalogPoiPort(
        [
            PoiRecord(
                poi_id=point["poi_id"],
                name=point["name"],
                city_id=city_id,
                latitude=point.get("latitude"),
                longitude=point.get("longitude"),
                coordinate_system="GCJ02" if point.get("latitude") is not None else None,
            )
            for point in catalog_points.values()
            if point.get("latitude") is not None and point.get("longitude") is not None
        ]
    )
    provider = route_provider
    if provider is None and deps is not None and deps.route_provider_factory is not None:
        provider = deps.route_provider_factory(poi_port)
    if provider is None:
        provider = build_route_provider(now=deps.now if deps is not None else None)
    transport = str(constraints.get("transport_preference") or "混合")
    days = schedule.get("days", [])

    def plan_day(day: dict[str, Any]) -> dict[str, Any]:
        cached = (cached_days or {}).get((day["date"], tuple(day["point_ids"])))
        if cached is not None:
            return cached
        return _plan_day(day, city_id, transport, catalog_points, poi_port, provider)

    with ThreadPoolExecutor(max_workers=min(4, max(1, len(days)))) as pool:
        results = list(pool.map(plan_day, days))

    conflicts = [conflict for result in results for conflict in result["conflicts"]]
    warnings = [warning for result in results for warning in result["warnings"]]
    degraded = sorted({field for result in results for field in result["degraded_fields"]})
    blocking = any(conflict["severity"] == "blocking" for conflict in conflicts)

    if blocking:
        status = "degraded"
        message = "路线阶段存在影响保护项的冲突，需要确认"
    elif any(result["route_status"] == "failed" for result in results):
        status = "partial"
        message = "部分日期无法生成路线，已显式降级"
        degraded.append("days")
    elif degraded:
        status = "partial"
        message = "路线包含直线估算，仅用于展示，未通过硬可行性 Gate"
    else:
        status = "ok"
        message = "逐日路线与时间槽完成"

    return {
        "status": status,
        "message": message,
        "days": results,
        "conflicts": conflicts,
        "warnings": warnings,
        "degraded_fields": sorted(set(degraded)),
    }


def _plan_day(
    day: dict[str, Any],
    city_id: str,
    transport: str,
    points: dict[str, Any],
    poi_port: CatalogPoiPort,
    provider: RouteProvider,
) -> dict[str, Any]:
    day_date = date.fromisoformat(day["date"])
    point_ids = [poi_id for poi_id in day.get("point_ids", []) if poi_id in points]
    warnings: list[str] = []
    conflicts: list[dict[str, Any]] = []
    degraded: list[str] = []

    if not point_ids:
        return {
            "date": day["date"],
            "weekday": day.get("weekday"),
            "segments": [],
            "route_status": "empty",
            "warnings": ["当日没有可安排的景点"],
            "conflicts": conflicts,
            "degraded_fields": ["days.segments"],
        }

    request = RoutePlanRequest(
        date=day["date"],
        city_id=city_id,
        points=[
            PointCandidate(
                poi_id=poi_id,
                name=points[poi_id]["name"],
                city_id=city_id,
                latitude=points[poi_id].get("latitude"),
                longitude=points[poi_id].get("longitude"),
                coordinate_system="GCJ02",
            )
            for poi_id in point_ids
        ],
        transport_preference=transport,
        time_window=TimeWindow(start=DAY_WINDOW_START, end=DAY_WINDOW_END),
        point_timings=[
            PointTiming(
                poi_id=poi_id,
                visit_duration_min=float(points[poi_id].get("duration_min") or 90.0),
                open_time=points[poi_id].get("open_time"),
                close_time=points[poi_id].get("close_time"),
                must_visit=bool(points[poi_id].get("is_must_visit")),
            )
            for poi_id in point_ids
        ],
    )
    result = plan_route(request, poi_port, provider)
    if result.status == "failed" or result.route is None:
        must_ids = [poi_id for poi_id in point_ids if points[poi_id].get("is_must_visit")]
        if must_ids:
            conflicts.append(
                {
                    "code": "ROUTE_INFEASIBLE",
                    "severity": "blocking",
                    "message": (
                        f"{day['date']} 的必去点无法形成可行路线："
                        f"{result.message or result.error_code}"
                    ),
                    "affected_days": [day["date"]],
                    "affected_points": must_ids,
                    "options": [
                        {
                            "option_id": "change_dates",
                            "label": "调整日期或必去点",
                            "impact": "需要重新规划",
                        },
                    ],
                }
            )
        else:
            warnings.append(f"{day['date']} 无法生成路线：{result.message or result.error_code}")
        return {
            "date": day["date"],
            "weekday": day.get("weekday"),
            "segments": [],
            "route_status": "failed",
            "warnings": warnings,
            "conflicts": conflicts,
            "degraded_fields": ["days.segments"],
        }

    assert result.route is not None
    ordered = result.route.ordered_points
    leg_by_pair = {
        (segment.from_point_id, segment.to_point_id): segment for segment in result.route.segments
    }
    segments = _assign_times(day_date, ordered, points, leg_by_pair, warnings, conflicts, degraded)

    if result.status == "degraded":
        degraded.append("days.segments")
        warnings.append("路线含直线估算，已显式标注 is_estimated")

    return {
        "date": day["date"],
        "weekday": day.get("weekday"),
        "segments": segments,
        "route_status": result.status,
        "warnings": warnings,
        "conflicts": conflicts,
        "degraded_fields": sorted(set(degraded)),
    }


def _assign_times(
    day: date,
    ordered: list[str],
    points: dict[str, Any],
    legs: dict[tuple[str, str], Any],
    warnings: list[str],
    conflicts: list[dict[str, Any]],
    degraded: list[str],
) -> list[dict[str, Any]]:
    window_start = parse_hhmm(DAY_WINDOW_START)
    window_end = parse_hhmm(DAY_WINDOW_END)
    lunch_start = parse_hhmm(LUNCH_START)
    lunch_end = parse_hhmm(LUNCH_END)
    current = float(window_start)
    segments: list[dict[str, Any]] = []

    for index, poi_id in enumerate(ordered):
        point = points[poi_id]
        duration = float(point.get("duration_min") or 90.0)
        if lunch_start <= current < lunch_end:
            # Never start a new visit inside the lunch break.
            current = float(lunch_end)
        end = current + duration
        if end > window_end:
            if point.get("is_must_visit"):
                conflicts.append(
                    {
                        "code": "DAY_OVERFLOW",
                        "severity": "blocking",
                        "message": (
                            f"{day.isoformat()} 必去点「{point['name']}」超出时间窗，"
                            "需调整日期或减少点位"
                        ),
                        "affected_days": [day.isoformat()],
                        "affected_points": [poi_id],
                        "options": [
                            {
                                "option_id": "change_dates",
                                "label": "调整日期",
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
            else:
                warnings.append(f"{day.isoformat()}「{point['name']}」超出时间窗，已跳过")
                degraded.append("days.segments")
            if segments:
                # The dropped point must not keep a misleading transport leg.
                segments[-1]["transport_to_next"] = None
            break

        next_id = ordered[index + 1] if index + 1 < len(ordered) else None
        leg = legs.get((poi_id, next_id)) if next_id else None
        transport = None
        if leg is not None:
            transport = {
                "mode": leg.transport,
                "distance_m": float(leg.distance_m),
                "duration_min": float(leg.duration_min),
                "is_estimated": bool(leg.is_estimated),
                "source_record_id": leg.source_record_id,
                "retrieved_at": leg.retrieved_at.isoformat().replace("+00:00", "Z"),
            }
            if leg.is_estimated:
                degraded.append("days.segments.is_estimated")

        segments.append(
            {
                "sequence_no": index + 1,
                "point_id": poi_id,
                "name": point["name"],
                "district": point.get("district"),
                "category": "attraction",
                "start_time": format_hhmm(current),
                "end_time": format_hhmm(end),
                "duration_min": duration,
                "duration_source": point.get("duration_source", "assumed"),
                "is_must_visit": bool(point.get("is_must_visit")),
                "is_estimated": False,
                "facts": point.get("facts", []),
                "transport_to_next": transport,
            }
        )
        current = end + (float(leg.duration_min) if leg is not None else 0.0)

    return segments


__all__ = ["run_routes", "CatalogPoiPort", "build_route_provider"]
