"""Composition root for the S19 orchestrator (offline-safe defaults)."""

from __future__ import annotations

import os
from collections.abc import Callable
from functools import lru_cache
from pathlib import Path

import httpx

from app.agents.route_planner.adapters.amap_route_adapter import AmapRouteAdapter
from app.agents.route_planner.mocks import MockRouteCache
from app.agents.route_planner.ports import PoiPort
from app.agents.route_planner.route_provider import RouteProvider
from app.agents.weather.mocks import MockWeatherCache
from app.agents.weather.weather_provider import WeatherProvider
from app.agents.weather.wiring import build_weather_deps
from app.core.config import get_route_settings, get_weather_settings
from app.knowledge.catalog import PublishedCatalog
from app.knowledge.dining_lodging import JsonPoiSource
from app.orchestrator.deps import OrchestratorDeps, UnavailableWeatherPort

_SERVICE_ROOT = Path(__file__).resolve().parents[2]


@lru_cache(maxsize=1)
def load_catalog() -> PublishedCatalog:
    configured = os.environ.get("AGENT_KNOWLEDGE_CATALOG", "resources/published_catalog.json")
    path = Path(configured)
    if not path.is_absolute():
        path = _SERVICE_ROOT / path
    return PublishedCatalog.load(path)


@lru_cache(maxsize=1)
def load_poi_source() -> JsonPoiSource:
    configured = os.environ.get("AGENT_DINING_POI_SOURCE", "resources/dining_lodging_pois.json")
    path = Path(configured)
    if not path.is_absolute():
        path = _SERVICE_ROOT / path
    return JsonPoiSource.load(path)


def build_default_deps() -> OrchestratorDeps:
    settings = get_weather_settings()
    if settings.weather_provider == "qweather" and settings.qweather_api_key:
        weather_port, cache = build_weather_deps()
        weather_provider = WeatherProvider(weather_port, cache, settings.qweather_zone_id)
    else:
        # No hidden fake weather: an unconfigured provider stays explicitly unavailable.
        weather_provider = WeatherProvider(
            UnavailableWeatherPort(), MockWeatherCache(), settings.qweather_zone_id
        )
    route_factory: Callable[[PoiPort], RouteProvider] | None = None
    route_settings = get_route_settings()
    if route_settings.route_provider == "amap" and route_settings.amap_api_key:
        client = httpx.Client(
            base_url=route_settings.amap_base_url,
            timeout=route_settings.amap_timeout_ms / 1000.0,
        )

        def route_factory(poi_port: PoiPort) -> RouteProvider:
            return RouteProvider(
                AmapRouteAdapter(poi_port, client, route_settings.amap_api_key),
                MockRouteCache(),
            )

    return OrchestratorDeps(
        catalog=load_catalog(),
        poi_source=load_poi_source(),
        weather_provider=weather_provider,
        forecast_horizon_days=settings.qweather_forecast_days,
        route_provider_factory=route_factory,
    )


__all__ = ["build_default_deps", "load_catalog", "load_poi_source"]
