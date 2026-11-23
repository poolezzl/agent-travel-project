"""S22 M2-gate runner: execute the 30 fixed cases and emit Gate evidence.

Usage (repository root)::

    python tests/evaluation/s22_gate.py --output docs/progress/s22-artifacts

The command writes three redacted artifacts:

* ``runs.json`` — the 30 run records in the ``metrics.py`` contract;
* ``metrics.json`` — deterministic aggregate metrics;
* ``m2-gate.json`` — every M2 threshold with its actual value and verdict.

It exits non-zero when any M2 threshold fails, so CI and the evidence file cannot
silently disagree.  Nothing stored here contains a prompt, a private itinerary, a
supplier response or a credential.
"""
from __future__ import annotations

import argparse
import json
import platform
import statistics
import sys
from pathlib import Path
from typing import Any

_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from metrics import calculate_metrics  # noqa: E402
from s22_fixtures import REFERENCE_TIME, load_cases, load_index  # noqa: E402
from s22_runner import CaseRun, run_case, utc_timestamp  # noqa: E402

MIN_CASES = 30
MIN_PASS_RATE = 0.85
MIN_LOCAL_PRESERVATION = 1.0
MAX_LOCAL_P95_MS = 60_000.0


def run_all(cases: list[Any] | None = None) -> list[CaseRun]:
    loaded = cases if cases is not None else load_cases()
    return [run_case(case) for case in loaded]


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = (len(ordered) - 1) * fraction
    lower, upper = int(rank), min(int(rank) + (0 if rank == int(rank) else 1), len(ordered) - 1)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (rank - lower)


def local_p95_ms(runs: list[CaseRun]) -> float:
    values = [
        float(run.record["local_modification_latency_ms"])
        for run in runs
        if run.record.get("local_modification_latency_ms") is not None
    ]
    if not values:
        return 0.0
    # Fixed set: interpolated percentile, same convention as metrics.py.
    return round(percentile(values, 0.95), 3)


