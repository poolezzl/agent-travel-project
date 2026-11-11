"""HTTP client for the Java S15 internal knowledge endpoints.

The Java backend remains the source of truth.  This client only calls the two
read-only internal endpoints and maps transport/timeout/shape failures to
stable error codes for the Python agent boundary.
"""

from __future__ import annotations

from datetime import date
from typing import Any, Iterable

import httpx

DEFAULT_BASE_URL = "http://127.0.0.1:8080"
DEFAULT_TIMEOUT_SECONDS = 3.0

ERROR_TIMEOUT = "TIMEOUT"
ERROR_UNAVAILABLE = "SOURCE_UNAVAILABLE"
ERROR_HTTP = "HTTP_ERROR"
ERROR_INVALID_RESPONSE = "INVALID_RESPONSE"


class InternalKnowledgeError(RuntimeError):
    """Stable failure from the internal knowledge boundary."""

    def __init__(self, code: str, message: str, *, status_code: int | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.status_code = status_code


class InternalKnowledgeClient:
    """Read-only client for `/internal/v1/knowledge/*`."""

    def __init__(
        self,
        base_url: str = DEFAULT_BASE_URL,
        *,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        client: httpx.Client | None = None,
    ) -> None:
        self._owns_client = client is None
        self._client = client or httpx.Client(base_url=base_url, timeout=timeout)

    def close(self) -> None:
        if self._owns_client:
            self._client.close()

    def __enter__(self) -> "InternalKnowledgeClient":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def query_attractions(
        self,
        *,
        city: str,
        points: Iterable[dict[str, Any]],
        preference_tags: Iterable[str] | None = None,
        limit: int = 10,
    ) -> dict[str, Any]:
        payload = {
            "city": city,
            "points": [_point(point) for point in points],
            "preference_tags": list(preference_tags or []),
            "limit": limit,
        }
        body = self._post("/internal/v1/knowledge/attractions/query", payload)
        cards = body.get("cards")
        if not isinstance(cards, list):
            raise InternalKnowledgeError(ERROR_INVALID_RESPONSE, "attractions 响应缺少 cards 数组")
        return body

    def query_reservations(
        self,
        *,
        city: str,
        points: Iterable[dict[str, Any]],
        day: date,
        limit: int = 10,
    ) -> dict[str, Any]:
        payload = {
            "city": city,
            "points": [_point(point) for point in points],
            "date": day.isoformat(),
            "limit": limit,
        }
        body = self._post("/internal/v1/knowledge/reservations/query", payload)
        reservations = body.get("reservations")
        if not isinstance(reservations, list):
            raise InternalKnowledgeError(ERROR_INVALID_RESPONSE, "reservations 响应缺少 reservations 数组")
        return body

    def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        try:
            response = self._client.post(path, json=payload)
        except httpx.TimeoutException as exc:
            raise InternalKnowledgeError(ERROR_TIMEOUT, "内部知识接口超时") from exc
        except httpx.HTTPError as exc:
            raise InternalKnowledgeError(ERROR_UNAVAILABLE, "内部知识接口不可用") from exc
        if response.status_code >= 400:
            raise InternalKnowledgeError(
                ERROR_HTTP,
                f"内部知识接口返回 HTTP {response.status_code}",
                status_code=response.status_code,
            )
        try:
            body = response.json()
        except ValueError as exc:
            raise InternalKnowledgeError(ERROR_INVALID_RESPONSE, "内部知识接口返回非 JSON") from exc
        if not isinstance(body, dict):
            raise InternalKnowledgeError(ERROR_INVALID_RESPONSE, "内部知识接口返回非对象")
        return body


def _point(point: dict[str, Any]) -> dict[str, Any]:
    mapped = {
        "point_id": str(point["point_id"]),
        "name": str(point["name"]),
        "longitude": float(point["longitude"]),
        "latitude": float(point["latitude"]),
    }
    if point.get("poi_id"):
        mapped["poi_id"] = str(point["poi_id"])
    return mapped


__all__ = [
    "DEFAULT_BASE_URL",
    "DEFAULT_TIMEOUT_SECONDS",
    "ERROR_HTTP",
    "ERROR_INVALID_RESPONSE",
    "ERROR_TIMEOUT",
    "ERROR_UNAVAILABLE",
    "InternalKnowledgeClient",
    "InternalKnowledgeError",
]
