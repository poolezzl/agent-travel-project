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
