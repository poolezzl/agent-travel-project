"""Configured LLM provider construction."""

import httpx

from app.core.config import LLMSettings, get_llm_settings
from app.llm.openai_provider import OpenAICompatibleProvider
from app.llm.provider import LLMProvider, MockLLMProvider


def create_llm_provider(
    settings: LLMSettings | None = None,
    *,
    transport: httpx.AsyncBaseTransport | None = None,
) -> LLMProvider:
    """Create the configured provider without exposing supplier parameters to users."""
    resolved_settings = settings or get_llm_settings()

    if resolved_settings.llm_provider == "mock":
        return MockLLMProvider()

    if resolved_settings.llm_provider == "openai-compatible":
        return OpenAICompatibleProvider(
            resolved_settings,
            transport=transport,
        )

    raise ValueError("Unsupported LLM provider configuration")
