"""FastAPI application entry point."""

from fastapi import FastAPI

from app.api.health import router as health_router
from app.core.config import get_settings

settings = get_settings()

app = FastAPI(
    title="TravelStar Agent Service",
    version="0.1.0",
    description="Python Agent service foundation for TravelStar.",
)

app.include_router(health_router)
