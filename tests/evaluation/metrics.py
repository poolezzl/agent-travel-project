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


def _is_finite_number(value: Any) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(value)
    )


def validate_run(run: Any) -> None:
    if not isinstance(run, dict):
        raise EvaluationInputError("run record must be an object")
    missing = REQUIRED_RUN_FIELDS - run.keys()
    if missing:
        raise EvaluationInputError(f"{run.get('run_id', '<unknown>')}: missing {sorted(missing)}")
    run_id = run["run_id"]
    if not isinstance(run_id, str) or not run_id.strip():
        raise EvaluationInputError("run_id must be a non-empty string")
    if not isinstance(run["case_id"], str) or not run["case_id"].strip():
        raise EvaluationInputError(f"{run_id}: case_id must be a non-empty string")
    if not _is_finite_number(run["latency_ms"]) or run["latency_ms"] < 0:
        raise EvaluationInputError(f"{run_id}: latency_ms must be a finite non-negative number")
    if not isinstance(run["cost"], dict):
        raise EvaluationInputError(f"{run_id}: cost must be an object")
    token_count = run["cost"].get("token_count")
    if not isinstance(token_count, int) or isinstance(token_count, bool) or token_count < 0:
        raise EvaluationInputError(f"{run_id}: cost.token_count must be a non-negative integer")
    api_cost = run["cost"].get("api_cost_minor", 0)
    if not _is_finite_number(api_cost) or api_cost < 0:
        raise EvaluationInputError(f"{run_id}: cost.api_cost_minor must be a finite non-negative number")
    if not isinstance(run["facts"], list) or not isinstance(run["hard_constraints"], list):
        raise EvaluationInputError(f"{run_id}: facts and hard_constraints must be arrays")
    for fact in run["facts"]:
        if not isinstance(fact, dict):
            raise EvaluationInputError(f"{run_id}: fact must be an object")
        missing_fact = REQUIRED_FACT_FIELDS - fact.keys()
        if missing_fact:
            raise EvaluationInputError(f"{run_id}: fact missing {sorted(missing_fact)}")
        if not isinstance(fact["source_record_id"], str):
            raise EvaluationInputError(f"{run_id}: fact source_record_id must be a string")
        if not isinstance(fact["is_estimated"], bool):
            raise EvaluationInputError(f"{run_id}: fact is_estimated must be a boolean")
    for constraint in run["hard_constraints"]:
        if not isinstance(constraint, dict) or not {"id", "satisfied"}.issubset(constraint):
            raise EvaluationInputError(f"{run_id}: malformed hard constraint")
        if not isinstance(constraint["id"], str) or not constraint["id"].strip():
            raise EvaluationInputError(f"{run_id}: hard constraint id must be a non-empty string")
        if not isinstance(constraint["satisfied"], bool):
            raise EvaluationInputError(f"{run_id}: hard constraint satisfied must be a boolean")
    if not isinstance(run["route"], dict) or not isinstance(run["route"].get("feasible"), bool):
        raise EvaluationInputError(f"{run_id}: route.feasible must be a boolean")
    if not isinstance(run["schema_valid"], bool):
        raise EvaluationInputError(f"{run_id}: schema_valid must be a boolean")
    if not isinstance(run["degraded_fields"], list):
        raise EvaluationInputError(f"{run_id}: degraded_fields must be an array")
    if "degradation_explained" in run and not isinstance(run["degradation_explained"], bool):
        raise EvaluationInputError(f"{run_id}: degradation_explained must be a boolean")
    local = run.get("local_modification")
    if local is not None:
        if not isinstance(local, dict):
            raise EvaluationInputError(f"{run_id}: local_modification must be an object")
        unchanged = local.get("unchanged_blocks", [])
        if not isinstance(unchanged, list):
            raise EvaluationInputError(f"{run_id}: unchanged_blocks must be an array")
        for block in unchanged:
            if not isinstance(block, dict):
                raise EvaluationInputError(f"{run_id}: unchanged block must be an object")
            if not {"before_hash", "after_hash"}.issubset(block):
                raise EvaluationInputError(f"{run_id}: unchanged block must contain before_hash and after_hash")


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
    route_results = [run["route"]["feasible"] for run in runs]
    source_complete = sum(bool(fact["source_record_id"]) for fact in facts)
    degraded_visible = sum(1 for run in runs if not run["degraded_fields"] or run.get("degradation_explained", False))
    schema_valid = sum(run["schema_valid"] for run in runs)
    satisfied = sum(constraint["satisfied"] for constraint in constraints)

    return {
        "run_count": len(runs),
        "hard_constraints": {
            "total": len(constraints),
            "violations": len(constraints) - satisfied,
            "satisfaction_rate": _ratio(satisfied, len(constraints)),
        },
        "source_completeness_rate": _ratio(source_complete, len(facts)),
        "stale_display_rate": _ratio(len(stale_correct), len(stale_expected)),
        "route_feasibility_rate": _ratio(sum(route_results), len(route_results)),
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