def build_report(runs: list[CaseRun]) -> dict[str, Any]:
    records = [run.record for run in runs]
    metrics = calculate_metrics(records)
    index = load_index()
    pass_count = sum(1 for run in runs if run.observations["pass"])
    pass_rate = round(pass_count / len(runs), 6) if runs else 0.0
    local_runs = [run for run in runs if run.local is not None]
    local_preservation = (
        round(statistics.fmean(run.local.preservation_rate for run in local_runs), 6)
        if local_runs
        else 1.0
    )
    p95_local = local_p95_ms(runs)
    estimated_cases = [run.record["case_id"] for run in runs if run.observations["estimated_total"]]
    estimated_ok = all(run.observations["estimated_visible"] for run in runs)
    stale_runs = [run for run in runs if run.observations["stale_total"]]
    degraded_runs = [run for run in runs if run.observations["degraded_field_count"]]

    checks = [
        _check(
            "case_count",
            "固定案例数",
            len(runs),
            f">= {MIN_CASES}",
            len(runs) >= MIN_CASES,
        ),
        _check(
            "all_cases_ready",
            "案例全部 ready 且已执行",
            all(run.observations["plan_present"] or run.observations["dag_error"] for run in runs),
            "每例都产生方案或稳定失败码",
            all(
                run.observations["plan_present"] or bool(run.observations["dag_error"])
                for run in runs
            ),
        ),
        _check(
            "e2e_pass_rate",
            "端到端通过率",
            pass_rate,
            f">= {MIN_PASS_RATE}",
            pass_rate >= MIN_PASS_RATE,
        ),
        _check(
            "local_modification_preservation",
            "局部修改未影响区块保真率",
            local_preservation,
            f"= {MIN_LOCAL_PRESERVATION}",
            len(local_runs) >= 1 and local_preservation >= MIN_LOCAL_PRESERVATION,
        ),
        _check(
            "stale_display",
            "过期事实可见（所有 stale 事实均显式标注）",
            metrics["stale_display_rate"],
            "= 1.0 且至少 1 例含 stale 事实",
            metrics["stale_display_rate"] >= 1.0 and len(stale_runs) >= 1,
        ),
        _check(
            "estimated_visibility",
            "估算路段可见（is_estimated + 降级字段）",
            estimated_ok,
            "所有含估算的案例均显式标注",
            estimated_ok and bool(estimated_cases),
        ),
        _check(
            "degradation_visibility",
            "降级可见率（有空降级字段必须解释）",
            metrics["degradation_visibility_rate"],
            "= 1.0 且至少 1 例降级",
            metrics["degradation_visibility_rate"] >= 1.0 and len(degraded_runs) >= 1,
        ),
        _check(
            "local_modification_p95",
            "P95 局部修改时延",
            p95_local,
            f"<= {MAX_LOCAL_P95_MS:.0f} ms",
            len(local_runs) >= 1 and p95_local <= MAX_LOCAL_P95_MS,
        ),
        _check(
            "schema_valid_rate",
            "Plan Schema 合法率",
            metrics["schema_valid_rate"],
            "= 1.0",
            metrics["schema_valid_rate"] >= 1.0,
        ),
        _check(
            "source_completeness",
            "事实来源完整率",
            metrics["source_completeness_rate"],
            "= 1.0",
            metrics["source_completeness_rate"] >= 1.0,
        ),
        _check(
            "source_whitelist",
            "事实来源均在案例白名单内",
            all(run.observations["source_whitelist_ok"] for run in runs),
            "全部通过",
            all(run.observations["source_whitelist_ok"] for run in runs),
        ),
        _check(
            "hard_constraints",
            "硬约束违反数",
            metrics["hard_constraints"]["violations"],
            "= 0",
            metrics["hard_constraints"]["violations"] == 0,
        ),
        _check(
            "dag_stability",
            "固定 DAG 无步骤级失败",
            sum(1 for run in runs if run.observations["dag_error"]),
            "= 0",
            all(not run.observations["dag_error"] for run in runs),
        ),
    ]

    cases = [
        {
            "case_id": run.record["case_id"],
            "case_version": run.record["case_version"],
            "passed": run.observations["pass"],
            "plan_status": run.record["plan_status"],
            "confirmation_required": run.record["confirmation_required"],
            "behaviors": run.observations["behaviors"],
            "failed_behaviors": sorted(
                key for key, value in run.observations["behaviors"].items() if not value
            ),
            "conflict_codes": run.observations["conflict_codes"],
            "stale_facts": run.observations["stale_total"],
            "degraded_fields": run.observations["degraded_field_count"],
            "local_preservation_rate": run.observations["local_preservation_rate"],
            "local_latency_ms": run.observations["local_latency_ms"],
            "latency_ms": run.record["latency_ms"],
        }
        for run in runs
    ]

    return {
        "schema_version": "s22-m2-gate-v1",
        "generated_at": utc_timestamp(),
        "reference_time": REFERENCE_TIME.isoformat().replace("+00:00", "Z"),
        "interpreter": f"CPython {platform.python_version()}",
        "index_ready_case_count": index.get("ready_case_count"),
        "case_count": len(runs),
        "pass_count": pass_count,
        "pass_rate": pass_rate,
        "local_case_count": len(local_runs),
        "local_preservation_rate": local_preservation,
        "local_p95_ms": p95_local,
        "gate_passed": all(check["passed"] for check in checks),
        "checks": checks,
        "metrics": metrics,
        "cases": cases,
    }


def _check(key: str, label: str, actual: Any, threshold: str, passed: bool) -> dict[str, Any]:
    return {
        "key": key,
        "label": label,
        "actual": actual,
        "threshold": threshold,
        "passed": bool(passed),
    }


def write_artifacts(report: dict[str, Any], runs: list[CaseRun], output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    _dump(output / "runs.json", [run.record for run in runs])
    _dump(output / "metrics.json", report["metrics"])
    _dump(output / "m2-gate.json", report)


def _dump(path: Path, payload: Any) -> None:
    text = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    path.write_text(text, encoding="utf-8")


def render_summary(report: dict[str, Any]) -> str:
    lines = [
        f"M2 Gate: {'PASS' if report['gate_passed'] else 'FAIL'}",
        f"cases={report['case_count']} passed={report['pass_count']} "
        f"rate={report['pass_rate']:.2%}",
        f"local_modification cases={report['local_case_count']} "
        f"preservation={report['local_preservation_rate']:.2%} "
        f"p95={report['local_p95_ms']:.1f} ms",
    ]
    for check in report["checks"]:
        if not check["passed"]:
            lines.append(f"  FAIL {check['key']}: {check['actual']} ({check['threshold']})")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the S22 fixed-case evaluation")
    parser.add_argument("--output", type=Path, default=_HERE.parents[1] / "docs" / "progress"
                        / "s22-artifacts")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)
    runs = run_all()
    report = build_report(runs)
    write_artifacts(report, runs, args.output)
    if not args.quiet:
        print(render_summary(report))
        print(f"artifacts: {args.output}")
    return 0 if report["gate_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())