"""Route planner orchestration: ALG-04 -> ALG-05 -> ALG-06 -> envelope-ready result.

This is the function-level entry point (``plan_route``). Mounting it under the S11 task worker
/ FastAPI router is PENDING (see docs/progress/S12.md); the function signature is independent
of any transport.
"""

from __future__ import annotations

import math
import re
from collections.abc import Callable
from dataclasses import dataclass
from typing import Literal

from app.agents.route_planner.errors import ResolutionCode, RouteErrorKind
from app.agents.route_planner.feasibility import (
    NodeTiming,
    OrderEvaluation,
    evaluate_order,
    parse_hhmm,
)
from app.agents.route_planner.models import (
    PointCandidate,
    PointTiming,
    ResolvedPoint,
    RouteData,
    RouteOutput,
    RoutePlanRequest,
    RoutePlanResult,
    RouteQuery,
    RouteSegment,
)
from app.agents.route_planner.poi_resolver import (
    Clarify,
    ResolutionOutcome,
    Resolved,
    resolve_poi,
)
from app.agents.route_planner.ports import PoiPort
from app.agents.route_planner.route_provider import RouteProvider
from app.algorithms.config import TWO_OPT_MAX_ROUNDS
from app.algorithms.tsp import greedy_construct, two_opt_improve

_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_TRANSPORT_MODES = frozenset({"步行", "公交", "地铁", "打车", "混合"})

# Internal resolution outcomes -> stable envelope error code. Anything that is a semantic
# input problem surfaces as INVALID_INPUT; only an absent source surfaces as SOURCE_UNAVAILABLE.
_ENVELOPE_CODE = {
    ResolutionCode.MISSING_CONTEXT: "INVALID_INPUT",
    ResolutionCode.AMBIGUOUS_NAME: "INVALID_INPUT",
    ResolutionCode.CROSS_CITY: "INVALID_INPUT",
    ResolutionCode.MISSING_COORDINATE: "INVALID_INPUT",
    ResolutionCode.COORDINATE_SYSTEM_MISMATCH: "INVALID_INPUT",
    ResolutionCode.SOURCE_UNAVAILABLE: "SOURCE_UNAVAILABLE",
}


@dataclass(frozen=True)
class MatrixCell:
    """One leg's resolved data plus its degradation status."""

    data: RouteData | None
    status: str  # "exact" | "estimated" | "unavailable"
    error_kind: RouteErrorKind | None


def plan_route(
    request: RoutePlanRequest,
    poi_port: PoiPort,
    route_provider: RouteProvider,
) -> RoutePlanResult:
    """Produce a route for a single day: disambiguate, order, and degrade deterministically."""
    validation_error = _validate_request(request)
    if validation_error is not None:
        return validation_error

    try:
        day_start = parse_hhmm(request.time_window.start)
        day_end = parse_hhmm(request.time_window.end)
    except ValueError as exc:
        return _failed("INVALID_INPUT", str(exc))

    resolved_points: list[ResolvedPoint] = []
    for candidate in request.points:
        outcome = resolve_poi(candidate, request.city_id, poi_port)
        point_or_error = _resolved_or_fail(outcome)
        if isinstance(point_or_error, RoutePlanResult):
            return point_or_error
        resolved_points.append(point_or_error)

    origin = _resolve_optional(request.origin, request.city_id, poi_port)
    if isinstance(origin, RoutePlanResult):
        return origin
    destination = _resolve_optional(request.destination, request.city_id, poi_port)
    if isinstance(destination, RoutePlanResult):
        return destination

    resolved_points = _deduplicate(resolved_points)
    if not resolved_points:
        return _failed("INVALID_INPUT", "no points to route")

    try:
        nodes = _build_nodes(resolved_points, origin, destination, request.point_timings)
    except ValueError as exc:
        return _failed("INVALID_INPUT", str(exc))

    node_ids = [node.poi_id for node in nodes]
    points_by_id = {point.poi_id: point for point in resolved_points}
    if origin is not None:
        points_by_id[origin.poi_id] = origin
    if destination is not None:
        points_by_id[destination.poi_id] = destination

    matrix = _build_matrix(request, node_ids, points_by_id, route_provider)

    def cost(i: int, j: int) -> float:
        cell = matrix[i][j]
        if cell.data is None:
            return math.inf
        return cell.data.duration_min

    def leg_status(i: int, j: int) -> str:
        return matrix[i][j].status

    order, _ = _find_order(
        request,
        matrix,
        nodes,
        cost,
        leg_status,
        day_start,
        day_end,
        allow_estimated=False,
    )
    if order is not None:
        return _build_result(order, matrix, node_ids, "ok", [], None)

    order, evaluation = _find_order(
        request,
        matrix,
        nodes,
        cost,
        leg_status,
        day_start,
        day_end,
        allow_estimated=True,
    )
    if order is not None:
        return _build_result(
            order,
            matrix,
            node_ids,
            "degraded",
            ["route.segments"],
            "route contains straight-line estimates; estimated segments are "
            "display-only and did not pass the hard feasibility gate",
        )

    conflicts = "; ".join(evaluation.conflicts) if evaluation is not None else ""
    detail = f": {conflicts}" if conflicts else ""
    return _failed("SOURCE_UNAVAILABLE", f"could not produce a feasible order{detail}")


