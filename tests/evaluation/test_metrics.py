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

    def test_rejects_non_object_run_and_invalid_identifiers(self):
        with self.assertRaisesRegex(EvaluationInputError, "run record must be an object"):
            calculate_metrics(["not-an-object"])

        for field, value in (("run_id", ""), ("case_id", 1)):
            with self.subTest(field=field, value=value):
                run = copy.deepcopy(BASE_RUN)
                run[field] = value
                with self.assertRaises(EvaluationInputError):
                    calculate_metrics([run])

    def test_rejects_non_boolean_metric_states(self):
        invalid_values = (
            (("hard_constraints", 0, "satisfied"), "false"),
            (("route", "feasible"), "true"),
            (("schema_valid",), "false"),
            (("degradation_explained",), 1),
            (("facts", 0, "is_estimated"), 0),
        )
        for path, value in invalid_values:
            with self.subTest(path=path, value=value):
                run = copy.deepcopy(BASE_RUN)
                target = run
                for key in path[:-1]:
                    target = target[key]
                target[path[-1]] = value
                with self.assertRaises(EvaluationInputError):
                    calculate_metrics([run])

    def test_rejects_missing_route_feasibility(self):
        run = copy.deepcopy(BASE_RUN)
        run["route"] = {}
        with self.assertRaisesRegex(EvaluationInputError, "route.feasible"):
            calculate_metrics([run])

    def test_rejects_non_finite_or_non_numeric_measurements(self):
        invalid_values = (
            (("latency_ms",), float("nan")),
            (("latency_ms",), float("inf")),
            (("cost", "api_cost_minor"), "3"),
            (("cost", "api_cost_minor"), float("nan")),
            (("cost", "api_cost_minor"), float("inf")),
            (("cost", "token_count"), True),
        )
        for path, value in invalid_values:
            with self.subTest(path=path, value=value):
                run = copy.deepcopy(BASE_RUN)
                target = run
                for key in path[:-1]:
                    target = target[key]
                target[path[-1]] = value
                with self.assertRaises(EvaluationInputError):
                    calculate_metrics([run])

    def test_rejects_malformed_fact_and_constraint_objects(self):
        malformed_records = (
            ("facts", ["not-an-object"]),
            ("facts", [{"source_record_id": "src-1"}]),
            ("hard_constraints", ["not-an-object"]),
            ("hard_constraints", [{"id": "c1"}]),
        )
        for field, value in malformed_records:
            with self.subTest(field=field, value=value):
                run = copy.deepcopy(BASE_RUN)
                run[field] = value
                with self.assertRaises(EvaluationInputError):
                    calculate_metrics([run])

    def test_rejects_malformed_local_modification(self):
        invalid_values = (
            "not-an-object",
            {"unchanged_blocks": "not-an-array"},
            {"unchanged_blocks": ["not-an-object"]},
            {"unchanged_blocks": [{"before_hash": "a"}]},
        )
        for value in invalid_values:
            with self.subTest(value=value):
                run = copy.deepcopy(BASE_RUN)
                run["local_modification"] = value
                with self.assertRaises(EvaluationInputError):
                    calculate_metrics([run])

    def test_load_runs_requires_array(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runs.json"
            path.write_text(json.dumps(BASE_RUN), encoding="utf-8")
            with self.assertRaisesRegex(EvaluationInputError, "JSON array"):
                load_runs(path)


if __name__ == "__main__":
    unittest.main()
