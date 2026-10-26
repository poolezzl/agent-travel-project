"""Planar distance helpers for straight-line estimation (ALG-06)."""

from __future__ import annotations

import math

_EARTH_RADIUS_M = 6_371_000.0


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Return great-circle distance in metres between two coordinates.

    Coordinates are treated as already expressed in a consistent system (GCJ02); no datum
    conversion is performed here.
    """
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    a = math.sin(delta_phi / 2.0) ** 2 + (
        math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2
    )
    return 2.0 * _EARTH_RADIUS_M * math.asin(math.sqrt(a))


def straight_line_duration_min(distance_m: float, speed_kmh: float) -> float:
    """Convert a straight-line distance to an estimated duration at a fixed speed."""
    if distance_m < 0:
        raise ValueError("distance_m must be non-negative")
    if speed_kmh <= 0:
        raise ValueError("speed_kmh must be positive")
    return distance_m / 1000.0 / speed_kmh * 60.0
