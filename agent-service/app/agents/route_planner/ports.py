"""Port interfaces (Protocols) the route planner depends on (S10 contract surface).

The real S10 adapters are owned by R5 (backend) and are **not present on dev-r2**. These
Protocols define the dependency surface S12 codes against; deterministic Mock implementations
live in ``app.agents.route_planner.mocks``. Real adapter wiring is PENDING-R5.
"""

from __future__ import annotations

from typing import Protocol

from app.agents.route_planner.models import PoiRecord, RouteData, RoutePortResult, RouteQuery


class PoiPort(Protocol):
    """POI lookup port (S10): resolve names/ids to authoritative records."""

    def lookup(self, name: str, city_id: str) -> list[PoiRecord]: ...

    def get(self, poi_id: str) -> PoiRecord | None: ...


class RoutePort(Protocol):
    """Map/route port (S10): precise road route between two points."""

    def route(self, query: RouteQuery) -> RoutePortResult: ...


class RouteCache(Protocol):
    """Route cache port (S10 / ADR-0006): exact fresh cache with original source time."""

    def get(self, key: str) -> RouteData | None: ...

    def put(self, key: str, data: RouteData) -> None: ...
