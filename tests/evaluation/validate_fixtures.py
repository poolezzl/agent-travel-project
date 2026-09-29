"""Validate S05 evaluation fixtures and S06 Beijing data fixtures."""
from __future__ import annotations

import argparse
import json
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any

CITIES = {
    "CN-BJ": "北京",
    "CN-SH": "上海",
    "CN-HZ": "杭州",
    "CN-NJ": "南京",
    "CN-SZ": "苏州",
    "CN-CD": "成都",
    "CN-XA": "西安",
    "CN-CQ": "重庆",
    "CN-GZ": "广州",
    "CN-XM": "厦门",
}
REQUIRED_CASE = {
    "schema_version", "case_id", "case_version", "status", "city_code",
    "evaluation_date", "user_request", "allowed_source_record_ids",
    "hard_constraints", "expected_behaviors", "expected_evidence",
    "manual_score", "runs",
}
REQUIRED_SOURCE = {
    "source_record_id", "source_url", "license", "city_code", "poi_id",
    "aliases", "entered_by", "reviewed_by", "retrieved_at", "valid_until",
    "freshness_status", "is_estimated", "status", "content_hash", "notes",
}
REQUIRED_ITEM = {
    "poi_id", "city_code", "name", "aliases", "source_record_id", "status",
    "conflict_flags", "retrieved_at", "freshness_status", "is_estimated",
}
SOURCE_STATUSES = {"draft", "review", "published", "retired", "manual_queue"}
CASE_STATUSES = {"ready", "template", "draft"}
FRESHNESS_STATUSES = {"fresh", "stale", "unknown"}
PENDING_VALUES = {"pending", "unknown", "unreviewed", "not-reviewed"}


def load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _load_object(path: Path, errors: list[str]) -> dict[str, Any] | None:
    try:
        value = load(path)
    except (OSError, json.JSONDecodeError) as exc:
        errors.append(f"{path}: cannot load JSON: {exc}")
        return None
    if not isinstance(value, dict):
        errors.append(f"{path}: record must be an object")
        return None
    return value


