"""FastAPI routes for the S16 attractions and S17 reservation agents."""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Body

from app.agents import attractions, reservation_rules
from app.agents.common import new_context
from app.knowledge.catalog import CatalogError, PublishedCatalog

router = APIRouter(prefix="/v1/agents", tags=["r3-agents"])

_SERVICE_ROOT = Path(__file__).resolve().parents[2]


@lru_cache(maxsize=1)
def get_catalog() -> PublishedCatalog:
    configured = os.environ.get("AGENT_KNOWLEDGE_CATALOG", "resources/published_catalog.json")
    path = Path(configured)
    if not path.is_absolute():
        path = _SERVICE_ROOT / path
    return PublishedCatalog.load(path)


def _context(body: dict[str, Any]) -> dict[str, str]:
    generated = new_context()
    for key in ("schema_version", "task_id", "trace_id", "request_id", "generated_at"):
        value = body.get(key)
        if isinstance(value, str) and value:
            generated[key] = value
    return generated


def _inputs(body: dict[str, Any]) -> dict[str, Any]:
    nested = body.get("input")
    return nested if isinstance(nested, dict) else body


def _invoke(agent: str, body: dict[str, Any]) -> dict[str, Any]:
    try:
        catalog = get_catalog()
    except CatalogError as exc:
        return {
            "schema_version": "agent-contract-v1",
            "task_id": body.get("task_id", "task-unavailable"),
            "trace_id": body.get("trace_id", "trace-unavailable"),
            "request_id": body.get("request_id", "req-unavailable"),
            "generated_at": new_context()["generated_at"],
            "status": "failed",
            "error_code": "SOURCE_UNAVAILABLE",
            "message": str(exc),
            "source_metadata": [],
        }
    context = _context(body)
    inputs = _inputs(body)
    if agent == "attractions":
        return attractions.run(inputs, catalog, context)
    return reservation_rules.run(inputs, catalog, context)


@router.post("/attractions/invoke")
def invoke_attractions(body: dict[str, Any] = Body(...)) -> dict[str, Any]:
    """Run the S16 attractions agent against the published knowledge snapshot."""
    return _invoke("attractions", body)


@router.post("/reservation-rules/invoke")
def invoke_reservation_rules(body: dict[str, Any] = Body(...)) -> dict[str, Any]:
    """Run the S17 reservation-rules agent against published rules."""
    return _invoke("reservation-rules", body)


@router.get("/catalog")
def catalog_status() -> dict[str, Any]:
    """Expose only non-sensitive catalog metadata for local debugging."""
    catalog = get_catalog()
    return {
        "catalog_version": catalog.catalog_version,
        "poi_count": len(catalog.pois),
        "published_entry_count": len(catalog.entries),
        "published_rule_count": len(catalog.rules),
    }
