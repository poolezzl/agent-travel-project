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
