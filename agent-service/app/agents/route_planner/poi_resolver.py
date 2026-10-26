"""ALG-04 POI disambiguation and input validation."""

from __future__ import annotations

from dataclasses import dataclass

from app.agents.route_planner.errors import ResolutionCode
from app.agents.route_planner.models import PointCandidate, PoiRecord, ResolvedPoint
from app.agents.route_planner.ports import PoiPort


@dataclass(frozen=True)
class Resolved:
    """A unique POI entity resolved from a candidate."""

    point: ResolvedPoint


@dataclass(frozen=True)
class Clarify:
    """The candidate needs user clarification (ambiguous or missing context)."""

    code: ResolutionCode
    reason: str
    candidates: tuple[PoiRecord, ...] = ()


@dataclass(frozen=True)
class ResolveFail:
    """The candidate cannot be resolved and must not enter the route."""

    code: ResolutionCode
    reason: str


ResolutionOutcome = Resolved | Clarify | ResolveFail


def resolve_poi(candidate: PointCandidate, city_id: str, port: PoiPort) -> ResolutionOutcome:
    """Resolve a candidate to a unique POI entity without guessing.

    Name is used only for disambiguation, never as a primary key. Coordinates, city and
    coordinate system always come from the authoritative POI port, never from the candidate.
    Same-name points are never merged automatically (no embedding merge).
    """
    name = candidate.name
    if name is None or name == "" or city_id == "":
        return Clarify(ResolutionCode.MISSING_CONTEXT, "name and city context are required")

    if candidate.poi_id:
        record = port.get(candidate.poi_id)
        if record is None:
            return ResolveFail(
                ResolutionCode.SOURCE_UNAVAILABLE,
                f"poi_id not found: {candidate.poi_id}",
            )
        matches: list[PoiRecord] = [record]
    else:
        matches = port.lookup(name, city_id)
        if not matches:
            return ResolveFail(
                ResolutionCode.SOURCE_UNAVAILABLE,
                f"no POI match for {name!r} in {city_id!r}",
            )
        if len(matches) > 1:
            return Clarify(
                ResolutionCode.AMBIGUOUS_NAME,
                f"ambiguous name {name!r}",
                tuple(matches),
            )

    poi = matches[0]
    if poi.city_id != city_id:
        return ResolveFail(
            ResolutionCode.CROSS_CITY,
            f"{poi.poi_id} belongs to {poi.city_id}, expected {city_id}",
        )
    if poi.latitude is None or poi.longitude is None:
        return ResolveFail(
            ResolutionCode.MISSING_COORDINATE,
            f"{poi.poi_id} has no coordinates",
        )
    if poi.coordinate_system != "GCJ02":
        return ResolveFail(
            ResolutionCode.COORDINATE_SYSTEM_MISMATCH,
            f"{poi.poi_id} uses {poi.coordinate_system}, expected GCJ02",
        )

    return Resolved(
        ResolvedPoint(
            poi_id=poi.poi_id,
            name=poi.name,
            city_id=poi.city_id,
            latitude=poi.latitude,
            longitude=poi.longitude,
            coordinate_system="GCJ02",
        )
    )
