"""Injection points for the S19 orchestrator (ports, clock, published catalog)."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime

from app.agents.route_planner.ports import PoiPort
from app.agents.route_planner.route_provider import RouteProvider
from app.knowledge.catalog import PublishedCatalog
from app.knowledge.dining_lodging import PoiSource


class UnavailableWeatherPort:
    """Explicit no-provider weather port: never invents weather facts."""

    def forecast(self, city_id: str, day: object) -> object:
        from app.agents.weather.errors import WeatherErrorKind
        from app.agents.weather.models import WeatherPortError

        return WeatherPortError(
            kind=WeatherErrorKind.UNREACHABLE, message="weather provider not configured"
        )

    def climate(self, city_id: str, day: object) -> object:
        return self.forecast(city_id, day)


@dataclass
class OrchestratorDeps:
    """Everything the DAG needs, injected so tests stay offline and deterministic."""

    catalog: PublishedCatalog
    poi_source: PoiSource
    weather_provider: object
    now: Callable[[], datetime] = field(default=lambda: datetime.now(UTC))
    forecast_horizon_days: int = 3
    route_provider_mode: str = "estimate"
    max_points_per_day: int = 3
    route_provider_factory: Callable[[PoiPort], RouteProvider] | None = None

    @property
    def sample_only(self) -> bool:
        return bool(getattr(self.catalog, "sample_only", False))


__all__ = ["OrchestratorDeps", "UnavailableWeatherPort"]
