"""Fast, dependency-light route tests using a minimal FastAPI stub.

The real ASGI/TestClient contract is covered by ``test_r3_http.py``.  Keep this
file as the offline fallback for environments without fastapi/httpx installed.
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
        os.environ["AGENT_DINING_POI_SOURCE"] = "resources/dining_lodging_pois.json"
        self.module.get_catalog.cache_clear()
        self.module.get_dining_source.cache_clear()

    def tearDown(self) -> None:
        self.module.get_catalog.cache_clear()
        self.module.get_dining_source.cache_clear()

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

    def _dining_input(self) -> dict[str, object]:
        return {
            "date": "2026-11-20",
            "itinerary_draft": {
                "city_id": "beijing",
                "days": [
                    {
                        "date": "2026-11-20",
                        "segments": [
                            {
                                "point_id": "POI-BJ-001",
                                "name": "故宫博物院",
                                "district": "东城区",
                                "latitude": 39.916,
                                "longitude": 116.397,
                                "start_time": "09:00",
                                "end_time": "11:30",
                            },
                            {
                                "point_id": "POI-BJ-002",
                                "name": "景山公园",
                                "district": "东城区",
                                "latitude": 39.928,
                                "longitude": 116.396,
                                "start_time": "14:00",
                                "end_time": "17:30",
                            },
                        ],
                    }
                ],
            },
        }

    def test_dining_lodging_route_returns_sorted_candidates(self) -> None:
        result = self.module.invoke_dining_lodging(
            {"task_id": "task-dining", "input": self._dining_input()}
        )
        self.assertEqual(result["task_id"], "task-dining")
        self.assertEqual(result["status"], "ok")
        self.assertEqual(len(result["meals"]), 2)
        self.assertEqual(len(result["lodging"]), 1)
        self.assertEqual(
            [c["poi_id"] for c in result["meals"][0]["candidates"]],
            ["DINING-BJ-001", "DINING-BJ-002", "DINING-BJ-003"],
        )

    def test_missing_dining_source_returns_source_unavailable(self) -> None:
        os.environ["AGENT_DINING_POI_SOURCE"] = "resources/does-not-exist.json"
        self.module.get_dining_source.cache_clear()
        result = self.module.invoke_dining_lodging({"input": self._dining_input()})
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["error_code"], "SOURCE_UNAVAILABLE")

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

    def _revision_plan(self) -> dict[str, object]:
        return {
            "days": [
                {
                    "date": "2026-11-20",
                    "segments": [
                        {
                            "sequence_no": 1,
                            "point_id": "POI-BJ-001",
                            "name": "故宫博物院",
                            "is_must_visit": True,
                        }
                    ],
                    "reservations": [],
                }
            ],
            "sources": [],
        }

    def test_revision_route_returns_bounded_change(self) -> None:
        result = self.module.invoke_revision(
            {
                "task_id": "task-revision",
                "input": {
                    "base_version_id": "version-1",
                    "current_plan": self._revision_plan(),
                    "revision_request": "删除第一天的故宫",
                },
            }
        )

        self.assertEqual(result["task_id"], "task-revision")
        self.assertEqual(result["intent"], "change")
        self.assertTrue(result["impact_scope"]["needs_confirm"])

    def test_revision_variant_route_enforces_requested_count(self) -> None:
        result = self.module.plan_revision_variants(
            {
                "input": {
                    "current_plan": self._revision_plan(),
                    "plan_count": 2,
                    "shared_source_snapshot_ids": ["11111111-1111-4111-8111-111111111111"],
                }
            }
        )

        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["requested_count"], 2)
        self.assertEqual(len(result["variants"]), 2)


if __name__ == "__main__":
    unittest.main()
