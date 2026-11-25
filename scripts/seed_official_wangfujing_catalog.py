#!/usr/bin/env python3
"""Publish a small, sourced 王府井大街 catalog for the 2026-11-26 demo day.

Why this POI: the Palace Museum requires booking seven days ahead, so a task generated on
2026-11-25 for 2026-11-26 has already missed the official booking window.  王府井大街 is an
official Beijing tourism page's free, all-day attraction with no reservation requirement,
so it can legitimately produce a clean single-day plan for the requested date.  The source
facts are from 北京旅游网 (北京市文化和旅游局宣传中心) and the POI coordinates are from
the AMap Web Service place-text endpoint; no value is invented.

All fact writes go through the Java ADMIN API.  The script is idempotent by source/external
id, entry_key and rule_key.

Usage:

    $env:MYSQL_PASSWORD = '<local mysql password>'
    .venv-agent/python.exe scripts/seed_official_wangfujing_catalog.py `
        --login <ADMIN login> --password '<ADMIN password>'
"""

from __future__ import annotations

import argparse
import os
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from seed_demo_catalog import AdminApi, MySql, SeedError, _sql_quote  # noqa: E402

NOW = datetime.now(UTC)
NOW_ISO = NOW.isoformat().replace("+00:00", "Z")
VALID_UNTIL = (NOW + timedelta(days=30)).isoformat().replace("+00:00", "Z")
CITY = "beijing"
POI_ID = "POI-BJ-WFJ-001"
POI_NAME = "王府井大街"
OFFICIAL_URL = "https://s.visitbeijing.com.cn/attraction/101739"
OFFICIAL_PROVIDER = "visitbeijing-official"
OFFICIAL_EXTERNAL = "VISITBEIJING-WFJ-101739-2026-11-25"
POI_PROVIDER = "amap-place-text"
POI_EXTERNAL = "AMAP-WFJ-001"

OPENING_TEXT = (
    "王府井大街开放时间：全天；门票价格：无；最佳游览时间：全年。"
    "来源：北京旅游网（北京市文化和旅游局宣传中心）王府井大街景区页。"
)
DURATION_TEXT = (
    "北京旅游网王府井大街景区页推荐游览时长：2小时。"
    "来源：北京旅游网（北京市文化和旅游局宣传中心）。"
)
HIGHLIGHT_TEXT = (
    "王府井大街南起东长安街，北至中国美术馆，全长约1600米，是北京有名的商业区。"
    "来源：北京旅游网（北京市文化和旅游局宣传中心）王府井大街景区页。"
)

ENTRIES = [
    {
        "entry_key": "VISITBEIJING-WFJ-OPENING-HOURS",
        "title": "王府井大街开放时间与门票",
        "fact_type": "opening_hours",
        "fact_value": {"open": "全天", "ticket": "免费", "best_season": "全年"},
        "chunk": OPENING_TEXT,
    },
    {
        "entry_key": "VISITBEIJING-WFJ-SUGGESTED-DURATION",
        "title": "王府井大街推荐游览时长",
        "fact_type": "suggested_duration",
        "fact_value": "北京旅游网推荐游览时长 2 小时。",
        "chunk": DURATION_TEXT,
    },
    {
        "entry_key": "VISITBEIJING-WFJ-HIGHLIGHTS",
        "title": "王府井大街简介",
        "fact_type": "highlights",
        "fact_value": "南起东长安街，北至中国美术馆，全长约1600米，是北京有名的商业区。",
        "chunk": HIGHLIGHT_TEXT,
    },
]


def _source_snapshot(mysql: MySql, api: AdminApi) -> str:
    existing = mysql.scalar(
        "SELECT BIN_TO_UUID(ss.id) FROM source_snapshot ss "
        "JOIN source_record sr ON sr.id=ss.source_record_id "
        f"WHERE sr.source_type='KNOWLEDGE_BASE' AND sr.provider_name={_sql_quote(OFFICIAL_PROVIDER)} "
        f"AND sr.external_record_id={_sql_quote(OFFICIAL_EXTERNAL)} "
        "ORDER BY ss.created_at DESC, ss.id DESC LIMIT 1"
    )
    if existing:
        return existing
    response = api.post(
        "/api/v1/admin/knowledge/sources",
        {
            "source_type": "KNOWLEDGE_BASE",
            "provider_name": OFFICIAL_PROVIDER,
            "external_record_id": OFFICIAL_EXTERNAL,
            "source_url": OFFICIAL_URL,
            "license_type": "official_public",
            "license_reference": "北京旅游网 王府井大街景区页",
            "permitted_use": "fact_only",
            "attribution_required": True,
            "valid_until": VALID_UNTIL,
            "summary": {
                "sample_only": False,
                "source_urls": [OFFICIAL_URL],
                "retrieved_at": NOW_ISO,
                "notes": "Short attributed facts; no images or raw page content stored.",
            },
        },
    )
    return str(response["source_snapshot_id"])


