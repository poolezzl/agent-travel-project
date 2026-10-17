"""Health check endpoint."""

from typing import TypedDict

from fastapi import APIRouter

from app.core.config import get_settings

router = APIRouter(tags=["system"])


class HealthDependencies(TypedDict):
    """Dependency status reported by the S03 liveness endpoint."""

    external_services: str


class HealthResponse(TypedDict):
    """Public, non-sensitive S03 health response."""

    status: str
    service: str
    profile: str
    dependencies: HealthDependencies


@router.get("/health")
def health() -> HealthResponse:
    """Return basic service liveness information."""
    settings = get_settings()
    return {
        "status": "ok",
        "service": settings.service_name,
        "profile": settings.app_profile,
        "dependencies": {"external_services": "disabled"},
    }
