"""OpenAI-compatible chat completion provider."""

import asyncio
import hashlib
import time
import uuid
from datetime import UTC, datetime
from typing import Any, Literal

import httpx

from app.core.config import LLMSettings
from app.llm.models import LLMCallMetadata, LLMRequest, LLMResponse
from app.llm.provider import LLMProvider


class LLMProviderError(RuntimeError):
    """Sanitized provider failure with a stable error code."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class OpenAICompatibleProvider(LLMProvider):
    """Adapter for the OpenAI Chat Completions-compatible API."""

    def __init__(
        self,
        settings: LLMSettings,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if not settings.llm_base_url:
            raise ValueError("LLM_BASE_URL is required")
        if not settings.llm_api_key:
            raise ValueError("LLM_API_KEY is required")
        self._settings = settings
        self._base_url: str = settings.llm_base_url
        self._api_key: str = settings.llm_api_key
        self._transport = transport

    async def call(self, request: LLMRequest) -> tuple[LLMResponse, LLMCallMetadata]:
        started_at = datetime.now(UTC)
        started = time.monotonic()
        call_id = str(uuid.uuid4())
        prompt_hash = hashlib.sha256(request.prompt.encode("utf-8")).hexdigest()
        messages: list[dict[str, str]] = []
        if request.system_prompt:
            messages.append({"role": "system", "content": request.system_prompt})
        messages.append({"role": "user", "content": request.prompt})

        payload: dict[str, Any] = {
            "model": self._settings.llm_model,
            "messages": messages,
            "max_tokens": request.max_tokens,
            "temperature": request.temperature,
        }
        url = f"{self._base_url.rstrip('/')}/chat/completions"
        headers = {"Authorization": f"Bearer {self._api_key}"}
        timeout = min(request.timeout_seconds, self._settings.llm_timeout_seconds)

        for attempt in range(self._settings.llm_max_retries + 1):
            try:
                async with httpx.AsyncClient(
                    transport=self._transport,
                    timeout=timeout,
                ) as client:
                    response = await client.post(url, json=payload, headers=headers)
            except httpx.TimeoutException as exc:
                raise LLMProviderError("LLM_TIMEOUT", "LLM provider request timed out") from exc
            except httpx.RequestError as exc:
                if attempt < self._settings.llm_max_retries:
                    await asyncio.sleep(0)
                    continue
                raise LLMProviderError(
                    "LLM_PROVIDER_UNAVAILABLE", "LLM provider request failed"
                ) from exc

            if response.status_code in (401, 403):
                raise LLMProviderError("LLM_AUTH_FAILED", "LLM provider authentication failed")
            if response.status_code == 429:
                try:
                    error_text = response.json().get("error", {}).get("message", "")
                except (ValueError, AttributeError):
                    error_text = ""
                code = (
                    "LLM_QUOTA_EXHAUSTED"
                    if "quota" in str(error_text).lower()
                    else "LLM_RATE_LIMITED"
                )
                raise LLMProviderError(code, "LLM provider rate limit or quota exceeded")
            if response.status_code >= 500:
                if attempt < self._settings.llm_max_retries:
                    await asyncio.sleep(0)
                    continue
                raise LLMProviderError("LLM_PROVIDER_UNAVAILABLE", "LLM provider is unavailable")
            if response.status_code >= 400:
                raise LLMProviderError("LLM_INVALID_REQUEST", "LLM provider rejected request")

            try:
                body = response.json()
                choice = body["choices"][0]
                content = choice["message"]["content"]
                model = body.get("model", self._settings.llm_model)
                raw_finish_reason = choice.get("finish_reason")
                usage = body.get("usage") or {}
                if not isinstance(content, str) or not content.strip():
                    raise ValueError("empty content")

                finish_reason: Literal["stop", "length", "error", "timeout"]
                if raw_finish_reason == "length":
                    finish_reason = "length"
                elif raw_finish_reason == "error":
                    finish_reason = "error"
                elif raw_finish_reason == "timeout":
                    finish_reason = "timeout"
                else:
                    finish_reason = "stop"
                result = LLMResponse(
                    content=content,
                    model=model,
                    finish_reason=finish_reason,
                    prompt_tokens=usage.get("prompt_tokens"),
                    completion_tokens=usage.get("completion_tokens"),
                    total_tokens=usage.get("total_tokens"),
                )
            except (ValueError, KeyError, IndexError, TypeError) as exc:
                raise LLMProviderError(
                    "LLM_DIRTY_RESPONSE", "LLM provider returned an invalid response"
                ) from exc

            metadata = LLMCallMetadata(
                call_id=call_id,
                provider="openai-compatible",
                model_alias=self._settings.llm_model,
                prompt_hash=prompt_hash,
                request_timestamp=started_at,
                response_timestamp=datetime.now(UTC),
                status="success",
                prompt_tokens=result.prompt_tokens,
                completion_tokens=result.completion_tokens,
                total_tokens=result.total_tokens,
                latency_ms=max(0, int((time.monotonic() - started) * 1000)),
            )
            return result, metadata

        raise LLMProviderError("LLM_PROVIDER_UNAVAILABLE", "LLM provider is unavailable")
