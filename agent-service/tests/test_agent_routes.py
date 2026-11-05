"""Route-level tests without requiring FastAPI/httpx at runtime.

The repository's HTTP layer is still exercised through a minimal FastAPI stub:
the route functions, catalog wiring and failure envelope are real, while the
ASGI server itself remains covered by the HTTP PENDING item in S16/S17.
"""

from __future__ import annotations

import os
import sys
import types
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def _install_fastapi_stub() -> None:
    if "fastapi" in sys.modules:
        return
    fastapi = types.ModuleType("fastapi")

    class APIRouter:
        def __init__(self, prefix: str = "", tags: list[str] | None = None) -> None:
            self.prefix = prefix
            self.tags = tags or []
            self.routes: list[tuple[str, str, object]] = []

        def post(self, path: str):
            def decorator(function):
                self.routes.append(("POST", self.prefix + path, function))
                return function

            return decorator

        def get(self, path: str):
            def decorator(function):
                self.routes.append(("GET", self.prefix + path, function))
                return function

            return decorator

    def Body(*args, **kwargs):
        return None

    fastapi.APIRouter = APIRouter
    fastapi.Body = Body
    sys.modules["fastapi"] = fastapi


class AgentRouteTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        _install_fastapi_stub()
        from app.api import agents as agents_module

        cls.module = agents_module

    def setUp(self) -> None:
        os.environ["AGENT_KNOWLEDGE_CATALOG"] = "resources/published_catalog.json"
        self.module.get_catalog.cache_clear()

    def tearDown(self) -> None:
        self.module.get_catalog.cache_clear()

    def _point(self) -> dict[str, object]:
        return {
            "point_id": "POI-BJ-001",
            "name": "故宫博物院",
            "latitude": 39.9,
            "longitude": 116.4,
        }

    def test_attractions_route_returns_cited_envelope(self) -> None:
        body = {"task_id": "task-route", "input": {"points": [self._point()]}}
        result = self.module.invoke_attractions(body)
        self.assertEqual(result["task_id"], "task-route")
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["cards"][0]["knowledge_id"], "KN-BJ-001")

    def test_reservation_route_returns_timeline(self) -> None:
        body = {"input": {"points": [self._point()], "date": "2026-12-10"}}
        result = self.module.invoke_reservation_rules(body)
        self.assertEqual(result["status"], "ok")
        self.assertEqual(len(result["timeline"]), 1)

    def test_catalog_status_hides_fact_text(self) -> None:
        result = self.module.catalog_status()
        self.assertEqual(result["catalog_version"], "r3-catalog-v1")
        self.assertGreaterEqual(result["poi_count"], 3)
        self.assertNotIn("entries", result)

    def test_missing_catalog_returns_source_unavailable(self) -> None:
        os.environ["AGENT_KNOWLEDGE_CATALOG"] = "resources/does-not-exist.json"
        self.module.get_catalog.cache_clear()
        result = self.module.invoke_attractions({"input": {"points": [self._point()]}})
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["error_code"], "SOURCE_UNAVAILABLE")

    def test_invalid_input_is_failed_not_500(self) -> None:
        result = self.module.invoke_attractions({"input": {"points": []}})
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["error_code"], "INVALID_INPUT")


if __name__ == "__main__":
    unittest.main()
