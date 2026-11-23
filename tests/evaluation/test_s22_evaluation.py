"""S22 gate tests: the 30 fixed cases must satisfy every M2 threshold.

The tests reuse the redacted artifacts produced by ``s22_gate.py``; they never touch a
network, a supplier or a model.  When a threshold fails the assertion prints the metric
that failed so the evidence file and the test can never silently disagree.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from s22_fixtures import load_cases, load_index  # noqa: E402
from s22_gate import build_report, run_all  # noqa: E402
from s22_runner import PLAN_SCHEMA, needs_local_revision, run_case  # noqa: E402
from jsonschema import Draft7Validator  # noqa: E402


@pytest.fixture(scope="module")
def runs():
    return run_all(load_cases())


@pytest.fixture(scope="module")
def report(runs):
    return build_report(runs)


def test_index_declares_thirty_ready_cases() -> None:
    index = load_index()
    cases = load_cases()

    assert index["case_count"] == 30
    assert len(cases) == 30
    assert {case.case_id for case in cases} == {
        item["case_id"] for item in index["cases"]
    }
    assert all(case.status == "ready" for case in cases)
    assert index["ready_case_count"] == len(cases)


def test_every_m2_threshold_passes(report) -> None:
    failed = [check for check in report["checks"] if not check["passed"]]

    assert not failed, json.dumps(failed, ensure_ascii=False, indent=2)
    assert report["gate_passed"] is True
    assert report["case_count"] == 30
    assert report["pass_rate"] >= 0.85
    assert report["local_preservation_rate"] == 1.0
    assert report["local_p95_ms"] <= 60_000


def test_every_case_plan_matches_the_frozen_plan_schema(runs) -> None:
    validator = Draft7Validator(PLAN_SCHEMA)
    for run in runs:
        assert run.plan is not None, run.record["case_id"]
        assert not list(validator.iter_errors(run.plan)), run.record["case_id"]
        assert run.record["schema_valid"] is True


def test_every_fact_cites_a_source_the_case_allows(runs) -> None:
    allowed_by_case = {case.case_id: set(case.sources) for case in load_cases()}
    for run in runs:
        allowed = allowed_by_case[run.record["case_id"]] | {"straight-line-estimate"}
        for fact in run.record["facts"]:
            if fact["source_record_id"]:
                assert fact["source_record_id"] in allowed, (
                    run.record["case_id"],
                    fact["slot"],
                )


def test_declared_behaviours_are_all_observed(report) -> None:
    for entry in report["cases"]:
        assert not entry["failed_behaviors"], entry


def test_local_modification_cases_keep_untouched_days_byte_identical(runs) -> None:
    local_runs = [run for run in runs if run.local is not None]
    covered = {run.record["case_id"] for run in local_runs}

    assert len(local_runs) >= 6
    assert covered == {
        case.case_id for case in load_cases() if needs_local_revision(case)
    }
    for run in local_runs:
        assert run.local.status != "failed", (run.record["case_id"], run.local.error)
        assert run.local.preservation_rate == 1.0, run.record["case_id"]
        assert run.local.changed_days, run.record["case_id"]


def test_rerunning_a_case_is_deterministic() -> None:
    case = load_cases()[0]
    first = run_case(case)
    second = run_case(case)

    assert _stable_plan(first.plan) == _stable_plan(second.plan)
    assert _stable_record(first.record) == _stable_record(second.record)


def test_stale_cases_actually_display_stale_facts(report) -> None:
    stale_cases = [
        entry
        for entry in report["cases"]
        if entry["case_id"] in {"BJ-06", "CASE-11", "CASE-14", "CASE-17", "CASE-20",
                                "CASE-23", "CASE-26", "CASE-29"}
    ]

    assert len(stale_cases) == 8
    assert all(entry["stale_facts"] > 0 for entry in stale_cases)
    assert report["metrics"]["stale_display_rate"] == 1.0


def test_degraded_cases_explain_every_degraded_field(report) -> None:
    degraded = [entry for entry in report["cases"] if entry["degraded_fields"]]

    assert degraded, "the fixed set must exercise at least one degradation"
    assert report["metrics"]["degradation_visibility_rate"] == 1.0


def _stable_plan(plan):
    if plan is None:
        return None
    copy = json.loads(json.dumps(plan, ensure_ascii=False))
    copy.pop("generated_at", None)
    return copy


def _stable_record(record):
    copy = json.loads(json.dumps(record, ensure_ascii=False))
    copy.pop("latency_ms", None)
    copy.pop("local_modification_latency_ms", None)
    return copy