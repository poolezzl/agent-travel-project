"""高德 route adapter: implements ``RoutePort`` by calling 高德 direction APIs directly.

``RouteQuery`` carries endpoint poi_ids, while 高德 (and the S10 ``RouteRequest``) requires
coordinates, so this adapter resolves coordinates through the injected ``PoiPort``. Returns
``RouteData`` with a deterministic ``source_record_id``, server ``retrieved_at`` and
``is_estimated=False``. 高德 timeout/429/QUOTA/AUTH/empty/dirty JSON map to ``RoutePortError``.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime

import httpx

from app.agents.route_planner.adapters._amap import (
    AMAP_TRANSIT_PATH,
    ROUTE_PATH_BY_TRANSPORT,
    amap_city_code,
    parse_number,
    route_error_kind,
    source_record_id,
)
from app.agents.route_planner.errors import RouteErrorKind
from app.agents.route_planner.models import (
    RouteData,
    RoutePortError,
    RoutePortResult,
    RouteQuery,
)
from app.agents.route_planner.ports import PoiPort


class AmapRouteAdapter:
    def __init__(
        self,
        poi_port: PoiPort,
        client: httpx.Client,
        api_key: str,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self._poi_port = poi_port
        self._client = client
        self._api_key = api_key
        self._now = now if now is not None else lambda: datetime.now(UTC)

    def route(self, query: RouteQuery) -> RoutePortResult:
        path = ROUTE_PATH_BY_TRANSPORT.get(query.transport)
        if path is None:
            return RoutePortError(
                kind=RouteErrorKind.DIRTY,
                message=f"unsupported transport for 高德: {query.transport}",
            )

        origin = self._coordinate(query.from_point_id)
        destination = self._coordinate(query.to_point_id)
        if origin is None or destination is None:
            return RoutePortError(
                kind=RouteErrorKind.DIRTY,
                message="missing authoritative coordinates for a leg endpoint",
            )

        params = {
            "origin": f"{origin[0]:.6f},{origin[1]:.6f}",
            "destination": f"{destination[0]:.6f},{destination[1]:.6f}",
            "key": self._api_key,
        }
        if path == AMAP_TRANSIT_PATH:
            city_code = amap_city_code(query.city_id)
            if city_code is None:
                return RoutePortError(
                    kind=RouteErrorKind.DIRTY,
                    message=f"missing 高德 adcode for city {query.city_id!r}",
                )
            params.update({"city": city_code, "cityd": city_code, "strategy": "0"})
        else:
            params["extensions"] = "base"

        try:
            response = self._client.get(path, params=params)
        except httpx.TimeoutException:
            return RoutePortError(kind=RouteErrorKind.TIMEOUT, message="高德 route timed out")
        except httpx.HTTPError as exc:
            return RoutePortError(kind=RouteErrorKind.UNREACHABLE, message=str(exc))

        try:
            body = response.json()
        except ValueError:
            return RoutePortError(kind=RouteErrorKind.DIRTY, message="高德 returned invalid JSON")

        if response.status_code != 200:
            return RoutePortError(
                kind=route_error_kind(response.status_code, body),
                message="高德 route request failed",
            )
        if not isinstance(body, dict) or body.get("status") != "1":
            return RoutePortError(
                kind=route_error_kind(200, body),
                message="高德 route request failed",
            )

        parsed = self._parse_route(body)
        if parsed is None:
            return RoutePortError(kind=RouteErrorKind.EMPTY, message="高德 returned no route path")
        distance_m, duration_min = parsed

        return RouteData(
            from_point_id=query.from_point_id,
            to_point_id=query.to_point_id,
            transport=query.transport,
            distance_m=distance_m,
            duration_min=duration_min,
            source_record_id=source_record_id(
                query.coordinate_system,
                query.city_id,
                query.from_point_id,
                query.to_point_id,
                query.transport,
            ),
            retrieved_at=self._now(),
            freshness_status="fresh",
            is_estimated=False,
        )

    def _coordinate(self, poi_id: str) -> tuple[float, float] | None:
        record = self._poi_port.get(poi_id)
        if record is None or record.longitude is None or record.latitude is None:
            return None
        return record.longitude, record.latitude

    def _parse_route(self, body: dict[object, object]) -> tuple[float, float] | None:
        route = body.get("route")
        if not isinstance(route, dict):
            return None
        paths = route.get("paths")
        if isinstance(paths, list) and paths and isinstance(paths[0], dict):
            distance_m = parse_number(paths[0].get("distance"))
            duration_seconds = parse_number(paths[0].get("duration"))
            if distance_m is None or duration_seconds is None:
                return None
            return distance_m, duration_seconds / 60.0

        # Transit returns route.transits[] instead of route.paths[].
        transits = route.get("transits")
        if isinstance(transits, list) and transits and isinstance(transits[0], dict):
            distance_m = parse_number(transits[0].get("distance"))
            duration_seconds = parse_number(transits[0].get("duration"))
            if distance_m is None or duration_seconds is None:
                return None
            return distance_m, duration_seconds / 60.0
        return None