def _is_non_empty_string(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _validate_string_list(value: Any, label: str, errors: list[str]) -> bool:
    if not isinstance(value, list) or any(
        not _is_non_empty_string(item) for item in value
    ):
        errors.append(f"{label} must be an array of non-empty strings")
        return False
    return True


def _parse_datetime(
    value: Any,
    label: str,
    errors: list[str],
) -> datetime | None:
    if not _is_non_empty_string(value):
        errors.append(f"{label} must be a timezone-aware ISO datetime")
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        errors.append(f"{label} must be a valid ISO datetime")
        return None
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        errors.append(f"{label} must include a timezone offset")
        return None
    return parsed


def _validate_case(
    case: dict[str, Any],
    path: Path,
    errors: list[str],
) -> list[str]:
    missing = REQUIRED_CASE - case.keys()
    if missing:
        errors.append(f"{path}: missing {sorted(missing)}")

    if case.get("schema_version") != "evaluation-case-v1":
        errors.append(f"{path}: invalid schema_version")
    case_id = case.get("case_id")
    if not _is_non_empty_string(case_id):
        errors.append(f"{path}: case_id must be a non-empty string")
    version = case.get("case_version")
    if (
        not isinstance(version, int)
        or isinstance(version, bool)
        or version < 1
    ):
        errors.append(f"{path}: case_version must be a positive integer")
    if case.get("status") not in CASE_STATUSES:
        errors.append(f"{path}: invalid status")
    if case.get("city_code") not in CITIES:
        errors.append(f"{path}: unknown city {case.get('city_code')}")
    try:
        evaluation_date = case.get("evaluation_date")
        if not isinstance(evaluation_date, str):
            raise ValueError
        date.fromisoformat(evaluation_date)
    except ValueError:
        errors.append(f"{path}: evaluation_date must be an ISO date")

    request = case.get("user_request")
    if not isinstance(request, dict):
        errors.append(f"{path}: user_request must be an object")
    elif any(
        not _is_non_empty_string(request.get(field))
        for field in ("summary", "traveler_profile")
    ):
        errors.append(
            f"{path}: user_request must contain summary and traveler_profile"
        )

    for field in (
        "allowed_source_record_ids",
        "expected_behaviors",
        "expected_evidence",
    ):
        _validate_string_list(case.get(field), f"{path}: {field}", errors)
    if not isinstance(case.get("runs"), list):
        errors.append(f"{path}: runs must be an array")

    constraints = case.get("hard_constraints")
    if not isinstance(constraints, list):
        errors.append(f"{path}: hard_constraints must be an array")
    else:
        for index, constraint in enumerate(constraints):
            label = f"{path}: hard_constraints[{index}]"
            if not isinstance(constraint, dict):
                errors.append(f"{label} must be an object")
                continue
            if any(
                not _is_non_empty_string(constraint.get(field))
                for field in ("id", "description", "expected")
            ):
                errors.append(
                    f"{label} must contain id, description and expected"
                )

    score = case.get("manual_score")
    if not isinstance(score, dict):
        errors.append(f"{path}: manual_score must be an object")
    else:
        if not _is_non_empty_string(score.get("status")):
            errors.append(
                f"{path}: manual_score.status must be a non-empty string"
            )
        total = score.get("score_total")
        if total is not None and (
            not isinstance(total, (int, float)) or isinstance(total, bool)
        ):
            errors.append(
                f"{path}: manual_score.score_total must be numeric or null"
            )

    local = case.get("local_modification")
    if local is not None:
        if not isinstance(local, dict):
            errors.append(
                f"{path}: local_modification must be an object or null"
            )
        else:
            if not _is_non_empty_string(local.get("base_version_id")):
                errors.append(
                    f"{path}: local_modification.base_version_id is required"
                )
            for field in ("affected_blocks", "unchanged_blocks"):
                _validate_string_list(
                    local.get(field),
                    f"{path}: local_modification.{field}",
                    errors,
                )

    behaviors = case.get("expected_behaviors")
    return behaviors if isinstance(behaviors, list) else []


def validate_cases(root: Path) -> list[str]:
    errors: list[str] = []
    files = sorted((root / "cases").glob("*.json"))
    ids: set[str] = set()
    beijing = 0
    topics: set[str] = set()
    for path in files:
        case = _load_object(path, errors)
        if case is None:
            continue
        topics.update(_validate_case(case, path, errors))
        case_id = case.get("case_id")
        if isinstance(case_id, str):
            if case_id in ids:
                errors.append(f"{path}: duplicate case_id {case_id}")
            ids.add(case_id)
        if case.get("city_code") == "CN-BJ":
            beijing += 1
    if len(files) < 30:
        errors.append(f"need at least 30 cases, found {len(files)}")
    if beijing < 10:
        errors.append(f"need at least 10 Beijing cases, found {beijing}")
    required_topics = {
        "same_name_poi",
        "closed_conflict",
        "forecast_window",
        "climate_statistics",
        "stale_visible",
        "unknown_fact",
        "local_point_preserved",
        "local_day_scope",
        "cross_day_scope",
    }
    for topic in sorted(required_topics - topics):
        errors.append(f"missing coverage topic {topic}")
    return errors


def _source_is_readable(
    source: dict[str, Any],
    path: Path,
    reference: datetime | None,
    errors: list[str],
) -> bool:
    missing = REQUIRED_SOURCE - source.keys()
    if missing:
        errors.append(f"{path}: missing {sorted(missing)}")

    for field in (
        "source_record_id",
        "source_url",
        "license",
        "city_code",
        "poi_id",
        "entered_by",
        "reviewed_by",
        "content_hash",
        "notes",
    ):
        if not _is_non_empty_string(source.get(field)):
            errors.append(f"{path}: {field} must be a non-empty string")
    _validate_string_list(source.get("aliases"), f"{path}: aliases", errors)
    if source.get("city_code") not in CITIES:
        errors.append(f"{path}: unknown city {source.get('city_code')}")
    if source.get("status") not in SOURCE_STATUSES:
        errors.append(
            f"{path}: invalid source status {source.get('status')}"
        )
    if source.get("freshness_status") not in FRESHNESS_STATUSES:
        errors.append(f"{path}: invalid freshness_status")
    if not isinstance(source.get("is_estimated"), bool):
        errors.append(f"{path}: is_estimated must be a boolean")

    retrieved = _parse_datetime(
        source.get("retrieved_at"),
        f"{path}: retrieved_at",
        errors,
    )
    valid_until = _parse_datetime(
        source.get("valid_until"),
        f"{path}: valid_until",
        errors,
    )
    source_id = source.get("source_record_id")
    if reference is not None and retrieved is not None:
        if (
            reference - retrieved > timedelta(days=30)
            and source.get("freshness_status") != "stale"
        ):
            errors.append(
                f"{path}: source freshness must be stale: {source_id}"
            )

    if source.get("status") != "published":
        return False

    readable = not missing
    license_value = source.get("license")
    if (
        not _is_non_empty_string(license_value)
        or license_value.strip().lower() in PENDING_VALUES
    ):
        errors.append(
            f"{path}: published source has no valid license: {source_id}"
        )
        readable = False
    reviewer = source.get("reviewed_by")
    if (
        not _is_non_empty_string(reviewer)
        or "pending" in reviewer.strip().lower()
    ):
        errors.append(
            f"{path}: published source review is pending: {source_id}"
        )
        readable = False
    if source.get("freshness_status") != "fresh":
        errors.append(f"{path}: published source is not fresh: {source_id}")
        readable = False
    if reference is None or valid_until is None or valid_until < reference:
        errors.append(
            f"{path}: published source is expired or has no valid_until: "
            f"{source_id}"
        )
        readable = False
    if retrieved is None:
        readable = False
    return readable


def validate_data(root: Path, reference: datetime) -> list[str]:
    errors: list[str] = []
    valid_reference: datetime | None = reference
    if (
        not isinstance(reference, datetime)
        or reference.tzinfo is None
        or reference.utcoffset() is None
    ):
        errors.append("reference time must be a timezone-aware datetime")
        valid_reference = None

    sources: dict[str, tuple[dict[str, Any], Path]] = {}
    readable: set[str] = set()
    for path in sorted((root / "sources").glob("*.json")):
        source = _load_object(path, errors)
        if source is None:
            continue
        source_id = source.get("source_record_id")
        if _is_non_empty_string(source_id):
            if source_id in sources:
                errors.append(
                    f"{path}: duplicate source_record_id {source_id}"
                )
            else:
                sources[source_id] = (source, path)
        if (
            _source_is_readable(source, path, valid_reference, errors)
            and isinstance(source_id, str)
        ):
            readable.add(source_id)

    for path in sorted((root / "beijing").glob("*.json")):
        item = _load_object(path, errors)
        if item is None:
            continue
        missing = REQUIRED_ITEM - item.keys()
        if missing:
            errors.append(f"{path}: missing {sorted(missing)}")
        for field in ("poi_id", "city_code", "name", "source_record_id"):
            if not _is_non_empty_string(item.get(field)):
                errors.append(f"{path}: {field} must be a non-empty string")
        _validate_string_list(item.get("aliases"), f"{path}: aliases", errors)
        _validate_string_list(
            item.get("conflict_flags"),
            f"{path}: conflict_flags",
            errors,
        )
        if item.get("city_code") != "CN-BJ":
            errors.append(f"{path}: Beijing item must use city_code CN-BJ")
        if item.get("status") not in SOURCE_STATUSES:
            errors.append(
                f"{path}: invalid item status {item.get('status')}"
            )
        if item.get("freshness_status") not in FRESHNESS_STATUSES:
            errors.append(f"{path}: invalid freshness_status")
        if not isinstance(item.get("is_estimated"), bool):
            errors.append(f"{path}: is_estimated must be a boolean")
        _parse_datetime(
            item.get("retrieved_at"),
            f"{path}: retrieved_at",
            errors,
        )

        source_id = item.get("source_record_id")
        source_entry = (
            sources.get(source_id) if isinstance(source_id, str) else None
        )
        if source_entry is None:
            errors.append(f"{path}: source does not exist")
        else:
            source, _ = source_entry
            if item.get("city_code") != source.get("city_code"):
                errors.append(f"{path}: city_code does not match source")
            if item.get("poi_id") != source.get("poi_id"):
                errors.append(f"{path}: poi_id does not match source")
        if item.get("status") == "published" and source_id not in readable:
            errors.append(
                f"{path}: published item references unreadable source"
            )
        if item.get("conflict_flags") and item.get("status") == "published":
            errors.append(f"{path}: conflict item cannot be published")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--evaluation-root",
        type=Path,
        default=Path("tests/fixtures/evaluation"),
    )
    parser.add_argument(
        "--data-root",
        type=Path,
        default=Path("tests/fixtures/data"),
    )
    parser.add_argument(
        "--reference-time",
        default="2026-10-01T00:00:00+00:00",
    )
    args = parser.parse_args()
    errors = validate_cases(args.evaluation_root)
    reference_errors: list[str] = []
    reference = _parse_datetime(
        args.reference_time,
        "reference time",
        reference_errors,
    )
    errors.extend(reference_errors)
    if reference is not None:
        errors.extend(validate_data(args.data_root, reference))
    if errors:
        print("\n".join(errors))
        return 1
    print(
        "S05/S06 fixtures valid: 30 cases, 10 Beijing cases, "
        "source/readability checks passed"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
