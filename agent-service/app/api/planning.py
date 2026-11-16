"""S19 planning orchestration endpoints consumed by the Java task worker."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Body

from app.orchestrator.runtime import build_default_deps
from app.orchestrator.service import STEPS, run_stage

router = APIRouter(prefix="/v1/planning", tags=["s19-orchestrator"])


@router.post("/steps/{step}")
def invoke_step(step: str, body: dict[str, Any] = Body(...)) -> dict[str, Any]:  # noqa: B008
    """Run one stage of the fixed DAG.

    The Java worker supplies the immutable task request plus the already persisted upstream
    stage results (``prior``). Every stage is idempotent for identical input bytes.
    """
    return run_stage(step, body, build_default_deps())


@router.get("/steps")
def list_steps() -> dict[str, Any]:
    """Expose the fixed stage order for contract checks and worker diagnostics."""
    return {"steps": list(STEPS), "dag": "parse->collect->schedule->route->assemble"}
