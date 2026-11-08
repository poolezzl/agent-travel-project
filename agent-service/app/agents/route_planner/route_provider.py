"""ALG-06 three-level route degradation: exact cache -> straight-line estimate -> unavailable.

The order of the ladder is fixed and never inverted. Estimated segments are display-only and
never pass the hard feasibility gate; auth/quota errors never write negative cache.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime

from app.agents.route_planner.errors import RouteErrorKind
from app.agents.route_planner.models import (
    ResolvedPoint,
    RouteData,
    RoutePortError,
    RoutePortResult,
    RouteProviderResult,
    RouteQuery,
)
from app.agents.route_planner.ports import RouteCache, RoutePort
from app.algorithms.config import STRAIGHT_LINE_SPEED_KMH
from app.algorithms.geometry import haversine_m, straight_line_duration_min

# Errors that return "unavailable" instead of falling back to a straight-line estimate.
_NO_ESTIMATE_KINDS = frozenset(
    {
        RouteErrorKind.AUTH,
        RouteErrorKind.QUOTA,
        RouteErrorKind.UNREACHABLE,
        RouteErrorKind.CROSS_CITY,
        RouteErrorKind.TIMEOUT,
    }
)


def route_key(query: RouteQuery) -> str:
    """Deterministic cache key for a leg (ADR-0006). Exact format is PENDING (S10/R5)."""
    slot = query.time_slot or "any"
    return (
        f"route:v1:{query.coordinate_system}:{query.city_id}:"
        f"{query.from_point_id}:{query.to_point_id}:{query.transport}:{slot}"
    )


class RouteProvider:
    """Resolve one leg following ALG-06."""

    def __init__(
        self,
        port: RoutePort,
        cache: RouteCache,
        estimate_speed_kmh: float = STRAIGHT_LINE_SPEED_KMH,
        now: Callable[[], datetime] | None = None,
    ):
        self._port = port
        self._cache = cache
        self._estimate_speed_kmh = estimate_speed_kmh
        self._now = now if now is not None else lambda: datetime.now(UTC)

    def route(
        self,
        query: RouteQuery,
        from_point: ResolvedPoint | None = None,
        to_point: ResolvedPoint | None = None,
    ) -> RouteProviderResult:
        key = route_key(query)

        cached = self._cache.get(key)
        if cached is not None and cached.freshness_status == "fresh":
            return RouteProviderResult(status="exact", data=cached)

        result: RoutePortResult = self._port.route(query)
        if isinstance(result, RouteData):
            self._cache.put(key, result)
            return RouteProviderResult(status="exact", data=result)

        error: RoutePortError = result
        if error.kind in _NO_ESTIMATE_KINDS:
            return RouteProviderResult(status="unavailable", error_kind=error.kind)

        # RATE_LIMITED / EMPTY / DIRTY fall back to a straight-line estimate when coordinates
        # are available; otherwise the leg is unavailable.
        if from_point is not None and to_point is not None:
            estimated = self._estimate(query, from_point, to_point)
            return RouteProviderResult(
                status="estimated",
                data=estimated,
                error_kind=error.kind,
            )
        return RouteProviderResult(status="unavailable", error_kind=error.kind)

    def _estimate(
        self,
        query: RouteQuery,
        from_point: ResolvedPoint,
        to_point: ResolvedPoint,
    ) -> RouteData:
        distance_m = haversine_m(
            from_point.latitude,
            from_point.longitude,
            to_point.latitude,
            to_point.longitude,
        )
        duration_min = straight_line_duration_min(distance_m, self._estimate_speed_kmh)
        return RouteData(
            from_point_id=query.from_point_id,
            to_point_id=query.to_point_id,
            transport=query.transport,
            distance_m=round(distance_m, 2),
            duration_min=round(duration_min, 2),
            source_record_id="straight-line-estimate",
            retrieved_at=self._now(),
            freshness_status="unknown",
            is_estimated=True,
        )
