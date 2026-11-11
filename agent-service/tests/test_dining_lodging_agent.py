"""S18 dining/lodging agent tests: legal POIs, detour ordering, safe degradation."""

from __future__ import annotations

import json
import unittest
from pathlib import Path
from typing import Any

from app.agents import dining_lodging
from app.agents.common import new_context
from jsonschema import Draft7Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads(
    (ROOT / "agents/05-dining-lodging/output.schema.json").read_text(encoding="utf-8")
)


class FakeSource:
    def __init__(self, items: dict[str, list[dict[str, Any]]]) -> None:
        self.items = items
        self.timeout = False
        self.calls: list[tuple[str, str]] = []

    def nearby(
        self,
        *,
        city_id: str,
        category: str,
        latitude: float,
        longitude: float,
        radius_m: float,
    ):
        self.calls.append((city_id, category))
        if self.timeout:
            raise TimeoutError("poi search timed out")
        return [dict(item) for item in self.items.get(category, [])]


def _poi(
    poi_id: str,
    *,
    name: str = "",
    category: str = "restaurant",
    city_id: str = "beijing",
    detour_min: float = 5,
    price: dict[str, Any] | None | object = ...,
    valid_until: str | None = "2027-11-11T00:00:00Z",
) -> dict[str, Any]:
    if price is ...:
        price = {"kind": "exact", "currency": "CNY", "amount_minor": 6800}
    return {
        "poi_id": poi_id,
        "name": name or f"示例-{poi_id}",
        "city_id": city_id,
        "category": category,
        "latitude": 39.916,
        "longitude": 116.402,
        "price": price,
        "retrieved_at": "2026-11-11T09:00:00Z",
        "valid_until": valid_until,
        "freshness_status": "fresh",
        "source_record_id": f"SRC-{poi_id}",
        "detour_min": detour_min,
    }


