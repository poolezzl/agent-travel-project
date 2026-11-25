#!/usr/bin/env python3
"""Idempotently import the S06/S15 sample catalog into the local trip_planner DB.

This is a demo/local-verification helper, not an S06/S15 deliverable.  It reads the
synthetic JSONL samples under ``docs/data/samples/`` and imports them through the
existing ADMIN knowledge API (``/api/v1/admin/knowledge/...``):

* ``source_registry_sample.jsonl``  -> ``POST /sources``
* ``poi_sample.jsonl``              -> ``imports/preview`` + ``imports/commit`` (POI)
* ``attraction_sample.jsonl``       -> ``imports/preview`` + ``imports/commit`` (ATTRACTION_KNOWLEDGE)
* ``reservation_rule_sample.jsonl`` -> ``imports/preview`` + ``imports/commit`` (RESERVATION_RULE)

The samples stay DRAFT: this script never calls submit/publish and never changes the
review status that the samples declare.  It preserves every original id (POI sample id,
knowledge id, policy id and source record id) and puts the original source annotations
into the source record summary.

Idempotency: existing city/source/POI/knowledge/rule rows are detected before any write;
a second run therefore performs no insert and creates no new import batch.  The MySQL
CLI is used read-only for those existence checks and for the city registry bootstrap
(the platform has no city write API).  Facts themselves are only written through the
Java ADMIN API.

Example (PowerShell):

    $env:MYSQL_PASSWORD = '<local mysql password>'
    .venv-agent/python.exe scripts/seed_demo_catalog.py `
        --login seed-admin@example.com --password '<admin password>'

Environment variables:
    MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DATABASE
    MYSQL_CLI (optional absolute path to mysql.exe)
    SEED_API_BASE_URL (default http://127.0.0.1:8080)
    SEED_ADMIN_LOGIN / SEED_ADMIN_PASSWORD (fallbacks for --login / --password)
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import uuid
from datetime import date, datetime, time, timezone
from pathlib import Path
from typing import Any, Iterable

import requests

ROOT = Path(__file__).resolve().parents[1]
SAMPLE_DIR = ROOT / "docs" / "data" / "samples"
CITY_CODE = "beijing"
CITY_NAME = "北京"
CITY_REGION = "华北"
CITY_TIMEZONE = "Asia/Shanghai"
SOURCE_PROVIDER = "s06-sample-registry"
SOURCE_TYPE = "KNOWLEDGE_BASE"
SOURCE_EXTERNAL_ID = "SRC-EXAMPLE-001"
SOURCE_EXTRA = {
    "sample_only": True,
    "source_status": "approved",
    "content_hash": "sha256:sample-source-001",
    "retrieved_at": "2026-09-20T08:00:00+08:00",
    "operator_id": "operator-sample",
    "reviewer_id": "reviewer-sample",
    "reviewed_at": "2026-09-20T09:00:00+08:00",
    "notes": "Synthetic sample only; not a real source.",
}


class SeedError(RuntimeError):
    """A deterministic seed failure with a user-actionable message."""


# --------------------------------------------------------------------------- SQL


def _sql_quote(value: Any) -> str:
    if value is None:
        return "NULL"
    text = str(value)
    return "'" + text.replace("\\", "\\\\").replace("'", "''") + "'"


class MySql:
    """Tiny read-only helper around the MySQL CLI.

    stdout/stderr are redirected to files instead of pipes so the script also works in
    restricted Windows sandboxes that reject named-pipe based subprocess capture.
    """

    def __init__(self) -> None:
        self.cli = os.environ.get("MYSQL_CLI") or shutil.which("mysql") or (
            r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
        )
        if not Path(self.cli).is_file():
            raise SeedError(
                "找不到 mysql 客户端；请通过 MYSQL_CLI 指定 mysql.exe 的绝对路径。"
            )
        self.host = os.environ.get("MYSQL_HOST", "127.0.0.1")
        self.port = os.environ.get("MYSQL_PORT", "3306")
        self.user = os.environ.get("MYSQL_USER", "root")
        self.password = os.environ.get("MYSQL_PASSWORD", "")
        self.database = os.environ.get("MYSQL_DATABASE", "trip_planner")

    def query(self, sql: str) -> list[list[str]]:
        env = os.environ.copy()
        env["MYSQL_PWD"] = self.password
        cmd = [
            self.cli,
            f"--host={self.host}",
            f"--port={self.port}",
            f"--user={self.user}",
            f"--database={self.database}",
            "--batch",
            "--raw",
            "--skip-column-names",
            "--default-character-set=utf8mb4",
            "--connect-timeout=5",
            f"--execute={sql}",
        ]
        completed = subprocess.run(
            cmd, capture_output=True, text=True, encoding="utf-8", env=env, timeout=60, check=False
        )
        if completed.returncode != 0:
            raise SeedError(f"MySQL 查询失败：{completed.stderr.strip() or completed.returncode}")
        rows: list[list[str]] = []
        for line in completed.stdout.splitlines():
            if not line:
                continue
            rows.append(line.split("\t"))
        return rows

    def scalar(self, sql: str) -> str | None:
        rows = self.query(sql)
        if not rows or not rows[0]:
            return None
        return rows[0][0]


# ------------------------------------------------------------------------- utils


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise SeedError(f"缺少样例文件：{path}")
    items: list[dict[str, Any]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        text = line.strip()
        if not text or text.startswith("#"):
            continue
        try:
            value = json.loads(text)
        except json.JSONDecodeError as exc:
            raise SeedError(f"{path.name}:{number} 不是合法 JSON：{exc}") from exc
        if not isinstance(value, dict):
            raise SeedError(f"{path.name}:{number} 必须是 JSON 对象")
        items.append(value)
    if not items:
        raise SeedError(f"样例文件为空：{path}")
    return items


def _instant(value: str | None, *, end_of_day: bool = False) -> str | None:
    """Normalise the sample timestamps to UTC ISO-8601 strings."""
    if value is None:
        return None
    text = value.strip()
    if len(text) == 10:
        day = date.fromisoformat(text)
        moment = (
            datetime.combine(day, time(23, 59, 59), tzinfo=timezone.utc)
            if end_of_day
            else datetime.combine(day, time(0, 0, 0), tzinfo=timezone.utc)
        )
        return moment.isoformat().replace("+00:00", "Z")
    parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise SeedError(f"样例时间缺少时区：{value}")
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _city_uuid() -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"travelstar:city:{CITY_CODE}"))


# --------------------------------------------------------------------------- API


class AdminApi:
    def __init__(self, base_url: str, login: str, password: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        token = self._login(login, password)
        self.session.headers.update({"Authorization": f"Bearer {token}"})

    def _login(self, login: str, password: str) -> str:
        response = self.session.post(
            f"{self.base_url}/api/v1/auth/login",
            json={"login": login, "password": password},
            timeout=20,
        )
        if response.status_code != 200:
            raise SeedError(
                "管理员登录失败；请确认账号已通过 SQL 提升为 ADMIN，且密码正确。"
                f" HTTP {response.status_code}"
            )
        body = response.json()
        if body.get("role") != "ADMIN":
            raise SeedError("该账号不是 ADMIN；请先按任务说明用 SQL 更新 user_account.role。")
        return str(body["accessToken"])

    def post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        response = self.session.post(f"{self.base_url}{path}", json=payload, timeout=60)
        if response.status_code >= 400:
            detail = response.text[:500]
            raise SeedError(f"POST {path} 失败：HTTP {response.status_code} {detail}")
        return response.json()


# --------------------------------------------------------------------------- seed


def _ensure_city(mysql: MySql) -> str:
    city_id = mysql.scalar(
        f"SELECT BIN_TO_UUID(id) FROM city WHERE city_code={_sql_quote(CITY_CODE)} LIMIT 1"
    )
    if city_id:
        return city_id
    mysql.query(
        "INSERT INTO city(id,city_code,name,region_name,timezone,status,created_at,updated_at) "
        f"VALUES(UUID_TO_BIN({_sql_quote(_city_uuid())}),{_sql_quote(CITY_CODE)},"
        f"{_sql_quote(CITY_NAME)},{_sql_quote(CITY_REGION)},{_sql_quote(CITY_TIMEZONE)},"
        "'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)) "
        "ON DUPLICATE KEY UPDATE city_code=VALUES(city_code)"
    )
    city_id = mysql.scalar(
        f"SELECT BIN_TO_UUID(id) FROM city WHERE city_code={_sql_quote(CITY_CODE)} LIMIT 1"
    )
    if not city_id:
        raise SeedError("城市登记失败：数据库中仍找不到 beijing。")
    print(f"[city] 已登记 {CITY_CODE} ({CITY_NAME}) id={city_id}")
    return city_id


def _source_snapshot_id(mysql: MySql, api: AdminApi) -> str:
    existing = mysql.scalar(
        "SELECT BIN_TO_UUID(ss.id) FROM source_snapshot ss "
        "JOIN source_record sr ON sr.id=ss.source_record_id "
        f"WHERE sr.source_type={_sql_quote(SOURCE_TYPE)} "
        f"AND sr.provider_name={_sql_quote(SOURCE_PROVIDER)} "
        f"AND sr.external_record_id={_sql_quote(SOURCE_EXTERNAL_ID)} "
        "ORDER BY ss.created_at DESC, ss.id DESC LIMIT 1"
    )
    if existing:
        print(f"[source] 复用已有来源快照 {existing}")
        return existing

    source = _read_jsonl(SAMPLE_DIR / "source_registry_sample.jsonl")[0]
    summary = {
        **SOURCE_EXTRA,
        "origin_file": "docs/data/samples/source_registry_sample.jsonl",
        "sample_source_record_id": source.get("source_record_id"),
        "sample_freshness_status": source.get("freshness_status"),
        "sample_reviewed_at": source.get("reviewed_at"),
        "license_type": source.get("license_type"),
        "license_reference": source.get("license_reference"),
        "permitted_use": source.get("permitted_use"),
    }
    response = api.post(
        "/api/v1/admin/knowledge/sources",
        {
            "source_type": SOURCE_TYPE,
            "provider_name": SOURCE_PROVIDER,
            "external_record_id": SOURCE_EXTERNAL_ID,
            "source_url": source.get("source_url"),
            "license_type": source.get("license_type") or "unknown",
            "license_reference": source.get("license_reference"),
            "permitted_use": source.get("permitted_use"),
            "attribution_required": bool(source.get("attribution_required")),
            "valid_until": _instant(source.get("valid_until")),
            "summary": summary,
        },
    )
    snapshot_id = str(response["source_snapshot_id"])
    print(f"[source] 已登记来源 {SOURCE_EXTERNAL_ID} snapshot={snapshot_id}")
    return snapshot_id


def _existing_map(mysql: MySql, city_id: str, table: str, key_column: str,
                  keys: Iterable[str], *, join_poi: bool = False) -> dict[str, str]:
    values = sorted({key for key in keys if key})
    if not values:
        return {}
    quoted = ",".join(_sql_quote(value) for value in values)
    if join_poi:
        # reservation_rule has no city_id; its city is the owning POI's city.
        sql = (
            "SELECT r.rule_key,BIN_TO_UUID(r.id) FROM reservation_rule r "
            "JOIN poi p ON p.id=r.poi_id "
            f"WHERE p.city_id=UUID_TO_BIN({_sql_quote(city_id)}) "
            f"AND r.rule_key IN ({quoted}) "
            "ORDER BY r.rule_key,r.id"
        )
    else:
        sql = (
            f"SELECT {key_column},BIN_TO_UUID(id) FROM {table} "
            f"WHERE city_id=UUID_TO_BIN({_sql_quote(city_id)}) "
            f"AND {key_column} IN ({quoted}) "
            f"ORDER BY {key_column},id"
        )
    rows = mysql.query(sql)
    result: dict[str, str] = {}
    for row in rows:
        if len(row) >= 2 and row[0] not in result:
            result[row[0]] = row[1]
    return result


def _preview_and_commit(api: AdminApi, import_type: str, rows: list[dict[str, Any]]) -> dict[str, Any]:
    payload = {"import_type": import_type, "rows": rows}
    preview = api.post("/api/v1/admin/knowledge/imports/preview", payload)
    if preview.get("error_count") or preview.get("valid_count") != len(rows):
        raise SeedError(
            f"{import_type} 预检未通过：{json.dumps(preview.get('errors'), ensure_ascii=False)}"
        )
    committed = api.post("/api/v1/admin/knowledge/imports/commit", payload)
    if committed.get("errors"):
        raise SeedError(
            f"{import_type} 提交失败：{json.dumps(committed.get('errors'), ensure_ascii=False)}"
        )
    print(f"[import] {import_type}: preview={preview['valid_count']} rows, "
          f"commit={committed.get('created_count')} created")
    return committed


def _poi_rows(pois: list[dict[str, Any]], snapshot_id: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for index, poi in enumerate(pois, start=1):
        rows.append(
            {
                "row_no": index,
                "city": CITY_CODE,
                "external_poi_id": poi["poi_id"],
                "poi_name": poi["name"],
                "poi_category": poi.get("poi_category"),
                "district": poi.get("district"),
                "longitude": poi.get("longitude"),
                "latitude": poi.get("latitude"),
                "coordinate_system": poi.get("coordinate_system") or "GCJ02",
                "source_snapshot_id": snapshot_id,
                "aliases": list(poi.get("alias_names") or []),
            }
        )
    return rows


def _knowledge_row(knowledge: dict[str, Any], poi_id: str, poi_name: str,
                   snapshot_id: str) -> dict[str, Any]:
    text = str(knowledge.get("fact_value"))
    return {
        "row_no": 1,
        "city": CITY_CODE,
        "poi_id": poi_id,
        "poi_name": poi_name,
        "entry_key": knowledge["knowledge_id"],
        "title": f"{poi_name}景点资讯（合成样例）",
        "fact_type": knowledge["fact_type"],
        "fact_value": text,
        "effective_from": _instant(knowledge.get("published_at")),
        "effective_until": _instant(knowledge.get("valid_until"), end_of_day=True),
        "last_verified_at": _instant(knowledge.get("last_verified_at")),
        "source_snapshot_id": snapshot_id,
        "chunks": [text],
    }


def _rule_row(rule: dict[str, Any], poi_id: str, poi_name: str,
              snapshot_id: str) -> dict[str, Any]:
    return {
        "row_no": 1,
        "city": CITY_CODE,
        "poi_id": poi_id,
        "poi_name": poi_name,
        "rule_key": rule["policy_id"],
        "ticket_type": rule["ticket_type"],
        "audience": rule.get("applicable_group"),
        "reservation_required": bool(rule.get("reservation_required")),
        "channel": rule.get("reservation_channel"),
        "release_time": rule.get("release_time"),
        "release_timezone": rule.get("release_timezone"),
        "advance_days": rule.get("advance_days"),
        "currency": rule.get("currency"),
        "amount_minor": rule.get("amount_minor"),
        "free_condition": rule.get("free_condition"),
        "effective_from": _instant(rule.get("valid_from")),
        "effective_until": _instant(rule.get("valid_until"), end_of_day=True),
        "holiday_exception": rule.get("holiday_exception"),
        "last_verified_at": _instant(rule.get("last_verified_at")),
        "stale_after_days": rule.get("stale_after_days"),
        "source_snapshot_id": snapshot_id,
    }


def seed(args: argparse.Namespace) -> int:
    mysql = MySql()
    city_id = _ensure_city(mysql)
    api = AdminApi(args.base_url, args.login, args.password)
    snapshot_id = _source_snapshot_id(mysql, api)

    pois = _read_jsonl(SAMPLE_DIR / "poi_sample.jsonl")
    knowledge = _read_jsonl(SAMPLE_DIR / "attraction_sample.jsonl")[0]
    rule = _read_jsonl(SAMPLE_DIR / "reservation_rule_sample.jsonl")[0]

    poi_keys = {str(poi["poi_id"]) for poi in pois}
    existing_pois = _existing_map(mysql, city_id, "poi", "external_poi_id", poi_keys)
    missing = [poi for poi in pois if str(poi["poi_id"]) not in existing_pois]
    if missing:
        _preview_and_commit(api, "POI", _poi_rows(missing, snapshot_id))
        existing_pois = _existing_map(mysql, city_id, "poi", "external_poi_id", poi_keys)
    else:
        print(f"[skip] POI 已全部存在：{len(existing_pois)} 条")

    target_poi = f"POI-BJ-EXAMPLE-001"
    if target_poi not in existing_pois:
        raise SeedError(f"无法解析景点 POI：{target_poi}")
    poi_id = existing_pois[target_poi]
    poi_name = next(str(poi["name"]) for poi in pois if str(poi["poi_id"]) == target_poi)

    knowledge_key = str(knowledge["knowledge_id"])
    existing_knowledge = _existing_map(
        mysql, city_id, "knowledge_entry", "entry_key", [knowledge_key]
    )
    if knowledge_key in existing_knowledge:
        print(f"[skip] 景点知识已存在：{knowledge_key}")
    else:
        _preview_and_commit(
            api, "ATTRACTION_KNOWLEDGE",
            [_knowledge_row(knowledge, poi_id, poi_name, snapshot_id)],
        )

    rule_key = str(rule["policy_id"])
    existing_rule = _existing_map(
        mysql, city_id, "reservation_rule", "rule_key", [rule_key], join_poi=True
    )
    if rule_key in existing_rule:
        print(f"[skip] 预约规则已存在：{rule_key}")
    else:
        _preview_and_commit(
            api, "RESERVATION_RULE",
            [_rule_row(rule, poi_id, poi_name, snapshot_id)],
        )

    counts = {}
    for table in (
        "poi",
        "poi_alias",
        "poi_snapshot",
        "knowledge_entry",
        "knowledge_chunk",
        "reservation_rule",
        "source_record",
        "source_snapshot",
        "import_batch",
    ):
        counts[table] = mysql.scalar(f"SELECT COUNT(*) FROM {table}")
    print("[counts] " + " ".join(f"{name}={value}" for name, value in counts.items()))
    print("[ok] 样例目录已导入；重复执行不会新增行。")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--base-url",
        default=os.environ.get("SEED_API_BASE_URL", "http://127.0.0.1:8080"),
        help="Java backend base URL (default: http://127.0.0.1:8080)",
    )
    parser.add_argument(
        "--login",
        default=os.environ.get("SEED_ADMIN_LOGIN", ""),
        help="ADMIN login (or SEED_ADMIN_LOGIN)",
    )
    parser.add_argument(
        "--password",
        default=os.environ.get("SEED_ADMIN_PASSWORD", ""),
        help="ADMIN password (or SEED_ADMIN_PASSWORD)",
    )
    args = parser.parse_args(argv)
    if not args.login or not args.password:
        parser.error("必须提供 --login/--password 或 SEED_ADMIN_LOGIN/SEED_ADMIN_PASSWORD")
    if not args.password.strip():
        parser.error("密码不能为空")
    try:
        return seed(args)
    except SeedError as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())