def _poi_source_snapshot(mysql: MySql, api: AdminApi) -> str:
    existing = mysql.scalar(
        "SELECT BIN_TO_UUID(ss.id) FROM source_snapshot ss "
        "JOIN source_record sr ON sr.id=ss.source_record_id "
        f"WHERE sr.source_type='POI' AND sr.provider_name={_sql_quote(POI_PROVIDER)} "
        f"AND sr.external_record_id={_sql_quote(POI_EXTERNAL)} "
        "ORDER BY ss.created_at DESC, ss.id DESC LIMIT 1"
    )
    if existing:
        return existing
    response = api.post(
        "/api/v1/admin/knowledge/sources",
        {
            "source_type": "POI",
            "provider_name": POI_PROVIDER,
            "external_record_id": POI_EXTERNAL,
            "source_url": "https://restapi.amap.com/v3/place/text",
            "license_type": "AMAP_TERMS",
            "license_reference": "AMap Web Service API terms",
            "permitted_use": "fact_only",
            "attribution_required": True,
            "valid_until": VALID_UNTIL,
            "summary": {
                "sample_only": False,
                "query": "王府井大街",
                "city": "北京",
                "derived_fields": ["name", "location", "adname"],
                "retrieved_at": NOW_ISO,
                "note": "Derived POI name and GCJ02 coordinates only; no raw supplier response stored.",
            },
        },
    )
    return str(response["source_snapshot_id"])


def _ensure_poi(mysql: MySql, api: AdminApi, city_id: str, source_snapshot_id: str) -> str:
    existing = mysql.scalar(
        "SELECT BIN_TO_UUID(id) FROM poi "
        f"WHERE city_id=UUID_TO_BIN({_sql_quote(city_id)}) "
        f"AND external_poi_id={_sql_quote(POI_ID)} LIMIT 1"
    )
    if existing:
        return existing
    rows = [
        {
            "row_no": 1,
            "city": CITY,
            "external_poi_id": POI_ID,
            "poi_name": POI_NAME,
            "poi_category": "attraction",
            "district": "东城区",
            "longitude": 116.411122,
            "latitude": 39.916176,
            "coordinate_system": "GCJ02",
            "source_snapshot_id": source_snapshot_id,
            "aliases": ["王府井步行街"],
        }
    ]
    payload = {"import_type": "POI", "rows": rows}
    preview = api.post("/api/v1/admin/knowledge/imports/preview", payload)
    if preview.get("error_count"):
        raise SeedError(f"王府井 POI 预检失败：{preview.get('errors')}")
    committed = api.post("/api/v1/admin/knowledge/imports/commit", payload)
    if committed.get("errors"):
        raise SeedError(f"王府井 POI 提交失败：{committed.get('errors')}")
    poi_id = mysql.scalar(
        "SELECT BIN_TO_UUID(id) FROM poi "
        f"WHERE city_id=UUID_TO_BIN({_sql_quote(city_id)}) "
        f"AND external_poi_id={_sql_quote(POI_ID)} LIMIT 1"
    )
    if not poi_id:
        raise SeedError("王府井 POI 导入后仍找不到记录")
    return poi_id


def _entry_state(mysql: MySql, key: str) -> tuple[str, str, int] | None:
    rows = mysql.query(
        "SELECT BIN_TO_UUID(id),status,version_no FROM knowledge_entry "
        f"WHERE entry_key={_sql_quote(key)} ORDER BY version_no DESC LIMIT 1"
    )
    return (rows[0][0], rows[0][1], int(rows[0][2])) if rows else None


def _publish_entry(api: AdminApi, entry_id: str, version: int) -> None:
    body = {"expected_version_no": version}
    api.post(f"/api/v1/admin/knowledge/entries/{entry_id}/submit", body)
    api.post(f"/api/v1/admin/knowledge/entries/{entry_id}/publish", body)


