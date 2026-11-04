"""Tests for the published catalog and retrieval filters."""
from __future__ import annotations

import unittest
from datetime import datetime, timezone
from pathlib import Path

from app.knowledge.catalog import PublishedCatalog

CATALOG_PATH = Path(__file__).resolve().parents[1] / "resources" / "published_catalog.json"
MOMENT = datetime(2026, 10, 29, tzinfo=timezone.utc)


class PublishedCatalogTests(unittest.TestCase):
    def setUp(self) -> None:
        self.catalog = PublishedCatalog.load(CATALOG_PATH)

    def test_alias_matches_internal_poi(self) -> None:
        candidates = self.catalog.find_pois("missing", "紫禁城", "beijing")
        self.assertEqual([poi.poi_id for poi in candidates], ["POI-BJ-001"])

    def test_unknown_poi_returns_empty(self) -> None:
        candidates, entries = self.catalog.knowledge_for(
            point_id="missing", name="不存在", city_id="beijing", query="", moment=MOMENT
        )
        self.assertEqual(candidates, ())
        self.assertEqual(entries, ())

    def test_expired_entry_is_not_readable(self) -> None:
        _, entries = self.catalog.knowledge_for(
            point_id="POI-BJ-001", name="故宫博物院", city_id="beijing", query="", moment=MOMENT
        )
        self.assertEqual([entry.knowledge_id for entry in entries], ["KN-BJ-001"])
        self.assertNotIn("KN-BJ-001-OLD", [entry.knowledge_id for entry in entries])

    def test_unpublished_entry_is_not_readable(self) -> None:
        payload = {
            "catalog_version": "test",
            "pois": [{"poi_id": "P1", "city_id": "beijing", "name": "A", "aliases": []}],
            "entries": [
                {
                    "knowledge_id": "K-DRAFT",
                    "poi_id": "P1",
                    "city_id": "beijing",
                    "status": "DRAFT",
                    "source": {"source_record_id": "S1"},
                    "chunks": [{"chunk_id": "C1", "text": "draft"}],
                }
            ],
            "rules": [],
        }
        catalog = PublishedCatalog.from_payload(payload)
        _, entries = catalog.knowledge_for(
            point_id="P1", name="A", city_id="beijing", query="", moment=MOMENT
        )
        self.assertEqual(entries, ())

    def test_same_name_conflict_returns_all_candidates(self) -> None:
        payload = {
            "catalog_version": "test",
            "pois": [
                {"poi_id": "P1", "city_id": "beijing", "name": "同名景点", "aliases": []},
                {"poi_id": "P2", "city_id": "beijing", "name": "同名景点", "aliases": []},
            ],
            "entries": [],
            "rules": [],
        }
        catalog = PublishedCatalog.from_payload(payload)
        candidates, entries = catalog.knowledge_for(
            point_id="unknown", name="同名景点", city_id="beijing", query="", moment=MOMENT
        )
        self.assertEqual(len(candidates), 2)
        self.assertEqual(entries, ())

    def test_rule_effective_window_and_stale(self) -> None:
        from datetime import date

        _, rules = self.catalog.rules_for(
            point_id="POI-BJ-001",
            name="故宫博物院",
            city_id="beijing",
            day=date(2026, 11, 10),
            moment=MOMENT,
        )
        self.assertEqual(len(rules), 1)
        self.assertFalse(rules[0].is_stale(MOMENT))
        _, stale_rules = self.catalog.rules_for(
            point_id="POI-BJ-002",
            name="中国国家博物馆",
            city_id="beijing",
            day=date(2026, 11, 10),
            moment=MOMENT,
        )
        self.assertTrue(stale_rules[0].is_stale(MOMENT))


if __name__ == "__main__":
    unittest.main()