def _validate_request(request: RoutePlanRequest) -> RoutePlanResult | None:
    if not _DATE_RE.match(request.date):
        return _failed("INVALID_INPUT", "date must be YYYY-MM-DD")
    if request.transport_preference not in _TRANSPORT_MODES:
        return _failed(
            "INVALID_INPUT",
            f"unsupported transport_preference {request.transport_preference!r}",
        )
    return None


def _resolved_or_fail(outcome: ResolutionOutcome) -> ResolvedPoint | RoutePlanResult:
    if isinstance(outcome, Resolved):
        return outcome.point
    if isinstance(outcome, Clarify):
        candidates = ", ".join(c.poi_id for c in outcome.candidates)
        return _failed(
            "INVALID_INPUT",
            f"{outcome.code.value}: {outcome.reason}"
            + (f" candidates: {candidates}" if candidates else ""),
        )
    return _failed(_ENVELOPE_CODE[outcome.code], f"{outcome.code.value}: {outcome.reason}")


def _resolve_optional(
    candidate: PointCandidate | None,
    city_id: str,
    poi_port: PoiPort,
) -> ResolvedPoint | None | RoutePlanResult:
    if candidate is None:
        return None
    outcome = resolve_poi(candidate, city_id, poi_port)
    if isinstance(outcome, Resolved):
        return outcome.point
    return _resolved_or_fail(outcome)


def _deduplicate(points: list[ResolvedPoint]) -> list[ResolvedPoint]:
    seen: set[str] = set()
    result: list[ResolvedPoint] = []
    for point in points:
        if point.poi_id not in seen:
            seen.add(point.poi_id)
            result.append(point)
    return result


def _build_nodes(
    points: list[ResolvedPoint],
    origin: ResolvedPoint | None,
    destination: ResolvedPoint | None,
    point_timings: list[PointTiming],
) -> list[NodeTiming]:
    timings_by_id = {timing.poi_id: timing for timing in point_timings}

    def timing_for(point: ResolvedPoint) -> NodeTiming:
        timing = timings_by_id.get(point.poi_id)
        visit = timing.visit_duration_min if timing else 60.0
        open_min = parse_hhmm(timing.open_time) if timing and timing.open_time else None
        close_min = parse_hhmm(timing.close_time) if timing and timing.close_time else None
        lunch_start = (
            parse_hhmm(timing.lunch_break.start) if timing and timing.lunch_break else None
        )
        lunch_end = parse_hhmm(timing.lunch_break.end) if timing and timing.lunch_break else None
        return NodeTiming(point.poi_id, visit, open_min, close_min, lunch_start, lunch_end)

    nodes: list[NodeTiming] = []
    if origin is not None:
        nodes.append(NodeTiming(origin.poi_id, 0.0, None, None, None, None))
    nodes.extend(timing_for(point) for point in points)
    if destination is not None:
        nodes.append(NodeTiming(destination.poi_id, 0.0, None, None, None, None))
    return nodes


