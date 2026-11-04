"""S17 reservation rule, stale and reminder timeline tests."""

from __future__ import annotations

import unittest
from datetime import datetime, timezone
from pathlib import Path

from app.agents import reservation_rules
from app.knowledge.catalog import PublishedCatalog

CATALOG_PATH = Path(__file__).resolve().parents[1] / "resources" / "published_catalog.json"
MOMENT = datetime(2026, 10, 29, tzinfo=timezone.utc)
CONTEXT = {
    "schema_version": "agent-contract-v1",
    "task_id": "task-test",
    "trace_id": "trace-test",
    "request_id": "req-test",
    "generated_at": "2026-10-29T00:00:00Z",
}


def _point(point_id: str, name: str) -> dict[str, object]:
    return {"point_id": point_id, "name": name, "latitude": 39.9, "longitude": 116.4}


class ReservationAgentTests(unittest.TestCase):
    def setUp(self) -> None:
        self.catalog = PublishedCatalog.load(CATALOG_PATH)

    def _run(self, inputs: dict[str, object], moment: datetime = MOMENT) -> dict[str, object]:
        return reservation_rules.run(inputs, self.catalog, CONTEXT, moment=moment)

    def test_ok_rule_builds_reminder(self) -> None:
        result = self._run({"points": [_point("POI-BJ-001", "故宫博物院")], "date": "2026-11-10"})
        self.assertEqual(result["status"], "ok")
        reservation = result["reservations"][0]
        self.assertEqual(reservation["policy_id"], "POLICY-BJ-001-V2")
        self.assertEqual(reservation["currency"], "CNY")
        self.assertEqual(reservation["amount_minor"], 6000)
        self.assertFalse(reservation["stale"])
        self.assertEqual(len(result["timeline"]), 1)
        self.assertEqual(result["timeline"][0]["release_at"], "2026-11-03T20:00:00+08:00")
        self.assertEqual(result["timeline"][0]["status"], "pending")

    def test_cross_month_release_date(self) -> None:
        result = self._run({"points": [_point("POI-BJ-001", "故宫博物院")], "date": "2026-11-05"})
        self.assertEqual(result["timeline"][0]["release_at"], "2026-10-29T20:00:00+08:00")

    def test_missed_release_is_not_a_reminder(self) -> None:
        result = self._run(
            {"points": [_point("POI-BJ-001", "故宫博物院")], "date": "2026-11-03"},
            moment=datetime(2026, 10, 28, tzinfo=timezone.utc),
        )
        self.assertEqual(result["status"], "partial")
        self.assertEqual(result["timeline"], [])
        self.assertIn("timeline[0]", result["degraded_fields"])

    def test_stale_rule_is_visible(self) -> None:
        result = self._run({"points": [_point("POI-BJ-002", "中国国家博物馆")], "date": "2026-11-10"})
        self.assertEqual(result["status"], "partial")
        self.assertTrue(result["reservations"][0]["stale"])
        self.assertIn("reservations[0].stale", result["degraded_fields"])
        self.assertNotIn("amount_minor", result["reservations"][0])

    def test_missing_rule_returns_empty_with_message(self) -> None:
        result = self._run({"points": [_point("POI-BJ-003", "天坛公园")], "date": "2026-11-10"})
        self.assertEqual(result["status"], "degraded")
        self.assertEqual(result["reservations"], [])
        self.assertEqual(result["timeline"], [])
        self.assertIn("暂未收录", result["message"])

    def test_duplicate_points_deduplicate_timeline(self) -> None:
        point = _point("POI-BJ-001", "故宫博物院")
        result = self._run({"points": [point, point], "date": "2026-11-10"})
        self.assertEqual(len(result["timeline"]), 1)
        self.assertTrue(any("dedupe_key" in field for field in result["degraded_fields"]))

    def test_invalid_date_is_failed(self) -> None:
        result = self._run({"points": [_point("POI-BJ-001", "故宫博物院")], "date": "11/10/2026"})
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["error_code"], "INVALID_INPUT")

    def test_same_name_conflict_does_not_guess_policy(self) -> None:
        payload = {
            "catalog_version": "test",
            "pois": [
                {"poi_id": "P1", "city_id": "beijing", "name": "同名景点", "aliases": []},
                {"poi_id": "P2", "city_id": "beijing", "name": "同名景点", "aliases": []},
            ],
            "entries": [],
            "rules": [
                {
                    "policy_id": "POL-1",
                    "poi_id": "P1",
                    "city_id": "beijing",
                    "status": "PUBLISHED",
                    "effective_from": "2026-01-01",
                    "last_verified_at": "2026-10-01T00:00:00+08:00",
                    "advance_days": 1,
                    "release_time": "09:00",
                    "release_timezone": "Asia/Shanghai",
                }
            ],
        }
        catalog = PublishedCatalog.from_payload(payload)
        result = reservation_rules.run(
            {"points": [_point("unknown", "同名景点")], "date": "2026-11-10"},
            catalog,
            CONTEXT,
            moment=MOMENT,
        )
        self.assertEqual(result["status"], "degraded")
        self.assertEqual(result["reservations"], [])


if __name__ == "__main__":
    unittest.main()
