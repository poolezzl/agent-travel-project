"""Application configuration loaded from environment variables."""

from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime settings for the Agent service foundation."""

    model_config = SettingsConfigDict(
        env_file=(".env", "../.env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    app_profile: Literal["mock", "real"] = Field(
        default="mock",
        validation_alias="APP_PROFILE",
    )
    service_name: str = Field(
        default="travelstar-agent-service",
        validation_alias="AGENT_SERVICE_NAME",
    )
    host: str = Field(
        default="127.0.0.1",
        validation_alias="AGENT_SERVICE_HOST",
    )
    port: int = Field(
        default=8000,
        ge=1,
        le=65535,
        validation_alias="AGENT_SERVICE_PORT",
    )


@lru_cache
def get_settings() -> Settings:
    """Return one immutable configuration view per process."""

    return Settings()


class LLMSettings(BaseSettings):
    """LLM Gateway configuration."""

    model_config = SettingsConfigDict(
        env_file=(".env", "../.env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
        # Tests and composition roots pass the declared field names while production uses
        # the env aliases; accept both instead of silently dropping the field-name kwargs.
        populate_by_name=True,
    )

    llm_provider: Literal["mock", "openai-compatible"] = Field(
        default="mock",
        validation_alias="LLM_PROVIDER",
    )
    llm_base_url: str | None = Field(
        default=None,
        validation_alias="LLM_BASE_URL",
    )
    llm_api_key: str | None = Field(
        default=None,
        validation_alias="LLM_API_KEY",
    )
    llm_model: str = Field(
        default="mock-model",
        validation_alias="LLM_MODEL",
    )
    llm_timeout_seconds: int = Field(
        default=30,
        ge=1,
        le=300,
        validation_alias="LLM_TIMEOUT_SECONDS",
    )
    llm_max_retries: int = Field(
        default=1,
        ge=0,
        le=3,
        validation_alias="LLM_MAX_RETRIES",
    )
    llm_min_call_approved: bool = Field(
        default=False,
        validation_alias="LLM_MIN_CALL_APPROVED",
    )


@lru_cache
def get_llm_settings() -> LLMSettings:
    """Return LLM configuration view."""
    return LLMSettings()


class RouteAdapterSettings(BaseSettings):
    """Route provider (S10 高德) configuration, mirrored from backend AdapterProperties."""

    model_config = SettingsConfigDict(
        env_file=(".env", "../.env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    route_provider: Literal["mock", "amap"] = Field(
        default="mock",
        validation_alias="ROUTE_PROVIDER",
    )
    route_cache_enabled: bool = Field(
        default=True,
        validation_alias="ROUTE_CACHE_ENABLED",
    )
    route_cache_ttl_seconds: int = Field(
        default=604800,
        ge=1,
        validation_alias="ROUTE_CACHE_TTL_SECONDS",
    )
    amap_base_url: str = Field(
        default="https://restapi.amap.com",
        validation_alias="AMAP_BASE_URL",
    )
    amap_api_key: str = Field(
        default="",
        validation_alias="AMAP_API_KEY",
    )
    amap_timeout_ms: int = Field(
        default=1500,
        ge=1,
        validation_alias="AMAP_TIMEOUT_MS",
    )
    amap_max_retries: int = Field(
        default=1,
        ge=0,
        le=3,
        validation_alias="AMAP_MAX_RETRIES",
    )


@lru_cache
def get_route_settings() -> RouteAdapterSettings:
    """Return route-provider configuration view."""
    return RouteAdapterSettings()


class WeatherAdapterSettings(BaseSettings):
    """Weather provider (S10 和风天气) configuration, mirrored from backend AdapterProperties."""

    model_config = SettingsConfigDict(
        env_file=(".env", "../.env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    weather_provider: Literal["mock", "qweather"] = Field(
        default="mock",
        validation_alias="WEATHER_PROVIDER",
    )
    weather_cache_ttl_seconds: int = Field(
        default=3600,
        ge=1,
        validation_alias="WEATHER_CACHE_TTL_SECONDS",
    )
    weather_climate_retention_seconds: int = Field(
        default=2592000,
        ge=1,
        validation_alias="WEATHER_CLIMATE_RETENTION_SECONDS",
    )
    qweather_alias: str = Field(
        default="qweather",
        validation_alias="QWEATHER_PROVIDER_ALIAS",
    )
    qweather_api_host: str = Field(
        default="https://devapi.qweather.com",
        validation_alias="QWEATHER_API_HOST",
    )
    qweather_api_key: str = Field(
        default="",
        validation_alias="QWEATHER_API_KEY",
    )
    qweather_forecast_days: int = Field(
        default=3,
        ge=1,
        le=30,
        validation_alias="QWEATHER_FORECAST_DAYS",
    )
    qweather_zone_id: str = Field(
        default="Asia/Shanghai",
        validation_alias="QWEATHER_ZONE_ID",
    )
    qweather_city_map: str = Field(
        default="",
        validation_alias="QWEATHER_CITY_MAP",
    )
    qweather_timeout_seconds: float = Field(
        default=2.0,
        gt=0.0,
        validation_alias="QWEATHER_TIMEOUT_SECONDS",
    )


@lru_cache
def get_weather_settings() -> WeatherAdapterSettings:
    """Return weather-provider configuration view."""
    return WeatherAdapterSettings()