def _build_matrix(
    request: RoutePlanRequest,
    node_ids: list[str],
    points_by_id: dict[str, ResolvedPoint],
    route_provider: RouteProvider,
) -> list[list[MatrixCell]]:
    matrix: list[list[MatrixCell]] = []
    for i, from_id in enumerate(node_ids):
        row: list[MatrixCell] = []
        for j, to_id in enumerate(node_ids):
            if i == j:
                row.append(MatrixCell(None, "unavailable", None))
                continue
            query = RouteQuery(
                from_point_id=from_id,
                to_point_id=to_id,
                transport=request.transport_preference,
                city_id=request.city_id,
                coordinate_system=request.coordinate_system,
            )
            result = route_provider.route(
                query,
                points_by_id.get(from_id),
                points_by_id.get(to_id),
            )
            if result.status == "exact" and result.data is not None:
                row.append(MatrixCell(_round_data(result.data), "exact", None))
            elif result.status == "estimated" and result.data is not None:
                row.append(MatrixCell(_round_data(result.data), "estimated", result.error_kind))
            else:
                row.append(MatrixCell(None, "unavailable", result.error_kind))
        matrix.append(row)
    return matrix


def _find_order(
    request: RoutePlanRequest,
    matrix: list[list[MatrixCell]],
    nodes: list[NodeTiming],
    cost: Callable[[int, int], float],
    leg_status: Callable[[int, int], str],
    day_start: int,
    day_end: int,
    allow_estimated: bool,
) -> tuple[tuple[int, ...] | None, OrderEvaluation | None]:
    origin_idx = 0 if request.origin is not None else None
    dest_idx = len(nodes) - 1 if request.destination is not None else None
    point_start = 1 if origin_idx is not None else 0
    point_end = len(nodes) - 1 if dest_idx is not None else len(nodes)
    point_indices = list(range(point_start, point_end))
    if not point_indices:
        return None, None

    def feasible(order: tuple[int, ...]) -> bool:
        evaluation = evaluate_order(order, cost, leg_status, nodes, day_start, day_end)
        if evaluation.conflicts or evaluation.has_unavailable:
            return False
        return allow_estimated or not evaluation.has_estimated

    if origin_idx is not None:
        start = min(point_indices, key=lambda p: (cost(origin_idx, p), p))
    else:
        start = point_indices[0]

    greedy = greedy_construct(point_indices, start, cost)
    full: list[int] = []
    if origin_idx is not None:
        full.append(origin_idx)
    full.extend(greedy)
    if dest_idx is not None:
        full.append(dest_idx)

    order = two_opt_improve(tuple(full), cost, feasible, TWO_OPT_MAX_ROUNDS)
    evaluation = evaluate_order(order, cost, leg_status, nodes, day_start, day_end)
    if feasible(order):
        return order, evaluation
    return None, evaluation


def _build_result(
    order: tuple[int, ...],
    matrix: list[list[MatrixCell]],
    node_ids: list[str],
    status: Literal["ok", "degraded"],
    degraded_fields: list[str],
    message: str | None,
) -> RoutePlanResult:
    segments: list[RouteSegment] = []
    total_commute = 0.0
    for left, right in zip(order, order[1:], strict=False):
        cell = matrix[left][right]
        data = cell.data
        assert data is not None
        total_commute += data.duration_min
        segments.append(
            RouteSegment(
                from_point_id=data.from_point_id,
                to_point_id=data.to_point_id,
                transport=data.transport,
                distance_m=data.distance_m,
                duration_min=data.duration_min,
                source_record_id=data.source_record_id,
                retrieved_at=data.retrieved_at,
                is_estimated=data.is_estimated,
            )
        )

    segment_sum = sum(segment.duration_min for segment in segments)
    # ALG-05 hard invariant: total commute equals the sum of per-segment duration_min.
    assert math.isclose(total_commute, segment_sum, rel_tol=1e-9, abs_tol=1e-9)

    route = RouteOutput(
        ordered_points=[node_ids[i] for i in order],
        segments=segments,
        is_estimated=any(segment.is_estimated for segment in segments),
    )
    return RoutePlanResult(
        status=status,
        message=message,
        error_code=None,
        degraded_fields=degraded_fields,
        route=route,
    )


def _round_data(data: RouteData) -> RouteData:
    return data.model_copy(
        update={
            "distance_m": round(data.distance_m, 2),
            "duration_min": round(data.duration_min, 2),
        }
    )


def _failed(error_code: str, message: str) -> RoutePlanResult:
    return RoutePlanResult(status="failed", error_code=error_code, message=message)
