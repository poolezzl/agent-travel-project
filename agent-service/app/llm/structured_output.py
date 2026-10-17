"""Structured output processing: JSON extraction, parsing, validation, and repair."""

import json
import re
from typing import Any

from jsonschema import Draft7Validator, ValidationError  # type: ignore[import-untyped]

from app.llm.models import (
    LLMRequest,
    StructuredOutputRequest,
    StructuredOutputResponse,
)
from app.llm.provider import LLMProvider


class StructuredOutputProcessor:
    """Process LLM output into validated JSON with limited repair."""

    def __init__(self, provider: LLMProvider):
        self.provider = provider

    async def process(self, request: StructuredOutputRequest) -> StructuredOutputResponse:
        """Extract, parse, validate, and optionally repair JSON from LLM output."""

        llm_request = LLMRequest(
            prompt=request.prompt,
            system_prompt=request.system_prompt,
            timeout_seconds=request.timeout_seconds,
        )

        # Initial call
        llm_response, metadata = await self.provider.call(llm_request)

        if llm_response.finish_reason in ("error", "timeout"):
            return StructuredOutputResponse(
                status="error",
                raw_content=llm_response.content,
                error_detail=f"LLM call failed: {llm_response.finish_reason}",
                repair_attempts=0,
                call_metadata=metadata,
            )

        # Try to extract and validate
        result = self._extract_and_validate(llm_response.content, request.json_schema)

        if result["success"]:
            return StructuredOutputResponse(
                status="success",
                validated_json=result["json"],
                raw_content=llm_response.content,
                repair_attempts=0,
                call_metadata=metadata,
            )

        # Attempt repair if configured
        repair_attempts = 0
        last_error = result["error"]

        for attempt in range(request.max_repair_attempts):
            repair_attempts += 1

            # Create repair prompt
            repair_prompt = self._create_repair_prompt(
                original_prompt=request.prompt,
                failed_output=llm_response.content,
                validation_error=last_error,
                schema=request.json_schema,
            )

            repair_request = LLMRequest(
                prompt=repair_prompt,
                system_prompt=request.system_prompt,
                timeout_seconds=request.timeout_seconds,
            )

            llm_response, metadata = await self.provider.call(repair_request)

            if llm_response.finish_reason in ("error", "timeout"):
                finish_reason = llm_response.finish_reason
                return StructuredOutputResponse(
                    status="error",
                    raw_content=llm_response.content,
                    error_detail=(f"Repair attempt {attempt + 1} failed: {finish_reason}"),
                    repair_attempts=repair_attempts,
                    call_metadata=metadata,
                )

            result = self._extract_and_validate(llm_response.content, request.json_schema)

            if result["success"]:
                return StructuredOutputResponse(
                    status="success",
                    validated_json=result["json"],
                    raw_content=llm_response.content,
                    repair_attempts=repair_attempts,
                    call_metadata=metadata,
                )

            last_error = result["error"]

        # All attempts exhausted
        return StructuredOutputResponse(
            status="validation_failed",
            raw_content=llm_response.content,
            error_detail=(
                f"Validation failed after {repair_attempts} repair attempts: {last_error}"
            ),
            repair_attempts=repair_attempts,
            call_metadata=metadata,
        )

    def _extract_and_validate(self, content: str, schema: dict[str, Any]) -> dict[str, Any]:
        """Extract JSON from content and validate against schema."""

        # Step 1: Extract JSON
        extracted = self._extract_json(content)
        if extracted is None:
            return {"success": False, "error": "No valid JSON found in output"}

        # Step 2: Validate against schema
        validator = Draft7Validator(schema)
        try:
            validator.validate(extracted)
            return {"success": True, "json": extracted}
        except ValidationError as e:
            return {"success": False, "error": str(e.message)}

    def _extract_json(self, content: str) -> dict[str, Any] | None:
        """Extract JSON from content, handling markdown wrapping."""

        # Try direct parse first
        try:
            return json.loads(content)
        except json.JSONDecodeError:
            pass

        # Try to extract from markdown code blocks
        json_block_pattern = r"```(?:json)?\s*(\{.*?\})\s*```"
        matches = re.findall(json_block_pattern, content, re.DOTALL)

        for match in matches:
            try:
                return json.loads(match)
            except json.JSONDecodeError:
                continue

        # Try to find any JSON object in the text
        brace_pattern = r"\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}"
        matches = re.findall(brace_pattern, content, re.DOTALL)

        for match in matches:
            try:
                return json.loads(match)
            except json.JSONDecodeError:
                continue

        return None

    def _create_repair_prompt(
        self,
        original_prompt: str,
        failed_output: str,
        validation_error: str,
        schema: dict[str, Any],
    ) -> str:
        """Create prompt for repair attempt; does not fabricate fact values."""

        return f"""The previous output failed validation.

Original task: {original_prompt}

Previous output:
{failed_output}

Validation error:
{validation_error}

Required schema:
{json.dumps(schema, indent=2)}

Please output ONLY valid JSON matching the schema. Fix formatting issues only
- do not invent or change factual values. If a required field cannot be
determined from context, use null."""
