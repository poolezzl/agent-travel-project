"""Offline tests for the OpenAI-compatible provider adapter."""

import httpx
import pytest
from app.core.config import LLMSettings
from app.llm.models import LLMRequest
from app.llm.openai_provider import LLMProviderError, OpenAICompatibleProvider


def make_settings(**overrides: object) -> LLMSettings:
    values: dict[str, object] = {
        "llm_provider": "openai-compatible",
        "llm_base_url": "https://llm.example.test/v1",
        "llm_api_key": "test-secret",
        "llm_model": "test-model",
        "llm_max_retries": 1,
    }
    values.update(overrides)
    return LLMSettings(**values)


@pytest.mark.asyncio
async def test_success_normalizes_response_and_request():
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(
            200,
            json={
                "model": "resolved-model",
                "choices": [
                    {
                        "message": {"content": '{"ok": true}'},
                        "finish_reason": "stop",
                    }
                ],
                "usage": {
                    "prompt_tokens": 3,
                    "completion_tokens": 4,
                    "total_tokens": 7,
                },
            },
        )

    provider = OpenAICompatibleProvider(make_settings(), transport=httpx.MockTransport(handler))
    result, metadata = await provider.call(LLMRequest(prompt="hello", system_prompt="system"))

    assert result.content == '{"ok": true}'
    assert result.model == "resolved-model"
    assert result.total_tokens == 7
    assert metadata.status == "success"
    assert metadata.provider == "openai-compatible"
    assert seen[0].url.path == "/v1/chat/completions"
    assert seen[0].headers["authorization"] == "Bearer test-secret"
    assert b'"role":"system"' in seen[0].content


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("status", "body", "expected_code"),
    [
        (401, {"error": {"message": "unauthorized"}}, "LLM_AUTH_FAILED"),
        (403, {"error": {"message": "forbidden"}}, "LLM_AUTH_FAILED"),
        (429, {"error": {"message": "rate limit"}}, "LLM_RATE_LIMITED"),
        (429, {"error": {"message": "quota exceeded"}}, "LLM_QUOTA_EXHAUSTED"),
        (400, {"error": {"message": "invalid"}}, "LLM_INVALID_REQUEST"),
        (200, {"choices": []}, "LLM_DIRTY_RESPONSE"),
        (
            200,
            {"choices": [{"message": {"content": " "}, "finish_reason": "stop"}]},
            "LLM_DIRTY_RESPONSE",
        ),
    ],
)
async def test_errors_have_stable_codes(status, body, expected_code):
    provider = OpenAICompatibleProvider(
        make_settings(),
        transport=httpx.MockTransport(lambda request: httpx.Response(status, json=body)),
    )

    with pytest.raises(LLMProviderError) as error:
        await provider.call(LLMRequest(prompt="hello"))

    assert error.value.code == expected_code
    assert "test-secret" not in str(error.value)


@pytest.mark.asyncio
async def test_server_error_retries_are_bounded():
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(503, json={"error": {"message": "unavailable"}})

    provider = OpenAICompatibleProvider(
        make_settings(llm_max_retries=2),
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(LLMProviderError) as error:
        await provider.call(LLMRequest(prompt="hello"))

    assert error.value.code == "LLM_PROVIDER_UNAVAILABLE"
    assert calls == 3


@pytest.mark.asyncio
async def test_auth_failure_is_not_retried():
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(401, json={"error": {"message": "unauthorized"}})

    provider = OpenAICompatibleProvider(
        make_settings(llm_max_retries=3),
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(LLMProviderError) as error:
        await provider.call(LLMRequest(prompt="hello"))

    assert error.value.code == "LLM_AUTH_FAILED"
    assert calls == 1


@pytest.mark.asyncio
async def test_timeout_maps_to_stable_code_without_retry():
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        raise httpx.ReadTimeout("simulated timeout", request=request)

    provider = OpenAICompatibleProvider(
        make_settings(llm_max_retries=3),
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(LLMProviderError) as error:
        await provider.call(LLMRequest(prompt="hello"))

    assert error.value.code == "LLM_TIMEOUT"
    assert calls == 1
    assert "test-secret" not in str(error.value)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "content",
    [
        "not-json",
        "[]",
        "{}",
        '{"choices": "not-an-array"}',
        '{"choices": [{"message": null}]}',
    ],
)
async def test_malformed_provider_payload_maps_to_dirty_response(content):
    provider = OpenAICompatibleProvider(
        make_settings(),
        transport=httpx.MockTransport(lambda request: httpx.Response(200, content=content)),
    )

    with pytest.raises(LLMProviderError) as error:
        await provider.call(LLMRequest(prompt="hello"))

    assert error.value.code == "LLM_DIRTY_RESPONSE"
    assert "test-secret" not in str(error.value)
