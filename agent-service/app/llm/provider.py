"""LLM Provider abstraction and Mock implementation."""

import hashlib
import uuid
from abc import ABC, abstractmethod
from datetime import UTC, datetime
from typing import Literal

from app.llm.models import LLMCallMetadata, LLMRequest, LLMResponse


class LLMProvider(ABC):
    """Abstract base class for LLM providers."""

    @abstractmethod
    async def call(self, request: LLMRequest) -> tuple[LLMResponse, LLMCallMetadata]:
        """Execute LLM call and return response with audit metadata."""
        pass


class MockLLMProvider(LLMProvider):
    """Mock LLM provider for testing without external API calls."""

    def __init__(
        self,
        mock_response: str | None = None,
        mock_finish_reason: Literal["stop", "length", "error", "timeout"] = "stop",
        mock_tokens: int = 100,
        simulate_error: bool = False,
        simulate_timeout: bool = False,
    ):
        self.mock_response = mock_response
        self.mock_finish_reason = mock_finish_reason
        self.mock_tokens = mock_tokens
        self.simulate_error = simulate_error
        self.simulate_timeout = simulate_timeout

    async def call(self, request: LLMRequest) -> tuple[LLMResponse, LLMCallMetadata]:
        """Return mock response for testing."""
        call_id = str(uuid.uuid4())
        request_timestamp = datetime.now(UTC)

        prompt_hash = hashlib.sha256(request.prompt.encode("utf-8")).hexdigest()

        if self.simulate_timeout:
            metadata = LLMCallMetadata(
                call_id=call_id,
                provider="mock",
                model_alias="mock-model",
                prompt_hash=prompt_hash,
                request_timestamp=request_timestamp,
                response_timestamp=datetime.now(UTC),
                status="timeout",
                error_message="Simulated timeout",
                latency_ms=request.timeout_seconds * 1000,
            )
            response = LLMResponse(
                content="",
                model="mock-model",
                finish_reason="timeout",
            )
            return response, metadata

        if self.simulate_error:
            metadata = LLMCallMetadata(
                call_id=call_id,
                provider="mock",
                model_alias="mock-model",
                prompt_hash=prompt_hash,
                request_timestamp=request_timestamp,
                response_timestamp=datetime.now(UTC),
                status="error",
                error_message="Simulated provider error",
                latency_ms=50,
            )
            response = LLMResponse(
                content="",
                model="mock-model",
                finish_reason="error",
            )
            return response, metadata

        # Success path
        content = self.mock_response or '{"status": "success", "data": "mock response"}'

        response = LLMResponse(
            content=content,
            model="mock-model",
            finish_reason=self.mock_finish_reason,
            prompt_tokens=self.mock_tokens // 2,
            completion_tokens=self.mock_tokens // 2,
            total_tokens=self.mock_tokens,
        )

        metadata = LLMCallMetadata(
            call_id=call_id,
            provider="mock",
            model_alias="mock-model",
            prompt_hash=prompt_hash,
            request_timestamp=request_timestamp,
            response_timestamp=datetime.now(UTC),
            status="success",
            prompt_tokens=response.prompt_tokens,
            completion_tokens=response.completion_tokens,
            total_tokens=response.total_tokens,
            cost_estimate=0.0,
            latency_ms=10,
        )

        return response, metadata
