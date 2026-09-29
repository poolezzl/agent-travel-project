"""Validate S05 evaluation fixtures and S06 Beijing data fixtures."""
from __future__ import annotations

import argparse
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

CITIES = {"CN-BJ": "北京", "CN-SH": "上海", "CN-HZ": "杭州", "CN-NJ": "南京", "CN-SZ": "苏州", "CN-CD": "成都", "CN-XA": "西安", "CN-CQ": "重庆", "CN-GZ": "广州", "CN-XM": "厦门"}
REQUIRED_CASE = {"schema_version", "case_id", "case_version", "status", "city_code", "evaluation_date", "user_request", "allowed_source_record_ids", "hard_constraints", "expected_behaviors", "expected_evidence", "manual_score", "runs"}
REQUIRED_SOURCE = {"source_record_id", "source_url", "license", "city_code", "poi_id", "aliases", "entered_by", "reviewed_by", "retrieved_at", "valid_until", "status"}


def load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_cases(root: Path) -> list[str]:
    errors: list[str] = []
    files = sorted((root / "cases").glob("*.json"))
    ids: set[str] = set()
    beijing = 0
    for path in files:
        case = load(path)
        missing = REQUIRED_CASE - case.keys()
        if missing:
            errors.append(f"{path}: missing {sorted(missing)}")
        cid = case.get("case_id")
        if cid in ids:
            errors.append(f"{path}: duplicate case_id {cid}")
        ids.add(cid)
        if case.get("city_code") == "CN-BJ":
            beijing += 1
        if case.get("status") not in {"ready", "template", "draft"}:
            errors.append(f"{path}: invalid status")
    if len(files) < 30:
        errors.append(f"need at least 30 cases, found {len(files)}")
    if beijing < 10:
        errors.append(f"need at least 10 Beijing cases, found {beijing}")
    required_topics = {"same_name_poi", "closed_conflict", "forecast_window", "climate_statistics", "stale_visible", "unknown_fact", "local_point_preserved", "local_day_scope", "cross_day_scope"}
    topics = {topic for path in files for topic in load(path).get("expected_behaviors", [])}
    for topic in sorted(required_topics - topics):
        errors.append(f"missing coverage topic {topic}")
    return errors


def validate_data(root: Path, reference: datetime) -> list[str]:
    errors: list[str] = []
    sources = [load(path) for path in sorted((root / "sources").glob("*.json"))]
    source_ids = set()
    for source in sources:
        missing = REQUIRED_SOURCE - source.keys()
        if missing:
            errors.append(f"source missing {sorted(missing)}")
        sid = source.get("source_record_id")
        if sid in source_ids:
            errors.append(f"duplicate source_record_id {sid}")
        source_ids.add(sid)
        if source.get("city_code") not in CITIES:
            errors.append(f"unknown city {source.get('city_code')}")
        if source.get("status") not in {"draft", "review", "published", "retired", "manual_queue"}:
            errors.append(f"invalid source status {source.get('status')}")
        if source.get("license") in {None, "", "unknown"} and source.get("status") == "published":
            errors.append(f"published source has no license: {sid}")
        retrieved = datetime.fromisoformat(source["retrieved_at"].replace("Z", "+00:00"))
        valid_until = source.get("valid_until")
        if valid_until and datetime.fromisoformat(valid_until.replace("Z", "+00:00")) < reference and source.get("status") == "published":
            errors.append(f"expired source is published: {sid}")
        if reference - retrieved > timedelta(days=30) and source.get("freshness_status") != "stale":
            errors.append(f"source freshness must be stale: {sid}")
    readable = {source["source_record_id"] for source in sources if source.get("status") == "published"}
    for path in sorted((root / "beijing").glob("*.json")):
        item = load(path)
        if item.get("source_record_id") not in source_ids:
            errors.append(f"{path}: source does not exist")
        if item.get("status") == "published" and item.get("source_record_id") not in readable:
            errors.append(f"{path}: published item references non-published source")
        if item.get("conflict_flags") and item.get("status") == "published":
            errors.append(f"{path}: conflict item cannot be published")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evaluation-root", type=Path, default=Path("tests/fixtures/evaluation"))
    parser.add_argument("--data-root", type=Path, default=Path("tests/fixtures/data"))
    parser.add_argument("--reference-time", default="2026-10-01T00:00:00+00:00")
    args = parser.parse_args()
    errors = validate_cases(args.evaluation_root)
    errors.extend(validate_data(args.data_root, datetime.fromisoformat(args.reference_time)))
    if errors:
        print("\n".join(errors))
        return 1
    print("S05/S06 fixtures valid: 30 cases, 10 Beijing cases, source/readability checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
