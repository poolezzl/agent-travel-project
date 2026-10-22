"""Tests for configuration-driven LLM provider construction."""

import httpx
import pytest
from app.core.config import LLMSettings
from app.llm.factory import create_llm_provider
from app.llm.openai_provider import OpenAICompatibleProvider
from app.llm.provider import MockLLMProvider


def test_factory_creates_mock_provider_without_external_credentials():
    provider = create_llm_provider(
        LLMSettings(
            llm_provider="mock",
            llm_base_url=None,
            llm_api_key=None,
            llm_model="mock-model",
        )
    )

    assert isinstance(provider, MockLLMProvider)


def test_factory_creates_openai_compatible_provider():
    transport = httpx.MockTransport(
        lambda request: httpx.Response(
            200,
            json={
                "model": "resolved-model",
                "choices": [
                    {
                        "message": {"content": "ok"},
                        "finish_reason": "stop",
                    }
                ],
            },
        )
    )
    provider = create_llm_provider(
        LLMSettings(
            llm_provider="openai-compatible",
            llm_base_url="https://llm.example.test/v1",
            llm_api_key="test-secret",
            llm_model="test-model",
        ),
        transport=transport,
    )

    assert isinstance(provider, OpenAICompatibleProvider)


@pytest.mark.parametrize(
    ("base_url", "api_key", "expected_message"),
    [
        (None, "test-secret", "LLM_BASE_URL is required"),
        ("https://llm.example.test/v1", None, "LLM_API_KEY is required"),
    ],
)
def test_real_provider_requires_configuration(base_url, api_key, expected_message):
    settings = LLMSettings(
        llm_provider="openai-compatible",
        llm_base_url=base_url,
        llm_api_key=api_key,
        llm_model="test-model",
    )

    with pytest.raises(ValueError, match=expected_message):
        create_llm_provider(settings)
