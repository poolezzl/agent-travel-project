import copy
import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from validate_fixtures import validate_cases, validate_data


ROOT = Path(__file__).parents[2]
EVALUATION_ROOT = ROOT / "tests" / "fixtures" / "evaluation"
DATA_ROOT = ROOT / "tests" / "fixtures" / "data"
REFERENCE = datetime(2026, 10, 1, tzinfo=timezone.utc)


class FixtureValidationTest(unittest.TestCase):
    def test_existing_fixtures_pass(self):
        self.assertEqual(validate_cases(EVALUATION_ROOT), [])
        self.assertEqual(validate_data(DATA_ROOT, REFERENCE), [])

    def test_rejects_malformed_case_without_traceback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "cases").mkdir()
            malformed = {"case_id": "broken"}
            (root / "cases" / "broken.json").write_text(
                json.dumps(malformed), encoding="utf-8"
            )
            errors = validate_cases(root)
        self.assertTrue(any("missing" in error for error in errors))
        self.assertTrue(any("need at least 30" in error for error in errors))

    def test_rejects_naive_reference_time(self):
        errors = validate_data(DATA_ROOT, datetime(2026, 10, 1))
        self.assertIn("reference time must be a timezone-aware datetime", errors)

    def test_rejects_malformed_source_timestamp(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sources").mkdir()
            source = json.loads(
                (DATA_ROOT / "sources" / "src-synthetic-poi-001.json").read_text(
                    encoding="utf-8"
                )
            )
            source["retrieved_at"] = "not-a-timestamp"
            (root / "sources" / "source.json").write_text(
                json.dumps(source), encoding="utf-8"
            )
            (root / "beijing").mkdir()
            errors = validate_data(root, REFERENCE)
        self.assertTrue(any("retrieved_at must be a valid ISO datetime" in error for error in errors))

    def test_rejects_incomplete_published_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sources").mkdir()
            source = json.loads(
                (DATA_ROOT / "sources" / "src-synthetic-poi-001.json").read_text(
                    encoding="utf-8"
                )
            )
            del source["content_hash"]
            (root / "sources" / "source.json").write_text(
                json.dumps(source), encoding="utf-8"
            )
            (root / "beijing").mkdir()
            errors = validate_data(root, REFERENCE)
        self.assertTrue(any("missing" in error and "content_hash" in error for error in errors))
        self.assertTrue(any("published item" not in error for error in errors))

    def test_rejects_pending_review_published_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sources").mkdir()
            source = json.loads(
                (DATA_ROOT / "sources" / "src-synthetic-poi-001.json").read_text(
                    encoding="utf-8"
                )
            )
            source["reviewed_by"] = "synthetic-review-pending"
            (root / "sources" / "source.json").write_text(
                json.dumps(source), encoding="utf-8"
            )
            (root / "beijing").mkdir()
            errors = validate_data(root, REFERENCE)
        self.assertTrue(any("review is pending" in error for error in errors))

    def test_rejects_cross_entity_source_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sources").mkdir()
            source = json.loads(
                (DATA_ROOT / "sources" / "src-synthetic-poi-001.json").read_text(
                    encoding="utf-8"
                )
            )
            (root / "sources" / "source.json").write_text(
                json.dumps(source), encoding="utf-8"
            )
            (root / "beijing").mkdir()
            item = json.loads(
                (DATA_ROOT / "beijing" / "poi-draft.json").read_text(
                    encoding="utf-8"
                )
            )
            item["city_code"] = "CN-SH"
            (root / "beijing" / "item.json").write_text(
                json.dumps(item), encoding="utf-8"
            )
            errors = validate_data(root, REFERENCE)
        self.assertTrue(any("Beijing item" in error for error in errors))
        self.assertTrue(any("city_code does not match source" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
