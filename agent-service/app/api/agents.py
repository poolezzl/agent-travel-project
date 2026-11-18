"""FastAPI routes for the S16 attractions and S17 reservation agents."""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Body

from app.agents import attractions, dining_lodging, reservation_rules, revision, revision_execution
from app.agents.common import failure, new_context
from app.knowledge.catalog import CatalogError, PublishedCatalog
from app.knowledge.dining_lodging import JsonPoiSource, PoiSourceError
from app.orchestrator.runtime import build_default_deps

router = APIRouter(prefix="/v1/agents", tags=["r3-agents"])

_SERVICE_ROOT = Path(__file__).resolve().parents[2]


@lru_cache(maxsize=1)
def get_catalog() -> PublishedCatalog:
    configured = os.environ.get("AGENT_KNOWLEDGE_CATALOG", "resources/published_catalog.json")
    path = Path(configured)
    if not path.is_absolute():
        path = _SERVICE_ROOT / path
    return PublishedCatalog.load(path)


@lru_cache(maxsize=1)
def get_dining_source() -> JsonPoiSource:
    configured = os.environ.get("AGENT_DINING_POI_SOURCE", "resources/dining_lodging_pois.json")
    path = Path(configured)
    if not path.is_absolute():
        path = _SERVICE_ROOT / path
    return JsonPoiSource.load(path)


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
    context = _context(body)
    inputs = _inputs(body)
    if agent == "dining-lodging":
        try:
            source = get_dining_source()
        except PoiSourceError as exc:
            return failure(context, "SOURCE_UNAVAILABLE", str(exc))
        return dining_lodging.run(inputs, context, poi_source=source)
    try:
        catalog = get_catalog()
    except CatalogError as exc:
        return failure(context, "SOURCE_UNAVAILABLE", str(exc))
    if agent == "attractions":
        return attractions.run(inputs, catalog, context)
    return reservation_rules.run(inputs, catalog, context)


@router.post("/attractions/invoke")
def invoke_attractions(body: dict[str, Any] = Body(...)) -> dict[str, Any]:  # noqa: B008
    """Run the S16 attractions agent against the published knowledge snapshot."""
    return _invoke("attractions", body)


@router.post("/dining-lodging/invoke")
def invoke_dining_lodging(body: dict[str, Any] = Body(...)) -> dict[str, Any]:  # noqa: B008
    """Run the S18 dining/lodging agent against legal POI records."""
    return _invoke("dining-lodging", body)


@router.post("/reservation-rules/invoke")
def invoke_reservation_rules(body: dict[str, Any] = Body(...)) -> dict[str, Any]:  # noqa: B008
    """Run the S17 reservation-rules agent against published rules."""
    return _invoke("reservation-rules", body)


@router.post("/revision/invoke")
def invoke_revision(body: dict[str, Any] = Body(...)) -> dict[str, Any]:  # noqa: B008
    """Classify one S20 query/change request and produce a bounded change proposal."""
    return revision.run(_inputs(body), _context(body))


@router.post("/revision/variants")
def plan_revision_variants(body: dict[str, Any] = Body(...)) -> dict[str, Any]:  # noqa: B008
    """Plan 1-3 deterministic variants while sharing collection snapshots."""
    return revision.plan_variants(_inputs(body), _context(body))


@router.post("/revision/apply")
def apply_revision(body: dict[str, Any] = Body(...)) -> dict[str, Any]:  # noqa: B008
    """Build a candidate from the backend's confirmed proposal."""
    return revision_execution.apply(_inputs(body), _context(body), build_default_deps())


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
