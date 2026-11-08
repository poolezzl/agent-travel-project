"""S16 agent contract, citation and degradation tests."""

from __future__ import annotations

import unittest
from datetime import datetime, timezone
from pathlib import Path

from app.agents import attractions
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


class AttractionsAgentTests(unittest.TestCase):
    def setUp(self) -> None:
        self.catalog = PublishedCatalog.load(CATALOG_PATH)

    def _run(self, inputs: dict[str, object]) -> dict[str, object]:
        return attractions.run(inputs, self.catalog, CONTEXT, moment=MOMENT)

    def test_ok_card_has_citations_and_content(self) -> None:
        result = self._run({"points": [_point("POI-BJ-001", "故宫博物院")]})
        self.assertEqual(result["status"], "ok")
        card = result["cards"][0]
        self.assertEqual(card["source"], "knowledge_base")
        self.assertEqual(card["point_id"], "POI-BJ-001")
        self.assertEqual(card["knowledge_id"], "KN-BJ-001")
        self.assertIn("CHUNK-BJ-001-01", card["chunk_ids"])
        self.assertTrue(card["highlights"])
        self.assertTrue(card["facts"])
        self.assertEqual(card["freshness_status"], "fresh")
        self.assertTrue(result["source_metadata"])

    def test_expired_entry_never_leaks(self) -> None:
        result = self._run({"points": [_point("POI-BJ-001", "故宫博物院")]})
        self.assertNotIn("CHUNK-BJ-001-OLD-01", result["cards"][0]["chunk_ids"])

    def test_alias_resolves_same_poi(self) -> None:
        result = self._run({"points": [_point("unknown", "紫禁城")], "city_id": "beijing"})
        self.assertEqual(result["cards"][0]["knowledge_id"], "KN-BJ-001")

    def test_unknown_point_returns_unknown_card(self) -> None:
        result = self._run({"points": [_point("POI-BJ-003", "天坛公园")]})
        self.assertEqual(result["status"], "degraded")
        self.assertEqual(result["cards"][0], {"point_id": "POI-BJ-003", "source": "unknown"})
        self.assertTrue(result["degraded_fields"])

    def test_all_unknown_is_degraded(self) -> None:
        result = self._run(
            {
                "points": [
                    _point("POI-BJ-003", "天坛公园"),
                    _point("missing", "不存在"),
                ]
            }
        )
        self.assertEqual(result["status"], "degraded")
        self.assertEqual(len(result["cards"]), 2)
        self.assertTrue(all(card["source"] == "unknown" for card in result["cards"]))

    def test_invalid_input_is_failed_without_facts(self) -> None:
        result = self._run({"points": []})
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["error_code"], "INVALID_INPUT")
        self.assertNotIn("cards", result)

    def test_preference_tags_only_reorder(self) -> None:
        result = self._run(
            {
                "points": [_point("POI-BJ-001", "故宫博物院")],
                "preference_tags": ["避坑"],
            }
        )
        self.assertEqual(result["cards"][0]["knowledge_id"], "KN-BJ-001")
        self.assertTrue(result["cards"][0]["avoid_tips"])

    def test_city_mismatch_never_leaks_known_poi(self) -> None:
        result = self._run(
            {
                "points": [_point("POI-BJ-001", "故宫博物院")],
                "city_id": "shanghai",
            }
        )
        self.assertEqual(result["status"], "degraded")
        self.assertEqual(result["cards"][0], {"point_id": "POI-BJ-001", "source": "unknown"})

    def test_every_fact_has_chunk_and_source_citation(self) -> None:
        result = self._run({"points": [_point("POI-BJ-001", "故宫博物院")]})
        card = result["cards"][0]
        self.assertEqual(
            {fact["chunk_id"] for fact in card["facts"]},
            set(card["chunk_ids"]),
        )
        source_ids = {source["source_record_id"] for source in result["source_metadata"]}
        for fact in card["facts"]:
            self.assertTrue(fact["source_record_id"])
            self.assertIn(fact["source_record_id"], source_ids)
        self.assertTrue(card["retrieved_at"])

    def test_query_uses_rag_and_keeps_only_matching_chunks(self) -> None:
        result = self._run(
            {
                "points": [_point("POI-BJ-001", "故宫博物院")],
                "query": "预留",
            }
        )
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["cards"][0]["chunk_ids"], ["CHUNK-BJ-001-02"])
        self.assertEqual(
            [fact["chunk_id"] for fact in result["cards"][0]["facts"]],
            ["CHUNK-BJ-001-02"],
        )

    def test_low_confidence_query_returns_unknown_card(self) -> None:
        result = self._run(
            {
                "points": [_point("POI-BJ-001", "故宫博物院")],
                "query": "量子物理",
            }
        )
        self.assertEqual(result["status"], "degraded")
        self.assertEqual(result["cards"][0], {"point_id": "POI-BJ-001", "source": "unknown"})
        self.assertIn("cards[0].source", result["degraded_fields"])


if __name__ == "__main__":
    unittest.main()
