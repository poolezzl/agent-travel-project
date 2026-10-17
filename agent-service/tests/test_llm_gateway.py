"""Tests for LLM Gateway, Mock Provider, and structured output processing."""

import pytest
from app.llm.models import (
    LLMRequest,
    StructuredOutputRequest,
)
from app.llm.provider import MockLLMProvider
from app.llm.structured_output import StructuredOutputProcessor


@pytest.mark.asyncio
async def test_mock_provider_success():
    """Mock provider returns configured response."""
    provider = MockLLMProvider(
        mock_response='{"status": "ok"}',
        mock_tokens=50,
    )

    request = LLMRequest(prompt="test prompt")
    response, metadata = await provider.call(request)

    assert response.content == '{"status": "ok"}'
    assert response.model == "mock-model"
    assert response.finish_reason == "stop"
    assert metadata.status == "success"
    assert metadata.provider == "mock"
    assert metadata.total_tokens == 50


@pytest.mark.asyncio
async def test_mock_provider_timeout():
    """Mock provider simulates timeout."""
    provider = MockLLMProvider(simulate_timeout=True)

    request = LLMRequest(prompt="test prompt", timeout_seconds=5)
    response, metadata = await provider.call(request)

    assert response.finish_reason == "timeout"
    assert metadata.status == "timeout"
    assert "timeout" in metadata.error_message.lower()


@pytest.mark.asyncio
async def test_mock_provider_error():
    """Mock provider simulates error."""
    provider = MockLLMProvider(simulate_error=True)

    request = LLMRequest(prompt="test prompt")
    response, metadata = await provider.call(request)

    assert response.finish_reason == "error"
    assert metadata.status == "error"


@pytest.mark.asyncio
async def test_structured_output_valid_json():
    """Valid JSON passes validation on first attempt."""
    provider = MockLLMProvider(mock_response='{"name": "test", "value": 42}')
    processor = StructuredOutputProcessor(provider)

    schema = {
        "type": "object",
        "properties": {
            "name": {"type": "string"},
            "value": {"type": "number"},
        },
        "required": ["name", "value"],
    }

    request = StructuredOutputRequest(
        prompt="generate test data",
        json_schema=schema,
    )

    result = await processor.process(request)

    assert result.status == "success"
    assert result.validated_json == {"name": "test", "value": 42}
    assert result.repair_attempts == 0


@pytest.mark.asyncio
async def test_structured_output_markdown_wrapped():
    """JSON wrapped in markdown code block is extracted."""
    provider = MockLLMProvider(mock_response='```json\n{"name": "test", "value": 42}\n```')
    processor = StructuredOutputProcessor(provider)

    schema = {
        "type": "object",
        "properties": {
            "name": {"type": "string"},
            "value": {"type": "number"},
        },
        "required": ["name", "value"],
    }

    request = StructuredOutputRequest(
        prompt="generate test data",
        json_schema=schema,
    )

    result = await processor.process(request)

    assert result.status == "success"
    assert result.validated_json == {"name": "test", "value": 42}


@pytest.mark.asyncio
async def test_structured_output_invalid_json():
    """Invalid JSON that cannot be parsed fails extraction."""
    provider = MockLLMProvider(mock_response="This is not JSON at all")
    processor = StructuredOutputProcessor(provider)

    schema = {
        "type": "object",
        "properties": {"value": {"type": "number"}},
        "required": ["value"],
    }

    request = StructuredOutputRequest(
        prompt="generate test data",
        json_schema=schema,
        max_repair_attempts=0,
    )

    result = await processor.process(request)

    assert result.status == "validation_failed"
    assert "No valid JSON found" in result.error_detail


@pytest.mark.asyncio
async def test_structured_output_schema_validation_failure():
    """JSON that fails schema validation is rejected."""
    provider = MockLLMProvider(mock_response='{"name": "test"}')
    processor = StructuredOutputProcessor(provider)

    schema = {
        "type": "object",
        "properties": {
            "name": {"type": "string"},
            "value": {"type": "number"},
        },
        "required": ["name", "value"],
    }

    request = StructuredOutputRequest(
        prompt="generate test data",
        json_schema=schema,
        max_repair_attempts=0,
    )

    result = await processor.process(request)

    assert result.status == "validation_failed"
    assert "value" in result.error_detail.lower()


@pytest.mark.asyncio
async def test_structured_output_repair_attempts_exhausted():
    """After max repair attempts, validation failure is final."""
    provider = MockLLMProvider(mock_response='{"wrong_field": 123}')
    processor = StructuredOutputProcessor(provider)

    schema = {
        "type": "object",
        "properties": {"correct_field": {"type": "number"}},
        "required": ["correct_field"],
    }

    request = StructuredOutputRequest(
        prompt="generate test data",
        json_schema=schema,
        max_repair_attempts=2,
    )

    result = await processor.process(request)

    assert result.status == "validation_failed"
    assert result.repair_attempts == 2
    assert "after 2 repair attempts" in result.error_detail


@pytest.mark.asyncio
async def test_structured_output_llm_timeout():
    """LLM timeout during initial call returns error status."""
    provider = MockLLMProvider(simulate_timeout=True)
    processor = StructuredOutputProcessor(provider)

    schema = {"type": "object", "properties": {"value": {"type": "number"}}}

    request = StructuredOutputRequest(
        prompt="generate test data",
        json_schema=schema,
    )

    result = await processor.process(request)

    assert result.status == "error"
    assert "timeout" in result.error_detail.lower()
    assert result.repair_attempts == 0


@pytest.mark.asyncio
async def test_structured_output_llm_error():
    """LLM error during initial call returns error status."""
    provider = MockLLMProvider(simulate_error=True)
    processor = StructuredOutputProcessor(provider)

    schema = {"type": "object", "properties": {"value": {"type": "number"}}}

    request = StructuredOutputRequest(
        prompt="generate test data",
        json_schema=schema,
    )

    result = await processor.process(request)

    assert result.status == "error"
    assert "error" in result.error_detail.lower()
