"""Stable resolution and degradation codes for the route planner (ALG-04 / ALG-06).

``CROSS_CITY`` / ``MISSING_COORDINATE`` / ``COORDINATE_SYSTEM_MISMATCH`` / ``AMBIGUOUS_NAME``
are internal resolution outcomes not yet present in ``contracts/error-codes/error-codes.md``
(PENDING-R2/R1). At the envelope level they surface as ``INVALID_INPUT`` /
``SOURCE_UNAVAILABLE`` with the specific reason kept in ``message``.
"""

from __future__ import annotations

from enum import StrEnum


class ResolutionCode(StrEnum):
    """Outcome of POI disambiguation (ALG-04)."""

    MISSING_CONTEXT = "MISSING_CONTEXT"
    SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE"
    AMBIGUOUS_NAME = "AMBIGUOUS_NAME"
    CROSS_CITY = "CROSS_CITY"
    MISSING_COORDINATE = "MISSING_COORDINATE"
    COORDINATE_SYSTEM_MISMATCH = "COORDINATE_SYSTEM_MISMATCH"


class RouteErrorKind(StrEnum):
    """Map/route port error kinds consumed by the ALG-06 degradation ladder."""

    TIMEOUT = "TIMEOUT"
    RATE_LIMITED = "RATE_LIMITED"
    QUOTA = "QUOTA"
    AUTH = "AUTH"
    EMPTY = "EMPTY"
    DIRTY = "DIRTY"
    UNREACHABLE = "UNREACHABLE"
    CROSS_CITY = "CROSS_CITY"
