"""FastAPI application entry point."""

import uvicorn
from fastapi import FastAPI

from app.api.agents import router as agents_router
from app.api.health import router as health_router
from app.api.planning import router as planning_router
from app.core.config import get_settings

settings = get_settings()

app = FastAPI(
    title="TravelStar Agent Service",
    version="0.1.0",
    description="Python Agent service foundation for TravelStar.",
)

app.include_router(health_router)
app.include_router(agents_router)
app.include_router(planning_router)


if __name__ == "__main__":
    uvicorn.run(app, host=settings.host, port=settings.port)
