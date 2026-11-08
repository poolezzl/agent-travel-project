"""Shared 高德 (AMap) Web Service field parsing and error-code mapping.

Mirrors the backend ``RealAmapAdapter`` (S10) error mapping so the Python adapters behave the
same way. Only allow-listed fields are mapped into internal models; any field that cannot be
mapped is dropped and recorded as PENDING, never guessed.
"""

from __future__ import annotations

import hashlib

from app.agents.route_planner.errors import RouteErrorKind

# 高德 infocode -> RouteErrorKind (from backend ProviderErrorCode mapping, S10).
_AUTH_CODES = frozenset({"10001", "10005", "10006", "10007"})
_QUOTA_CODES = frozenset({"10003", "10044"})
_RATE_CODES = frozenset({"10004", "10021", "10029", "10041"})
_INVALID_REQUEST_CODES = frozenset({"20000", "20001", "20002", "20003"})

# transport preference -> 高德 direction endpoint path. S10 only implements driving + walking;
# transit / mixed are not yet supported by the backend adapter (PENDING-R5).
ROUTE_PATH_BY_TRANSPORT: dict[str, str] = {
    "打车": "/v3/direction/driving",
    "步行": "/v3/direction/walking",
}


def parse_lnglat(value: object) -> tuple[float, float] | None:
    """Parse 高德 "lng,lat" into ``(longitude, latitude)``; ``None`` when malformed."""
    if not isinstance(value, str):
        return None
    parts = value.split(",")
    if len(parts) != 2:
        return None
    try:
        lng = float(parts[0])
        lat = float(parts[1])
    except ValueError:
        return None
    if not (-180.0 <= lng <= 180.0 and -90.0 <= lat <= 90.0):
        return None
    return lng, lat


def parse_number(value: object) -> float | None:
    """Parse a numeric value that 高德 may return as an int, float or string."""
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return None
    return None


def route_error_kind(status_code: int, body: object) -> RouteErrorKind:
    """Map 高德 HTTP status + infocode to a stable RouteErrorKind (never unhandled)."""
    if status_code in (401, 403):
        return RouteErrorKind.AUTH
    if status_code == 429:
        return _infocode_kind(body, RouteErrorKind.RATE_LIMITED)
    if status_code >= 500:
        return RouteErrorKind.UNREACHABLE
    if status_code < 200 or status_code >= 300:
        return RouteErrorKind.DIRTY
    infocode = _infocode(body)
    if infocode in _AUTH_CODES:
        return RouteErrorKind.AUTH
    if infocode in _QUOTA_CODES:
        return RouteErrorKind.QUOTA
    if infocode in _RATE_CODES:
        return RouteErrorKind.RATE_LIMITED
    if infocode in _INVALID_REQUEST_CODES:
        return RouteErrorKind.DIRTY
    return RouteErrorKind.DIRTY


def source_record_id(*parts: str) -> str:
    """Deterministic source_record_id (mirrors backend SHA-256 source id for traceability)."""
    digest = hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()[:16]
    return f"amap-route:{digest}"


def _infocode(body: object) -> str:
    if not isinstance(body, dict):
        return ""
    infocode = body.get("infocode")
    if isinstance(infocode, str):
        return infocode
    if isinstance(infocode, int):
        return str(infocode)
    return ""


def _infocode_kind(body: object, default: RouteErrorKind) -> RouteErrorKind:
    infocode = _infocode(body)
    if infocode in _QUOTA_CODES:
        return RouteErrorKind.QUOTA
    if infocode in _AUTH_CODES:
        return RouteErrorKind.AUTH
    if infocode in _RATE_CODES:
        return RouteErrorKind.RATE_LIMITED
    return default
