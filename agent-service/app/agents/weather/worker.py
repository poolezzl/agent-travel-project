"""S11 mounting adapter for the ``collect`` task step (task_step=COLLECTING).

``TaskWorkDefinitions`` maps ``COLLECTING`` to stepName ``collect``; the S11 worker calls
``execute(taskId, "collect", attemptNo)`` and persists ``WorkerResult`` under the unique key
``(task_id, step_id, attempt_no)`` plus a canonical JSON hash. This module is the Python
counterpart: it runs ``run_weather`` for every request (in parallel, deterministic order),
aggregates the results and projects ``source_metadata``.

Envelope system fields (``schema_version``/``task_id``/``trace_id``/``request_id``/
``generated_at``) are injected by S11 / the gateway, never here. ``forecast_horizon_days`` is
taken from the real adapter (``port.forecast_horizon_days``) when available — it is never
hard-coded — while ``today`` stays a caller-injected field.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Callable, Mapping
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from typing import Literal

from pydantic import BaseModel, Field

from app.agents.weather.agent import run_weather
from app.agents.weather.models import WeatherAgentResult, WeatherDaily, WeatherPlanRequest
from app.agents.weather.ports import WeatherPort
from app.agents.weather.weather_provider import WeatherProvider

StepStatus = Literal["ok", "partial", "degraded", "failed"]

_FAILED_MESSAGE = "天气服务暂不可用"


class WeatherStepResult(BaseModel):
    """``WorkerResult.result`` payload for the collect step (envelope fields excluded)."""

    status: StepStatus
    message: str | None = None
    error_code: str | None = None
    degraded_fields: list[str] = Field(default_factory=list)
    daily: list[WeatherDaily] = Field(default_factory=list)
    source_metadata: list[dict[str, object]] = Field(default_factory=list)
    fingerprint: str


def run_weather_step(
    task_id: str,
    attempt_no: int,
    requests: list[WeatherPlanRequest],
    provider: WeatherProvider,
    port: WeatherPort,
    now: Callable[[], datetime] | None = None,
) -> WeatherStepResult:
    """Run every weather request for one S11 attempt and aggregate the payload."""
    clock = now if now is not None else (lambda: datetime.now(UTC))
    horizon = getattr(port, "forecast_horizon_days", None)
    prepared = [
        request.model_copy(update={"forecast_horizon_days": horizon})
        if isinstance(horizon, int)
        else request
        for request in requests
    ]

    if not prepared:
        return _result(
            task_id,
            attempt_no,
            status="failed",
            message="no weather requests supplied",
            error_code="INVALID_INPUT",
            degraded_fields=[],
            daily=[],
            now=clock(),
        )

    results = _run_parallel(prepared, provider)
    status, message, error_code, degraded_fields = _aggregate(results)
    daily = [item for result in results for item in result.daily]
    return _result(
        task_id,
        attempt_no,
        status=status,
        message=message,
        error_code=error_code,
        degraded_fields=degraded_fields,
        daily=daily,
        now=clock(),
    )


def _run_parallel(
    requests: list[WeatherPlanRequest],
    provider: WeatherProvider,
) -> list[WeatherAgentResult]:
    if len(requests) == 1:
        return [run_weather(requests[0], provider)]
    workers = min(4, len(requests))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        # ``map`` preserves input order, keeping the payload deterministic.
        return list(pool.map(lambda request: run_weather(request, provider), requests))


def _aggregate(
    results: list[WeatherAgentResult],
) -> tuple[StepStatus, str | None, str | None, list[str]]:
    failed = [result for result in results if result.status == "failed"]
    degraded = [result for result in results if result.status == "degraded"]
    partial = [result for result in results if result.status == "partial"]

    if len(failed) == len(results):
        return (
            "failed",
            _FAILED_MESSAGE,
            failed[0].error_code or "SOURCE_UNAVAILABLE",
            [],
        )
    if failed or partial:
        messages = [result.message for result in failed + partial if result.message]
        return "partial", "; ".join(messages) or "部分日期天气不可用", None, ["daily"]
    if degraded:
        messages = [result.message for result in degraded if result.message]
        return "degraded", "; ".join(messages) or "部分天气数据已过期", None, ["daily"]
    return "ok", None, None, []


def _result(
    task_id: str,
    attempt_no: int,
    *,
    status: StepStatus,
    message: str | None,
    error_code: str | None,
    degraded_fields: list[str],
    daily: list[WeatherDaily],
    now: datetime,
) -> WeatherStepResult:
    source_metadata = _project_source_metadata(daily, now)
    payload = {
        "status": status,
        "message": message,
        "error_code": error_code,
        "degraded_fields": degraded_fields,
        "daily": [item.model_dump(mode="json") for item in daily],
        "source_metadata": source_metadata,
    }
    return WeatherStepResult(
        status=status,
        message=message,
        error_code=error_code,
        degraded_fields=degraded_fields,
        daily=daily,
        source_metadata=source_metadata,
        fingerprint=_fingerprint(task_id, attempt_no, payload),
    )


def _project_source_metadata(
    daily: list[WeatherDaily],
    now: datetime,
) -> list[dict[str, object]]:
    """Project each day's source into output-schema ``source_metadata`` items."""
    items: list[dict[str, object]] = []
    seen: set[str] = set()
    for item in daily:
        if item.source_record_id in seen:
            continue
        seen.add(item.source_record_id)
        items.append(
            {
                "source_type": "weather",
                "source_record_id": item.source_record_id,
                "retrieved_at": _as_utc_iso(item.retrieved_at),
                "valid_until": _as_utc_iso(item.valid_until) if item.valid_until else None,
                "freshness_status": _freshness(item, now),
                "is_estimated": False,
            }
        )
    return items


def _freshness(item: WeatherDaily, now: datetime) -> str:
    if item.valid_until is None:
        return "unknown"
    return "stale" if item.valid_until <= now else "fresh"


def _as_utc_iso(value: datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")


def _fingerprint(task_id: str, attempt_no: int, payload: Mapping[str, object]) -> str:
    canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(f"{task_id}:{attempt_no}:{canonical}".encode()).hexdigest()
