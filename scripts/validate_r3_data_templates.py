"""Validate the R3 data templates and synthetic samples.

This utility checks the S06 handoff artifacts only. It does not import data,
publish records, call external services, or mutate the database.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from datetime import date, datetime
from pathlib import Path
from typing import Any


CSV_TEMPLATES = {
    "source_registry_template.csv": [
        "source_record_id",
        "source_type",
        "source_url",
        "license_type",
        "license_reference",
        "permitted_use",
        "attribution_required",
        "collected_at",
        "retrieved_at",
        "valid_until",
        "freshness_status",
        "source_status",
        "content_hash",
        "operator_id",
        "reviewer_id",
        "reviewed_at",
        "notes",
    ],
    "poi_import_template.csv": [
        "poi_id",
        "city_id",
        "name",
        "alias_names",
        "longitude",
        "latitude",
        "coordinate_system",
        "district",
        "poi_category",
        "merge_group_id",
        "source_record_id",
        "review_status",
        "version",
        "operator_id",
        "reviewer_id",
        "reviewed_at",
    ],
    "attraction_import_template.csv": [
        "knowledge_id",
        "poi_id",
        "fact_type",
        "fact_value",
        "chunk_id",
        "source_record_id",
        "published_at",
        "last_verified_at",
        "valid_until",
        "freshness_status",
        "review_status",
        "version",
        "operator_id",
        "reviewer_id",
        "reviewed_at",
    ],
    "reservation_rule_import_template.csv": [
        "policy_id",
        "poi_id",
        "ticket_type",
        "applicable_group",
        "reservation_required",
        "reservation_channel",
        "release_time",
        "release_timezone",
        "advance_days",
        "currency",
        "amount_minor",
        "free_condition",
        "valid_from",
        "valid_until",
        "holiday_exception",
        "last_verified_at",
        "stale_after_days",
        "stale",
        "source_record_id",
        "review_status",
        "version",
        "operator_id",
        "reviewer_id",
        "reviewed_at",
    ],
}

SAMPLE_FILES = {
    "source_registry_sample.jsonl": "source_registry",
    "poi_sample.jsonl": "poi",
    "attraction_sample.jsonl": "attraction",
    "reservation_rule_sample.jsonl": "reservation_rule",
}

ID_PATTERN = re.compile(r"^[A-Z0-9][A-Z0-9_-]+$")
CITY_PATTERN = re.compile(r"^[a-z0-9_]+$")
DATE_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}$")
TIME_PATTERN = re.compile(r"^(?:[01]\d|2[0-3]):[0-5]\d$")
DATETIME_PATTERN = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$"
)
REVIEW_STATUSES = {"draft", "review", "published", "suspended", "rolled_back"}
FRESHNESS_STATUSES = {"fresh", "stale", "unknown"}
SOURCE_STATUSES = {"pending_review", "approved", "rejected", "revoked"}
POI_CATEGORIES = {"attraction", "restaurant", "lodging"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Project root containing docs/data.",
    )
    return parser.parse_args()


def check_id(value: Any, field: str, location: str, errors: list[str]) -> None:
    if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
        errors.append(f"{location}.{field}: invalid internal ID")


def check_datetime(value: Any, field: str, location: str, errors: list[str]) -> None:
    if value is None:
        return
    if not isinstance(value, str) or not DATETIME_PATTERN.fullmatch(value):
        errors.append(f"{location}.{field}: expected ISO 8601 datetime with timezone")
        return
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        errors.append(f"{location}.{field}: invalid datetime")


def check_date(value: Any, field: str, location: str, errors: list[str]) -> None:
    if value is None:
        return
    if not isinstance(value, str) or not DATE_PATTERN.fullmatch(value):
        errors.append(f"{location}.{field}: expected YYYY-MM-DD")
        return
    try:
        date.fromisoformat(value)
    except ValueError:
        errors.append(f"{location}.{field}: invalid date")


def check_common(record: dict[str, Any], location: str, errors: list[str]) -> None:
    source_record_id = record.get("source_record_id")
    check_id(source_record_id, "source_record_id", location, errors)
    if "review_status" in record and record["review_status"] not in REVIEW_STATUSES:
        errors.append(f"{location}.review_status: unsupported status")
    if "freshness_status" in record and record["freshness_status"] not in FRESHNESS_STATUSES:
        errors.append(f"{location}.freshness_status: unsupported status")
    if "version" in record and (
        not isinstance(record["version"], int) or record["version"] < 1
    ):
        errors.append(f"{location}.version: must be a positive integer")
    for field in ("collected_at", "retrieved_at", "published_at", "last_verified_at"):
        if field in record:
            check_datetime(record[field], field, location, errors)


def validate_source_registry(
    record: dict[str, Any], location: str, errors: list[str]
) -> None:
    check_id(record.get("source_record_id"), "source_record_id", location, errors)
    if not record.get("sample_only"):
        errors.append(f"{location}: sample_only must be true")
    if not str(record.get("source_url", "")).endswith(".invalid/attraction/001"):
        errors.append(f"{location}.source_url: sample must use example.invalid")
    if record.get("source_status") not in SOURCE_STATUSES:
        errors.append(f"{location}.source_status: unsupported status")
    check_datetime(record.get("collected_at"), "collected_at", location, errors)
    check_datetime(record.get("retrieved_at"), "retrieved_at", location, errors)
    check_datetime(record.get("valid_until"), "valid_until", location, errors)
    if not record.get("content_hash"):
        errors.append(f"{location}.content_hash: required")


def validate_poi(record: dict[str, Any], location: str, errors: list[str]) -> None:
    check_id(record.get("poi_id"), "poi_id", location, errors)
    if not isinstance(record.get("city_id"), str) or not CITY_PATTERN.fullmatch(
        record["city_id"]
    ):
        errors.append(f"{location}.city_id: must use lowercase snake_case")
    if record.get("coordinate_system") != "GCJ02":
        errors.append(f"{location}.coordinate_system: must be GCJ02")
    longitude = record.get("longitude")
    latitude = record.get("latitude")
    if not isinstance(longitude, (int, float)) or not -180 <= longitude <= 180:
        errors.append(f"{location}.longitude: outside valid range")
    if not isinstance(latitude, (int, float)) or not -90 <= latitude <= 90:
        errors.append(f"{location}.latitude: outside valid range")
    if record.get("poi_category") not in POI_CATEGORIES:
        errors.append(f"{location}.poi_category: unsupported category")
    check_common(record, location, errors)


def validate_attraction(
    record: dict[str, Any], location: str, errors: list[str]
) -> None:
    for field in ("knowledge_id", "poi_id", "chunk_id"):
        check_id(record.get(field), field, location, errors)
    if not record.get("fact_value"):
        errors.append(f"{location}.fact_value: required")
    check_datetime(record.get("valid_until"), "valid_until", location, errors)
    check_common(record, location, errors)


def validate_reservation_rule(
    record: dict[str, Any], location: str, errors: list[str]
) -> None:
    for field in ("policy_id", "poi_id"):
        check_id(record.get(field), field, location, errors)
    if not isinstance(record.get("reservation_required"), bool):
        errors.append(f"{location}.reservation_required: must be boolean")
    if record.get("release_time") is not None and not TIME_PATTERN.fullmatch(
        str(record["release_time"])
    ):
        errors.append(f"{location}.release_time: expected HH:MM")
    for field in ("advance_days", "amount_minor", "stale_after_days"):
        value = record.get(field)
        if value is not None and (not isinstance(value, int) or value < 0):
            errors.append(f"{location}.{field}: must be a non-negative integer")
    if record.get("currency") is not None and (
        not isinstance(record["currency"], str)
        or not re.fullmatch(r"^[A-Z]{3}$", record["currency"])
    ):
        errors.append(f"{location}.currency: expected ISO 4217 code")
    if record.get("stale_after_days") != 30:
        errors.append(f"{location}.stale_after_days: R3 default is 30")
    check_date(record.get("valid_from"), "valid_from", location, errors)
    check_date(record.get("valid_until"), "valid_until", location, errors)
    check_common(record, location, errors)


VALIDATORS = {
    "source_registry": validate_source_registry,
    "poi": validate_poi,
    "attraction": validate_attraction,
    "reservation_rule": validate_reservation_rule,
}


def validate_csv_templates(root: Path, errors: list[str]) -> int:
    template_dir = root / "docs" / "data" / "templates"
    checked = 0
    for filename, expected_header in CSV_TEMPLATES.items():
        path = template_dir / filename
        if not path.is_file():
            errors.append(f"{path}: file is missing")
            continue
        with path.open("r", encoding="utf-8-sig", newline="") as handle:
            rows = list(csv.reader(handle))
        if not rows:
            errors.append(f"{path}: header is missing")
            continue
        if rows[0] != expected_header:
            errors.append(f"{path}: header does not match the R3 dictionary")
        if len(rows) > 1:
            errors.append(f"{path}: template must not contain production rows")
        checked += 1
    return checked


def validate_jsonl_samples(root: Path, errors: list[str]) -> int:
    sample_dir = root / "docs" / "data" / "samples"
    checked = 0
    for filename, kind in SAMPLE_FILES.items():
        path = sample_dir / filename
        if not path.is_file():
            errors.append(f"{path}: file is missing")
            continue
        with path.open("r", encoding="utf-8") as handle:
            lines = [line.strip() for line in handle if line.strip()]
        if not lines:
            errors.append(f"{path}: at least one synthetic sample is required")
            continue
        for line_no, line in enumerate(lines, 1):
            location = f"{path.name}:{line_no}"
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                errors.append(f"{location}: invalid JSON ({exc.msg})")
                continue
            if not isinstance(record, dict):
                errors.append(f"{location}: sample must be a JSON object")
                continue
            VALIDATORS[kind](record, location, errors)
        checked += 1
    return checked


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    errors: list[str] = []
    csv_count = validate_csv_templates(root, errors)
    sample_count = validate_jsonl_samples(root, errors)
    if errors:
        print("R3 template validation failed:")
        for error in errors:
            print(f"- {error}")
        return 1
    print(
        "R3 template validation passed: "
        f"{csv_count} CSV templates and {sample_count} JSONL samples."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
