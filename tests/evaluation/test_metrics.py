import copy
import json
import tempfile
import unittest
from pathlib import Path

from metrics import EvaluationInputError, calculate_metrics, load_runs


BASE_RUN = {
    "run_id": "run-1",
    "case_id": "BJ-01",
    "hard_constraints": [{"id": "c1", "satisfied": True}],
    "facts": [{
        "source_record_id": "src-1",
        "freshness_status": "fresh",
        "is_estimated": False,
        "displayed_status": "fresh",
    }],
    "route": {"feasible": True},
    "schema_valid": True,
    "latency_ms": 100,
    "cost": {"token_count": 10, "api_cost_minor": 2},
    "degraded_fields": [],
    "degradation_explained": True,
}


class MetricsTest(unittest.TestCase):
    def test_calculates_success_and_local_preservation(self):
        run = copy.deepcopy(BASE_RUN)
        run["local_modification"] = {
            "unchanged_blocks": [
                {"before_hash": "a", "after_hash": "a"},
                {"before_hash": "b", "after_hash": "changed"},
            ]
        }
        result = calculate_metrics([run])
        self.assertEqual(result["hard_constraints"]["satisfaction_rate"], 1.0)
        self.assertEqual(result["local_modification_preservation_rate"], 0.5)
        self.assertEqual(result["cost"]["token_total"], 10)

    def test_rejects_empty_runs(self):
        with self.assertRaisesRegex(EvaluationInputError, "must not be empty"):
            calculate_metrics([])

    def test_rejects_missing_required_field(self):
        run = copy.deepcopy(BASE_RUN)
        del run["route"]
        with self.assertRaisesRegex(EvaluationInputError, "missing"):
            calculate_metrics([run])

    def test_rejects_duplicate_run_ids(self):
        first = copy.deepcopy(BASE_RUN)
        second = copy.deepcopy(BASE_RUN)
        second["case_id"] = "BJ-02"
        with self.assertRaisesRegex(EvaluationInputError, "duplicate run_id"):
            calculate_metrics([first, second])

    def test_stale_fact_must_be_displayed_as_stale(self):
        run = copy.deepcopy(BASE_RUN)
        run["facts"][0]["freshness_status"] = "stale"
        run["facts"][0]["displayed_status"] = "fresh"
        result = calculate_metrics([run])
        self.assertEqual(result["stale_display_rate"], 0.0)

    def test_degraded_run_requires_explanation(self):
        run = copy.deepcopy(BASE_RUN)
        run["degraded_fields"] = ["weather"]
        run["degradation_explained"] = False
        result = calculate_metrics([run])
        self.assertEqual(result["degradation_visibility_rate"], 0.0)

    def test_rejects_negative_cost_and_latency(self):
        negative_latency = copy.deepcopy(BASE_RUN)
        negative_latency["latency_ms"] = -1
        with self.assertRaises(EvaluationInputError):
            calculate_metrics([negative_latency])

        negative_cost = copy.deepcopy(BASE_RUN)
        negative_cost["cost"]["api_cost_minor"] = -1
        with self.assertRaises(EvaluationInputError):
            calculate_metrics([negative_cost])

    def test_load_runs_requires_array(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runs.json"
            path.write_text(json.dumps(BASE_RUN), encoding="utf-8")
            with self.assertRaisesRegex(EvaluationInputError, "JSON array"):
                load_runs(path)


if __name__ == "__main__":
    unittest.main()
