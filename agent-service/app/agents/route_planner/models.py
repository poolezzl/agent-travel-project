"""Internal data models for the route planner (S12).

These models carry fields that ``agents/02-route-planner/input.schema.json`` and
``output.schema.json`` do not yet freeze:

- ``RouteSegment`` implements the per-segment fields listed in
  ``agents/02-route-planner/prompt.txt`` line 26
  (``from_point_id`` / ``to_point_id`` / ``transport`` / ``distance_m`` / ``duration_min`` /
  ``source_record_id`` / ``retrieved_at`` / ``is_estimated``). The frozen output schema still
  defines ``segments`` as an unsubdivided array, so this is an **internal** model, not a
  contract change (PENDING-R2/R1).
- ``PointTiming`` (visit duration, opening hours, lunch break, must-visit) is sourced from the
  attractions / reservation-rule agents and passed through the orchestrator's internal task
  context; it is not part of the frozen route-planner input schema (PENDING).
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

from app.agents.route_planner.errors import RouteErrorKind

CoordinateSystem = Literal["GCJ02"]

# Explicit "absence" marker for POI fields the source does not provide. It is copied as-is from
# the authoritative boundary; adapters must never substitute a category taxonomy or a timestamp.
UNKNOWN_CATEGORY = "unknown"


class TimeWindow(BaseModel):
    """Day-level time window, HH:MM 24-hour."""

    start: str = Field(..., description="HH:MM start")
    end: str = Field(..., description="HH:MM end")


class LunchBreak(BaseModel):
    """Midday closure window for a point (e.g. 12:00-13:30)."""

    start: str = Field(..., description="HH:MM break start")
    end: str = Field(..., description="HH:MM break end")


class PointCandidate(BaseModel):
    """Raw, untrusted point received from upstream. Name is used only for disambiguation."""

    poi_id: str | None = None
    name: str | None = None
    city_id: str | None = None
    latitude: float | None = None
    longitude: float | None = None
    coordinate_system: str | None = None


class PoiRecord(BaseModel):
    """Authoritative POI entity returned by the POI port (S10).

    ``category`` and ``data_at`` are provided by the authoritative boundary. When the source
    has no category, the literal ``"unknown"`` is used; when it has no data timestamp,
    ``data_at`` stays ``None``. Neither is ever filled with a guessed value on the Python side.
    """

    poi_id: str
    name: str
    city_id: str
    latitude: float | None
    longitude: float | None
    coordinate_system: str | None
    category: str = UNKNOWN_CATEGORY
    data_at: datetime | None = None
    source_record_id: str | None = None
    retrieved_at: datetime | None = None
    freshness_status: Literal["fresh", "stale", "unknown"] = "unknown"


class ResolvedPoint(BaseModel):
    """A uniquely resolved POI ready to enter ALG-05."""

    poi_id: str
    name: str
    city_id: str
    latitude: float
    longitude: float
    coordinate_system: CoordinateSystem


class PointTiming(BaseModel):
    """Internal per-point schedule inputs (NOT part of the frozen route-planner schema)."""

    poi_id: str
    visit_duration_min: float = Field(default=60.0, ge=0.0)
    open_time: str | None = None
    close_time: str | None = None
    lunch_break: LunchBreak | None = None
    must_visit: bool = False


class RoutePlanRequest(BaseModel):
    """Internal route-planning request (superset of the frozen input schema)."""

    date: str
    city_id: str
    coordinate_system: str = "GCJ02"
    points: list[PointCandidate]
    origin: PointCandidate | None = None
    destination: PointCandidate | None = None
    transport_preference: str = "混合"
    time_window: TimeWindow
    point_timings: list[PointTiming] = Field(default_factory=list)


class RouteQuery(BaseModel):
    """A single leg query sent to the map/route port (S10)."""

    from_point_id: str
    to_point_id: str
    transport: str
    city_id: str
    coordinate_system: str = "GCJ02"
    time_slot: str | None = None


class RouteData(BaseModel):
    """A precise road route (exact) or a straight-line estimate."""

    from_point_id: str
    to_point_id: str
    transport: str
    distance_m: float
    duration_min: float
    source_record_id: str
    retrieved_at: datetime
    freshness_status: Literal["fresh", "stale", "unknown"] = "fresh"
    is_estimated: bool = False


class RoutePortError(BaseModel):
    """Typed failure returned by the map/route port."""

    kind: RouteErrorKind
    message: str


RoutePortResult = RouteData | RoutePortError


class RouteProviderResult(BaseModel):
    """Result of the ALG-06 three-level resolution for a single leg."""

    status: Literal["exact", "estimated", "unavailable"]
    data: RouteData | None = None
    error_kind: RouteErrorKind | None = None


class RouteSegment(BaseModel):
    """Per-segment route output (internal model, prompt.txt line 26 fields)."""

    from_point_id: str
    to_point_id: str
    transport: str
    distance_m: float
    duration_min: float
    source_record_id: str
    retrieved_at: datetime
    is_estimated: bool


class RouteOutput(BaseModel):
    """Route payload of the route-planner output envelope."""

    ordered_points: list[str]
    segments: list[RouteSegment]
    is_estimated: bool


class RoutePlanResult(BaseModel):
    """Envelope-ready result. System fields are injected by the gateway, not here."""

    status: Literal["ok", "degraded", "failed"]
    message: str | None = None
    error_code: str | None = None
    degraded_fields: list[str] = Field(default_factory=list)
    route: RouteOutput | None = None