def _inputs() -> dict[str, Any]:
    return {
        "date": "2026-11-20",
        "taste_preference": ["清淡"],
        "companion": "情侣",
        "budget": {"currency": "CNY"},
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


def _run(items: dict[str, list[dict[str, Any]]], **kwargs: Any) -> dict[str, Any]:
    source = FakeSource(items)
    for key, value in kwargs.items():
        setattr(source, key, value)
    return dining_lodging.run(_inputs(), new_context(), poi_source=source)


class DiningLodgingAgentTests(unittest.TestCase):
    def test_normal_returns_three_per_meal_and_lodging_sorted_by_detour(self) -> None:
        result = _run(
            {
                "restaurant": [
                    _poi("DINING-3", detour_min=12),
                    _poi("DINING-1", detour_min=4),
                    _poi("DINING-2", detour_min=8),
                ],
                "lodging": [
                    _poi("LODGING-2", category="lodging", detour_min=10),
                    _poi("LODGING-1", category="lodging", detour_min=5),
                    _poi("LODGING-3", category="lodging", detour_min=20),
                ],
            }
        )
        self.assertEqual(result["status"], "ok")
        self.assertEqual(len(result["meals"]), 2, "lunch and dinner")
        self.assertEqual(
            [item["anchor_point_id"] for item in result["meals"]], ["POI-BJ-001", "POI-BJ-002"]
        )
        expected_dining = ["DINING-1", "DINING-2", "DINING-3"]
        for meal in result["meals"]:
            self.assertEqual([c["poi_id"] for c in meal["candidates"]], expected_dining)
        expected_lodging = ["LODGING-1", "LODGING-2", "LODGING-3"]
        self.assertEqual(
            [c["poi_id"] for c in result["lodging"][0]["candidates"]],
            expected_lodging,
        )
        self.assertEqual(result["lodging"][0]["area"], "东城区")
        Draft7Validator(SCHEMA).validate(result)

    def test_empty_poi_returns_empty_candidates_and_degraded(self) -> None:
        result = _run({"restaurant": [], "lodging": []})
        self.assertEqual(result["status"], "degraded")
        self.assertIn("附近暂无推荐", result["message"])
        self.assertTrue(all(meal["candidates"] == [] for meal in result["meals"]))
        self.assertEqual(result["lodging"][0]["candidates"], [])
        self.assertTrue(result["degraded_fields"])
        self.assertEqual(result["source_metadata"], [])

    def test_expired_poi_is_excluded(self) -> None:
        result = _run(
            {
                "restaurant": [
                    _poi("DINING-OLD", valid_until="2026-01-01T00:00:00Z"),
                    _poi("DINING-1", detour_min=4),
                    _poi("DINING-2", detour_min=8),
                ],
                "lodging": [],
            }
        )
        ids = {c["poi_id"] for meal in result["meals"] for c in meal["candidates"]}
        self.assertNotIn("DINING-OLD", ids)
        self.assertEqual(ids, {"DINING-1", "DINING-2"})
        self.assertIn(result["status"], {"partial", "degraded"})

    def test_same_name_pois_are_not_merged_or_guessed(self) -> None:
        result = _run(
            {
                "restaurant": [
                    _poi("DINING-A", name="同名餐厅", detour_min=4),
                    _poi("DINING-B", name="同名餐厅", detour_min=6),
                    _poi("DINING-C", name="另一家", detour_min=8),
                ],
                "lodging": [],
            }
        )
        names = [c["name"] for meal in result["meals"] for c in meal["candidates"]]
        self.assertNotIn("同名餐厅", names)
        self.assertEqual(names, ["另一家", "另一家"])
        self.assertIn(result["status"], {"partial", "degraded"})

    def test_cross_city_poi_is_excluded(self) -> None:
        result = _run(
            {
                "restaurant": [
                    _poi("DINING-SH", city_id="shanghai", detour_min=4),
                    _poi("DINING-1", detour_min=8),
                ],
                "lodging": [],
            }
        )
        ids = {c["poi_id"] for meal in result["meals"] for c in meal["candidates"]}
        self.assertEqual(ids, {"DINING-1"})

    def test_detour_over_threshold_is_excluded(self) -> None:
        result = _run(
            {
                "restaurant": [
                    _poi("DINING-FAR", detour_min=41),
                    _poi("DINING-1", detour_min=4),
                    _poi("DINING-2", detour_min=8),
                ],
                "lodging": [],
            }
        )
        ids = {c["poi_id"] for meal in result["meals"] for c in meal["candidates"]}
        self.assertNotIn("DINING-FAR", ids)
        self.assertIn(result["status"], {"partial", "degraded"})

    def test_timeout_degrades_without_failing_whole_agent(self) -> None:
        result = _run({}, timeout=True)
        self.assertEqual(result["status"], "degraded")
        self.assertNotEqual(result["status"], "failed")
        self.assertIn("超时", result["message"])
        self.assertTrue(result["degraded_fields"])

    def test_unknown_price_has_no_numeric_value(self) -> None:
        result = _run(
            {
                "restaurant": [
                    _poi("DINING-1", price=None, detour_min=4),
                    _poi("DINING-2", price={"kind": "unknown"}, detour_min=8),
                ],
                "lodging": [],
            }
        )
        for meal in result["meals"]:
            for candidate in meal["candidates"]:
                self.assertEqual(candidate["price"]["kind"], "unknown")
                self.assertIsNone(candidate["price"]["currency"])
                self.assertIsNone(candidate["price"]["amount_minor"])
                self.assertIsNone(candidate["price"]["min_minor"])
                self.assertIsNone(candidate["price"]["max_minor"])

    def test_sensitive_preferences_are_not_echoed(self) -> None:
        result = _run(
            {
                "restaurant": [_poi("DINING-1"), _poi("DINING-2", detour_min=6)],
                "lodging": [],
            }
        )
        text = json.dumps(result, ensure_ascii=False)
        self.assertNotIn("清淡", text)
        self.assertNotIn("情侣", text)

    def test_missing_itinerary_is_failed(self) -> None:
        result = dining_lodging.run(
            {"date": "2026-11-20"},
            new_context(),
            poi_source=FakeSource({}),
        )
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["error_code"], "INVALID_INPUT")


if __name__ == "__main__":
    unittest.main()
