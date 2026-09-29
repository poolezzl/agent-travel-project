"""Deterministic evaluation metrics for redacted trip-planning runs.

The module deliberately accepts only already-redacted JSON records. It has no
network, model, filesystem, or provider integration.
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
from pathlib import Path
from typing import Any

REQUIRED_RUN_FIELDS = {
    "run_id", "case_id", "hard_constraints", "facts", "route",
    "schema_valid", "latency_ms", "cost", "degraded_fields",
}
REQUIRED_FACT_FIELDS = {"source_record_id", "freshness_status", "is_estimated", "displayed_status"}


class EvaluationInputError(ValueError):
    """Raised when a redacted run record violates the evaluation contract."""


def _ratio(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0


def _percentile(values: list[float], percentile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = (len(ordered) - 1) * percentile
    lower, upper = math.floor(rank), math.ceil(rank)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (rank - lower)


def validate_run(run: dict[str, Any]) -> None:
    missing = REQUIRED_RUN_FIELDS - run.keys()
    if missing:
        raise EvaluationInputError(f"{run.get('run_id', '<unknown>')}: missing {sorted(missing)}")
    if not isinstance(run["latency_ms"], (int, float)) or run["latency_ms"] < 0:
        raise EvaluationInputError(f"{run['run_id']}: latency_ms must be non-negative")
    if not isinstance(run["cost"], dict) or not isinstance(run["cost"].get("token_count"), int):
        raise EvaluationInputError(f"{run['run_id']}: cost.token_count must be an integer")
    if run["cost"]["token_count"] < 0 or float(run["cost"].get("api_cost_minor", 0)) < 0:
        raise EvaluationInputError(f"{run['run_id']}: cost cannot be negative")
    if not isinstance(run["facts"], list) or not isinstance(run["hard_constraints"], list):
        raise EvaluationInputError(f"{run['run_id']}: facts and hard_constraints must be arrays")
    for fact in run["facts"]:
        missing_fact = REQUIRED_FACT_FIELDS - fact.keys()
        if missing_fact:
            raise EvaluationInputError(f"{run['run_id']}: fact missing {sorted(missing_fact)}")
    for constraint in run["hard_constraints"]:
        if not {"id", "satisfied"}.issubset(constraint):
            raise EvaluationInputError(f"{run['run_id']}: malformed hard constraint")
    if not isinstance(run["degraded_fields"], list):
        raise EvaluationInputError(f"{run['run_id']}: degraded_fields must be an array")


def _validate_unique(runs: list[dict[str, Any]]) -> None:
    for field in ("run_id",):
        values = [run[field] for run in runs]
        if len(values) != len(set(values)):
            raise EvaluationInputError(f"duplicate {field}")


def _local_preservation(run: dict[str, Any]) -> tuple[int, int]:
    local = run.get("local_modification")
    if not local:
        return (0, 0)
    unchanged = local.get("unchanged_blocks", [])
    preserved = sum(1 for block in unchanged if block.get("before_hash") == block.get("after_hash"))
    return preserved, len(unchanged)


def calculate_metrics(runs: list[dict[str, Any]]) -> dict[str, Any]:
    if not runs:
        raise EvaluationInputError("run set must not be empty")
    for run in runs:
        validate_run(run)
    _validate_unique(runs)

    constraints = [constraint for run in runs for constraint in run["hard_constraints"]]
    facts = [fact for run in runs for fact in run["facts"]]
    stale_expected = [fact for fact in facts if fact["freshness_status"] == "stale"]
    stale_correct = [fact for fact in stale_expected if fact["displayed_status"] == "stale"]
    preserved, compared = (sum(values) for values in zip(*(_local_preservation(run) for run in runs)))
    latencies = [float(run["latency_ms"]) for run in runs]
    costs = [float(run["cost"].get("api_cost_minor", 0)) for run in runs]
    tokens = [run["cost"]["token_count"] for run in runs]
    route_results = [run["route"].get("feasible") for run in runs]
    route_known = [value for value in route_results if isinstance(value, bool)]
    source_complete = sum(bool(fact["source_record_id"]) for fact in facts)
    degraded_visible = sum(1 for run in runs if not run["degraded_fields"] or run.get("degradation_explained", False))
    schema_valid = sum(bool(run["schema_valid"]) for run in runs)
    satisfied = sum(bool(constraint["satisfied"]) for constraint in constraints)

    return {
        "run_count": len(runs),
        "hard_constraints": {
            "total": len(constraints),
            "violations": len(constraints) - satisfied,
            "satisfaction_rate": _ratio(satisfied, len(constraints)),
        },
        "source_completeness_rate": _ratio(source_complete, len(facts)),
        "stale_display_rate": _ratio(len(stale_correct), len(stale_expected)),
        "route_feasibility_rate": _ratio(sum(route_known), len(route_known)),
        "schema_valid_rate": _ratio(schema_valid, len(runs)),
        "local_modification_preservation_rate": _ratio(preserved, compared),
        "degradation_visibility_rate": _ratio(degraded_visible, len(runs)),
        "latency_ms": {
            "p50": _percentile(latencies, 0.50),
            "p95": _percentile(latencies, 0.95),
        },
        "cost": {
            "token_total": sum(tokens),
            "token_mean": round(statistics.fmean(tokens), 6),
            "api_cost_minor_total": round(sum(costs), 6),
            "api_cost_minor_mean": round(statistics.fmean(costs), 6),
        },
    }


def load_runs(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise EvaluationInputError("run file must contain a JSON array")
    return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Calculate deterministic metrics from redacted runs")
    parser.add_argument("runs", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        result = calculate_metrics(load_runs(args.runs))
    except (OSError, json.JSONDecodeError, EvaluationInputError) as exc:
        print(f"evaluation input error: {exc}", file=sys.stderr)
        return 2
    text = json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    else:
        print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