def _ensure_entries(mysql: MySql, api: AdminApi, poi_id: str, snapshot: str) -> None:
    for item in ENTRIES:
        existing = _entry_state(mysql, item["entry_key"])
        if existing:
            entry_id, status, version = existing
            if status == "DRAFT":
                _publish_entry(api, entry_id, version)
            elif status == "REVIEW":
                api.post(
                    f"/api/v1/admin/knowledge/entries/{entry_id}/publish",
                    {"expected_version_no": version},
                )
            print(f"[skip] 知识已存在：{item['entry_key']} ({status})")
            continue
        created = api.post(
            "/api/v1/admin/knowledge/entries",
            {
                "entry_key": item["entry_key"],
                "poi_id": poi_id,
                "city": CITY,
                "title": item["title"],
                "fact_type": item["fact_type"],
                "fact_value": item["fact_value"],
                "effective_from": NOW_ISO,
                "effective_until": None,
                "last_verified_at": NOW_ISO,
                "source_snapshot_id": snapshot,
                "chunks": [{"text": item["chunk"]}],
            },
        )
        _publish_entry(api, str(created["knowledge_id"]), int(created["version_no"]))
        print(f"[entry] 创建并发布 {item['entry_key']}")


def _ensure_rule(mysql: MySql, api: AdminApi, poi_id: str, snapshot: str) -> None:
    rule_key = "VISITBEIJING-WFJ-2026-11-25-FREE"
    rows = mysql.query(
        "SELECT BIN_TO_UUID(id),status,version_no FROM reservation_rule "
        f"WHERE rule_key={_sql_quote(rule_key)} ORDER BY version_no DESC LIMIT 1"
    )
    if rows:
        rule_id, status, version = rows[0][0], rows[0][1], int(rows[0][2])
        if status == "DRAFT":
            body = {"expected_version_no": version}
            api.post(f"/api/v1/admin/reservation-rules/{rule_id}/submit", body)
            api.post(f"/api/v1/admin/reservation-rules/{rule_id}/publish", body)
        elif status == "REVIEW":
            api.post(
                f"/api/v1/admin/reservation-rules/{rule_id}/publish",
                {"expected_version_no": version},
            )
        print(f"[skip] 规则已存在：{rule_key} ({status})")
        return
    created = api.post(
        "/api/v1/admin/reservation-rules",
        {
            "poi_id": poi_id,
            "rule_key": rule_key,
            "ticket_type": "免费开放",
            "audience": "general",
            "channel": OFFICIAL_URL,
            "reservation_required": False,
            "release_time": None,
            "release_timezone": "Asia/Shanghai",
            "advance_days": 0,
            "currency": None,
            "amount_minor": None,
            "free_condition": "官方页面标注门票价格：无",
            "effective_from": NOW_ISO,
            "effective_until": None,
            "holiday_exception": "开放时间：全天；具体以官方页面为准",
            "last_verified_at": NOW_ISO,
            "stale_after_days": 30,
            "source_snapshot_id": snapshot,
        },
    )
    rule_id = str(created["policy_id"])
    version = int(created["version_no"])
    api.post(
        f"/api/v1/admin/reservation-rules/{rule_id}/submit",
        {"expected_version_no": version},
    )
    api.post(
        f"/api/v1/admin/reservation-rules/{rule_id}/publish",
        {"expected_version_no": version},
    )
    print(f"[rule] 创建并发布 {rule_key}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("SEED_API_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--login", default=os.environ.get("SEED_ADMIN_LOGIN", ""))
    parser.add_argument("--password", default=os.environ.get("SEED_ADMIN_PASSWORD", ""))
    args = parser.parse_args(argv)
    if not args.login or not args.password:
        parser.error("必须提供 --login/--password 或 SEED_ADMIN_LOGIN/SEED_ADMIN_PASSWORD")
    try:
        mysql = MySql()
        city_id = mysql.scalar(
            f"SELECT BIN_TO_UUID(id) FROM city WHERE city_code={_sql_quote(CITY)} LIMIT 1"
        )
        if not city_id:
            raise SeedError("找不到 beijing 城市；请先运行 seed_demo_catalog.py")
        api = AdminApi(args.base_url, args.login, args.password)
        poi_source = _poi_source_snapshot(mysql, api)
        poi_id = _ensure_poi(mysql, api, city_id, poi_source)
        official_source = _source_snapshot(mysql, api)
        _ensure_entries(mysql, api, poi_id, official_source)
        _ensure_rule(mysql, api, poi_id, official_source)
        print(
            "[ok] 官方王府井目录已发布；"
            f"source={OFFICIAL_URL}, retrieved_at={NOW_ISO}"
        )
        return 0
    except SeedError as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())