"""Stable error kinds for the weather agent (ALG-07 degradation ladder)."""

from __future__ import annotations

from enum import StrEnum


class WeatherErrorKind(StrEnum):
    """Weather port error kinds consumed by the degradation ladder."""

    TIMEOUT = "TIMEOUT"
    RATE_LIMITED = "RATE_LIMITED"
    QUOTA = "QUOTA"
    AUTH = "AUTH"
    EMPTY = "EMPTY"
    DIRTY = "DIRTY"
    UNREACHABLE = "UNREACHABLE"
