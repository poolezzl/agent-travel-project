"""LLM Gateway data models for requests, responses, and audit metadata."""

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field


class LLMRequest(BaseModel):
    """Unified request to LLM provider."""

    prompt: str = Field(..., description="User or system prompt content")
    system_prompt: str | None = Field(default=None, description="Optional system message")
    max_tokens: int = Field(default=1024, ge=1, le=8192)
    temperature: float = Field(default=0.7, ge=0.0, le=2.0)
    timeout_seconds: int = Field(default=30, ge=1, le=300)


class LLMResponse(BaseModel):
    """Unified response from LLM provider."""

    content: str = Field(..., description="Raw model output")
    model: str = Field(..., description="Actual model used")
    finish_reason: Literal["stop", "length", "error", "timeout"] = Field(
        ..., description="Why generation stopped"
    )
    prompt_tokens: int | None = Field(default=None, ge=0)
    completion_tokens: int | None = Field(default=None, ge=0)
    total_tokens: int | None = Field(default=None, ge=0)


class LLMCallMetadata(BaseModel):
    """Audit metadata for LLM calls; does not store API keys or full prompts."""

    call_id: str = Field(..., description="Unique identifier for this call")
    provider: str = Field(..., description="Provider type (mock, openai-compatible)")
    model_alias: str = Field(..., description="Configured model name, not real model ID")
    prompt_hash: str = Field(..., description="SHA256 of prompt for deduplication, not content")
    request_timestamp: datetime = Field(
        default_factory=datetime.utcnow, description="When request was made"
    )
    response_timestamp: datetime | None = Field(default=None, description="When response completed")
    status: Literal["success", "error", "timeout", "rate_limited"] = Field(
        ..., description="Call outcome"
    )
    error_message: str | None = Field(default=None, description="Error if failed")
    prompt_tokens: int | None = Field(default=None, ge=0)
    completion_tokens: int | None = Field(default=None, ge=0)
    total_tokens: int | None = Field(default=None, ge=0)
    cost_estimate: float | None = Field(
        default=None, ge=0.0, description="Estimated cost in USD if available"
    )
    latency_ms: int | None = Field(default=None, ge=0, description="Call duration")


class StructuredOutputRequest(BaseModel):
    """Request for structured JSON extraction and validation."""

    prompt: str
    system_prompt: str | None = None
    json_schema: dict[str, Any] = Field(..., description="JSON Schema Draft-07 for validation")
    max_repair_attempts: int = Field(default=1, ge=0, le=3)
    timeout_seconds: int = Field(default=30, ge=1, le=300)


class StructuredOutputResponse(BaseModel):
    """Response after JSON extraction, parsing, validation, and optional repair."""

    status: Literal["success", "validation_failed", "extraction_failed", "error"]
    validated_json: dict[str, Any] | None = Field(
        default=None, description="Validated and parsed JSON if successful"
    )
    raw_content: str = Field(..., description="Original LLM output")
    error_detail: str | None = Field(default=None)
    repair_attempts: int = Field(default=0, ge=0)
    call_metadata: LLMCallMetadata
