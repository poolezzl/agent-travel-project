"""Health check endpoint."""

from fastapi import APIRouter

from app.core.config import get_settings

router = APIRouter(tags=["system"])


@router.get("/health")
def health() -> dict[str, str]:
    """Return basic service liveness information."""
    settings = get_settings()
    return {
        "status": "ok",
        "service": settings.service_name,
        "profile": settings.app_profile,
    }
