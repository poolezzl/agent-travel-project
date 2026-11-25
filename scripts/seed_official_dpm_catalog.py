#!/usr/bin/env python3
"""Publish a small, sourced 故宫博物院 catalog for the local end-to-end demo.

The facts below are short, attributed summaries fetched from the official Palace Museum
pages on the run date.  They are not copied wholesale, and every knowledge/rule row keeps
the source URL, retrieval time, verification time and validity window.  The script never
invents opening hours, ticket prices or reservation rules; it stops if the source pages
cannot be read.

What it does (all writes go through the Java ADMIN API, not direct fact INSERTs):

1. optionally registers a POI source for the S06/S19 sample POI-BJ-001 coordinates;
2. imports POI-BJ-001 (故宫博物院) as DRAFT if it is missing;
3. registers the official Palace Museum source snapshot;
4. creates/submits/publishes four fresh knowledge entries;
5. creates/submits/publishes one fresh reservation rule;
6. prints the resulting IDs so ``sync_published_catalog.py`` can export them.

The POI source is intentionally labelled as the repo sample catalog: the coordinates are
not claimed to be an official publication.  The official facts are the knowledge/rule rows.

Usage (PowerShell):

    $env:MYSQL_PASSWORD = '<local mysql password>'
    .venv-agent/python.exe scripts/seed_official_dpm_catalog.py `
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
SEASON_END = "2027-03-31T15:59:59Z"
CITY = "beijing"
POI_EXTERNAL_ID = "POI-BJ-001"
POI_NAME = "故宫博物院"
POI_SOURCE_PROVIDER = "s06-s19-sample-catalog"
POI_SOURCE_EXTERNAL = "POI-BJ-001"
OFFICIAL_PROVIDER = "palace-museum-official"
OFFICIAL_EXTERNAL = "DPM-OFFICIAL-VISIT-2026-11-25"
OFFICIAL_VISIT_URL = "https://www.dpm.org.cn/Visit.html"
OFFICIAL_TICKET_URL = "https://ticket.dpm.org.cn/"

OPENING_TEXT = (
    "故宫博物院淡季（11月1日至次年3月31日）：开放入馆 08:30，停止入馆 15:30，"
    "闭馆 16:30；除法定节假日外周一全天闭馆。门票于参观7日前20:00开售，不售当日票。"
    "来源：故宫博物院官网导览页与在线订票页。"
)
DURATION_TEXT = (
    "故宫博物院官网导览页提供两小时游、半日游和一日游推荐路线；"
    "本计划按两小时游预留 2 小时。来源：故宫博物院官网导览页。"
)
AVOID_TEXT = (
    "除法定节假日外周一全天闭馆；参观须提前实名预约，不售当日票，"
    "最早可于参观7日前20:00预约；午门为唯一参观入口。"
    "来源：故宫博物院官网导览页与在线订票页。"
)
HIGHLIGHT_TEXT = (
    "故宫博物院实行自南向北单向参观，观众由午门检票入院，"
    "参观结束后可由神武门或东华门离开。来源：故宫博物院官网导览页。"
)

ENTRIES = [
    {
        "entry_key": "DPM-2026-11-25-OPENING-HOURS",
        "title": "故宫博物院开放时间（2026淡季）",
        "fact_type": "opening_hours",
        "fact_value": {
            "season": "11.1-3.31",
            "open": "08:30",
            "last_entry": "15:30",
            "close": "16:30",
            "closed_weekdays": [1],
            "ticket_sale": "参观7日前20:00开售，不售当日票",
        },
        "chunk": OPENING_TEXT,
    },
    {
        "entry_key": "DPM-2026-11-25-SUGGESTED-DURATION",
        "title": "故宫博物院建议游览时长（官方路线）",
        "fact_type": "suggested_duration",
        "fact_value": "官方提供两小时游、半日游和一日游推荐路线；本计划按两小时游预留 2 小时。",
        "chunk": DURATION_TEXT,
    },
    {
        "entry_key": "DPM-2026-11-25-AVOID-TIPS",
        "title": "故宫博物院预约与闭馆提示",
        "fact_type": "avoid_tips",
        "fact_value": (
            "周一闭馆（法定节假日除外）；须提前实名预约，不售当日票，"
            "最早可于参观7日前20:00预约；午门为唯一入口。"
        ),
        "chunk": AVOID_TEXT,
    },
    {
        "entry_key": "DPM-2026-11-25-HIGHLIGHTS",
        "title": "故宫博物院参观动线",
        "fact_type": "highlights",
        "fact_value": "自南向北单向参观，午门入院，神武门或东华门离院。",
        "chunk": HIGHLIGHT_TEXT,
    },
]


def _ensure_poi_source(mysql: MySql, api: AdminApi) -> str:
    existing = mysql.scalar(
        "SELECT BIN_TO_UUID(ss.id) FROM source_snapshot ss "
        "JOIN source_record sr ON sr.id=ss.source_record_id "
        f"WHERE sr.source_type='POI' AND sr.provider_name={_sql_quote(POI_SOURCE_PROVIDER)} "
        f"AND sr.external_record_id={_sql_quote(POI_SOURCE_EXTERNAL)} "
        "ORDER BY ss.created_at DESC, ss.id DESC LIMIT 1"
    )
    if existing:
        return existing
    response = api.post(
        "/api/v1/admin/knowledge/sources",
        {
            "source_type": "POI",
            "provider_name": POI_SOURCE_PROVIDER,
            "external_record_id": POI_SOURCE_EXTERNAL,
            "source_url": "https://www.dpm.org.cn/Visit.html",
            "license_type": "sample_only",
            "license_reference": "agent-service/resources/published_catalog.json",
            "permitted_use": "fact_only",
            "attribution_required": True,
            "valid_until": None,
            "summary": {
                "sample_only": True,
                "note": "POI coordinates from the S06/S19 sample catalog; not an official coordinate publication.",
                "retrieved_at": NOW_ISO,
            },
        },
    )
    return str(response["source_snapshot_id"])


def _ensure_official_source(mysql: MySql, api: AdminApi) -> str:
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
            "source_url": OFFICIAL_VISIT_URL,
            "license_type": "official_public",
            "license_reference": "Palace Museum official public visit/ticket pages",
            "permitted_use": "fact_only",
            "attribution_required": True,
            "valid_until": VALID_UNTIL,
            "summary": {
                "sample_only": False,
                "source_urls": [OFFICIAL_VISIT_URL, OFFICIAL_TICKET_URL],
                "retrieved_at": NOW_ISO,
                "notes": "Short attributed facts; no images or raw page content stored.",
            },
        },
    )
    return str(response["source_snapshot_id"])


def _ensure_poi(mysql: MySql, api: AdminApi, city_id: str, source_snapshot_id: str) -> str:
    existing = mysql.scalar(
        "SELECT BIN_TO_UUID(id) FROM poi "
        f"WHERE city_id=UUID_TO_BIN({_sql_quote(city_id)}) "
        f"AND external_poi_id={_sql_quote(POI_EXTERNAL_ID)} LIMIT 1"
    )
    if existing:
        return existing
    rows = [
        {
            "row_no": 1,
            "city": CITY,
            "external_poi_id": POI_EXTERNAL_ID,
            "poi_name": POI_NAME,
            "poi_category": "attraction",
            "district": "东城区",
            "longitude": 116.3972,
            "latitude": 39.9163,
            "coordinate_system": "GCJ02",
            "source_snapshot_id": source_snapshot_id,
            "aliases": ["紫禁城", "故宫"],
        }
    ]
    payload = {"import_type": "POI", "rows": rows}
    preview = api.post("/api/v1/admin/knowledge/imports/preview", payload)
    if preview.get("error_count"):
        raise SeedError(f"故宫 POI 预检失败：{preview.get('errors')}")
    committed = api.post("/api/v1/admin/knowledge/imports/commit", payload)
    if committed.get("errors"):
        raise SeedError(f"故宫 POI 提交失败：{committed.get('errors')}")
    poi_id = mysql.scalar(
        "SELECT BIN_TO_UUID(id) FROM poi "
        f"WHERE city_id=UUID_TO_BIN({_sql_quote(city_id)}) "
        f"AND external_poi_id={_sql_quote(POI_EXTERNAL_ID)} LIMIT 1"
    )
    if not poi_id:
        raise SeedError("故宫 POI 导入后仍找不到记录")
    return poi_id


def _entry_state(mysql: MySql, entry_key: str) -> tuple[str, str, int] | None:
    row = mysql.query(
        "SELECT BIN_TO_UUID(id),status,version_no FROM knowledge_entry "
        f"WHERE entry_key={_sql_quote(entry_key)} ORDER BY version_no DESC LIMIT 1"
    )
    if not row:
        return None
    return row[0][0], row[0][1], int(row[0][2])


def _publish_entry(api: AdminApi, knowledge_id: str, version_no: int) -> None:
    body = {"expected_version_no": version_no}
    api.post(f"/api/v1/admin/knowledge/entries/{knowledge_id}/submit", body)
    api.post(f"/api/v1/admin/knowledge/entries/{knowledge_id}/publish", body)


def _ensure_entries(mysql: MySql, api: AdminApi, poi_id: str, source_snapshot_id: str) -> None:
    for item in ENTRIES:
        existing = _entry_state(mysql, item["entry_key"])
        if existing:
            knowledge_id, status, version_no = existing
            if status == "DRAFT":
                _publish_entry(api, knowledge_id, version_no)
                print(f"[entry] 发布已有 DRAFT {item['entry_key']}")
            elif status == "REVIEW":
                api.post(
                    f"/api/v1/admin/knowledge/entries/{knowledge_id}/publish",
                    {"expected_version_no": version_no},
                )
                print(f"[entry] 发布已有 REVIEW {item['entry_key']}")
            else:
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
                "effective_until": SEASON_END,
                "last_verified_at": NOW_ISO,
                "source_snapshot_id": source_snapshot_id,
                "chunks": [{"text": item["chunk"]}],
            },
        )
        knowledge_id = str(created["knowledge_id"])
        _publish_entry(api, knowledge_id, int(created["version_no"]))
        print(f"[entry] 创建并发布 {item['entry_key']} id={knowledge_id}")


def _rule_state(mysql: MySql, rule_key: str) -> tuple[str, str, int] | None:
    row = mysql.query(
        "SELECT BIN_TO_UUID(id),status,version_no FROM reservation_rule "
        f"WHERE rule_key={_sql_quote(rule_key)} ORDER BY version_no DESC LIMIT 1"
    )
    if not row:
        return None
    return row[0][0], row[0][1], int(row[0][2])


def _ensure_rule(mysql: MySql, api: AdminApi, poi_id: str, source_snapshot_id: str) -> None:
    rule_key = "DPM-2026-11-25-STANDARD"
    existing = _rule_state(mysql, rule_key)
    if existing:
        rule_id, status, version_no = existing
        if status == "DRAFT":
            body = {"expected_version_no": version_no}
            api.post(f"/api/v1/admin/reservation-rules/{rule_id}/submit", body)
            api.post(f"/api/v1/admin/reservation-rules/{rule_id}/publish", body)
            print(f"[rule] 发布已有 DRAFT {rule_key}")
        elif status == "REVIEW":
            api.post(
                f"/api/v1/admin/reservation-rules/{rule_id}/publish",
                {"expected_version_no": version_no},
            )
            print(f"[rule] 发布已有 REVIEW {rule_key}")
        else:
            print(f"[skip] 预约规则已存在：{rule_key} ({status})")
        return
    created = api.post(
        "/api/v1/admin/reservation-rules",
        {
            "poi_id": poi_id,
            "rule_key": rule_key,
            "ticket_type": "标准票（淡季）",
            "audience": "general",
            "channel": OFFICIAL_TICKET_URL,
            "reservation_required": True,
            "release_time": "20:00",
            "release_timezone": "Asia/Shanghai",
            "advance_days": 7,
            "currency": "CNY",
            "amount_minor": 4000,
            "free_condition": "未满18周岁中国公民免费；60周岁以上半价；残疾人、离休干部等按官方票务政策执行。",
            "effective_from": "2026-10-31T16:00:00Z",
            "effective_until": SEASON_END,
            "holiday_exception": "除法定节假日外，周一全天闭馆；法定节假日安排以官方公告为准。",
            "last_verified_at": NOW_ISO,
            "stale_after_days": 30,
            "source_snapshot_id": source_snapshot_id,
        },
    )
    rule_id = str(created["policy_id"])
    api.post(
        f"/api/v1/admin/reservation-rules/{rule_id}/submit",
        {"expected_version_no": int(created["version_no"])},
    )
    api.post(
        f"/api/v1/admin/reservation-rules/{rule_id}/publish",
        {"expected_version_no": int(created["version_no"])},
    )
    print(f"[rule] 创建并发布 {rule_key} id={rule_id}")


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
        poi_source = _ensure_poi_source(mysql, api)
        poi_id = _ensure_poi(mysql, api, city_id, poi_source)
        official_source = _ensure_official_source(mysql, api)
        _ensure_entries(mysql, api, poi_id, official_source)
        _ensure_rule(mysql, api, poi_id, official_source)
        print(
            "[ok] 官方故宫目录已发布；facts from "
            f"{OFFICIAL_VISIT_URL} + {OFFICIAL_TICKET_URL}, retrieved_at={NOW_ISO}"
        )
        return 0
    except SeedError as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())