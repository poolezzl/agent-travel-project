"""Shared envelope, validation and source helpers for R3 agents."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any, Iterable

SCHEMA_VERSION = "agent-contract-v1"
ERROR_INVALID_INPUT = "INVALID_INPUT"


class InputError(ValueError):
    """Raised when an agent request violates the frozen input contract."""


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def isoformat(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def new_context() -> dict[str, str]:
    moment = now_utc()
    return {
        "schema_version": SCHEMA_VERSION,
        "task_id": f"task-{uuid.uuid4().hex[:12]}",
        "trace_id": f"trace-{uuid.uuid4().hex[:12]}",
        "request_id": f"req-{uuid.uuid4().hex[:12]}",
        "generated_at": isoformat(moment),
    }


def envelope(context: dict[str, Any], **payload: Any) -> dict[str, Any]:
    result = {
        "schema_version": context.get("schema_version", SCHEMA_VERSION),
        "task_id": context["task_id"],
        "trace_id": context["trace_id"],
        "request_id": context["request_id"],
        "generated_at": context["generated_at"],
    }
    result.update(payload)
    return result


def failure(context: dict[str, Any], code: str, message: str) -> dict[str, Any]:
    return envelope(
        context,
        status="failed",
        error_code=code,
        message=message,
        source_metadata=[],
    )


def validate_points(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list) or not value:
        raise InputError("points 必须是非空数组")
    points: list[dict[str, Any]] = []
    for index, item in enumerate(value):
        if not isinstance(item, dict):
            raise InputError(f"points[{index}] 必须是对象")
        point_id = item.get("point_id")
        name = item.get("name")
        if not isinstance(point_id, str) or not point_id.strip():
            raise InputError(f"points[{index}].point_id 不能为空")
        if not isinstance(name, str) or not name.strip():
            raise InputError(f"points[{index}].name 不能为空")
        latitude = item.get("latitude")
        longitude = item.get("longitude")
        if not isinstance(latitude, (int, float)) or not -90 <= float(latitude) <= 90:
            raise InputError(f"points[{index}].latitude 非法")
        if not isinstance(longitude, (int, float)) or not -180 <= float(longitude) <= 180:
            raise InputError(f"points[{index}].longitude 非法")
        points.append(
            {
                "point_id": point_id.strip(),
                "name": name.strip(),
                "latitude": float(latitude),
                "longitude": float(longitude),
            }
        )
    return points


def dedupe_sources(items: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[str, str, str]] = set()
    result: list[dict[str, Any]] = []
    for item in items:
        key = (
            str(item.get("source_type", "")),
            str(item.get("source_record_id", "")),
            str(item.get("retrieved_at", "")),
        )
        if key in seen:
            continue
        seen.add(key)
        result.append(item)
    return result
