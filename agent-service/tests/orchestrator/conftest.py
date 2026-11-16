"""Offline fixtures: published catalog, legal POI source and a fixed weather provider."""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import pytest
from app.agents.weather.mocks import MockWeatherCache
from app.agents.weather.models import DailyWeather
from app.agents.weather.weather_provider import WeatherProvider
from app.knowledge.catalog import PublishedCatalog
from app.knowledge.dining_lodging import JsonPoiSource
from app.orchestrator.deps import OrchestratorDeps

NOW = datetime(2026, 11, 16, 6, 0, tzinfo=UTC)
SERVICE_ROOT = Path(__file__).resolve().parents[2]


class FixedWeatherPort:
    """Deterministic fresh forecast records for every requested day."""

    def forecast(self, city_id: str, day: date) -> DailyWeather:
        return DailyWeather(
            city_id=city_id,
            date=day,
            temp_min=4.0,
            temp_max=13.0,
            precip_prob=0.2,
            condition="晴间多云",
            data_type="forecast",
            source_record_id=f"test-weather:{city_id}:{day.isoformat()}",
            retrieved_at=NOW,
            valid_until=NOW + timedelta(days=10),
            freshness_status="fresh",
        )

    def climate(self, city_id: str, day: date) -> DailyWeather:
        return self.forecast(city_id, day)


@pytest.fixture
def catalog() -> PublishedCatalog:
    return PublishedCatalog.load(SERVICE_ROOT / "resources" / "published_catalog.json")


@pytest.fixture
def deps(catalog: PublishedCatalog) -> OrchestratorDeps:
    provider = WeatherProvider(
        FixedWeatherPort(),
        MockWeatherCache(),
        "Asia/Shanghai",
        now=lambda: NOW,
    )
    return OrchestratorDeps(
        catalog=catalog,
        poi_source=JsonPoiSource.load(SERVICE_ROOT / "resources" / "dining_lodging_pois.json"),
        weather_provider=provider,
        now=lambda: NOW,
        forecast_horizon_days=10,
    )


@pytest.fixture
def request_payload() -> dict[str, object]:
    return {
        "city": "北京",
        "start_date": "2026-11-20",
        "end_date": "2026-11-22",
        "party_size": 2,
        "budget": {"currency": "CNY", "amount_minor": 300000},
        "must_visit": ["故宫博物院"],
        "preferences": "历史",
        "transport": "混合",
        "plan_count": 1,
    }
