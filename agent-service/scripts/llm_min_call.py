"""Run one approved, bounded real-model smoke call without logging secrets."""

import asyncio
import json
import sys
from datetime import UTC, datetime
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.core.config import get_llm_settings  # noqa: E402
from app.llm.factory import create_llm_provider  # noqa: E402
from app.llm.models import LLMRequest  # noqa: E402
from app.llm.openai_provider import LLMProviderError  # noqa: E402

MAX_OUTPUT_TOKENS = 16
PROMPT = "请只回复 OK"
MARKER_PATH = Path(__file__).resolve().parents[1] / ".llm_min_call.used"


def emit(payload: dict[str, object]) -> None:
    """Print only sanitized call metadata."""
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True))


def reserve_one_shot() -> bool:
    """Atomically reserve the local one-shot budget before network access."""
    try:
        with MARKER_PATH.open("x", encoding="utf-8") as marker:
            marker.write(f"reserved_at={datetime.now(UTC).isoformat()}\n")
        return True
    except FileExistsError:
        emit({"status": "blocked", "reason": "one-shot budget already consumed"})
        return False
    except OSError:
        emit({"status": "blocked", "reason": "cannot create one-shot budget marker"})
        return False


async def run_once() -> int:
    settings = get_llm_settings()

    if not settings.llm_min_call_approved:
        emit({"status": "blocked", "reason": "LLM_MIN_CALL_APPROVED is not true"})
        return 2
    if settings.llm_provider != "openai-compatible":
        emit({"status": "blocked", "reason": "LLM_PROVIDER must be openai-compatible"})
        return 2
    if settings.llm_max_retries != 0:
        emit({"status": "blocked", "reason": "LLM_MAX_RETRIES must be 0 for one-shot call"})
        return 2

    try:
        provider = create_llm_provider(settings)
    except ValueError:
        emit(
            {
                "provider": "openai-compatible",
                "status": "blocked",
                "reason": "invalid LLM configuration",
            }
        )
        return 2
    if not reserve_one_shot():
        return 2

    request = LLMRequest(
        prompt=PROMPT,
        max_tokens=MAX_OUTPUT_TOKENS,
        temperature=0,
        timeout_seconds=min(settings.llm_timeout_seconds, 30),
    )
    try:
        _, metadata = await provider.call(request)
    except LLMProviderError as exc:
        emit(
            {
                "provider": "openai-compatible",
                "status": "error",
                "error_code": exc.code,
            }
        )
        return 1

    emit(
        {
            "provider": metadata.provider,
            "model": metadata.model_alias,
            "status": metadata.status,
            "request_timestamp": metadata.request_timestamp.isoformat(),
            "response_timestamp": (
                metadata.response_timestamp.isoformat()
                if metadata.response_timestamp is not None
                else None
            ),
            "prompt_tokens": metadata.prompt_tokens,
            "completion_tokens": metadata.completion_tokens,
            "total_tokens": metadata.total_tokens,
            "latency_ms": metadata.latency_ms,
            "cost_estimate": metadata.cost_estimate,
        }
    )
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(run_once()))
