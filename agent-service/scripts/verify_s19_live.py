"""Live S19 check: real Amap route legs and (optionally) real QWeather.

Usage (PowerShell, values never committed):

    $env:ROUTE_PROVIDER='amap'
    $env:AMAP_API_KEY='...'
    $env:AMAP_TIMEOUT_MS='5000'
    python scripts/verify_s19_live.py

The script prints only status/source summaries; it never prints keys or raw provider
responses. Exit code 1 means the DAG produced no exact map leg.
"""

from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from app.orchestrator.runtime import build_default_deps
from app.orchestrator.service import run_stage

STEPS = ("parse", "collect", "schedule", "route", "assemble")


def main() -> int:
    if not os.environ.get("AMAP_API_KEY"):
        print("AMAP_API_KEY is required for live verification")
        return 2
    # +1 keeps the trip inside the default 3-day QWeather forecast window.
    start = datetime.now(ZoneInfo("Asia/Shanghai")).date() + timedelta(days=1)
    request = {
        "city": "北京",
        "start_date": start.isoformat(),
        "end_date": (start + timedelta(days=2)).isoformat(),
        "party_size": 2,
        "budget": {"currency": "CNY", "amount_minor": 300000},
        "must_visit": ["故宫博物院"],
        "preferences": "历史",
        # driving/walking/transit 均可验证；transit 覆盖公交/地铁/混合。
        "transport": os.environ.get("S19_LIVE_TRANSPORT", "打车"),
        "plan_count": 1,
    }
    deps = build_default_deps()
    prior: dict[str, dict[str, object]] = {}
    for step in STEPS:
        envelope = run_stage(
            step, {"task_id": "s19-live", "request": request, "prior": prior}, deps
        )
        print(f"{step}: {envelope['status']}")
        if envelope["status"] == "failed":
            print(json.dumps(envelope, ensure_ascii=False)[:800])
            return 1
        prior[step] = envelope

    legs = [
        segment["transport_to_next"]
        for day in prior["route"]["output"]["days"]
        for segment in day["segments"]
        if segment.get("transport_to_next")
    ]
    exact = [leg for leg in legs if not leg["is_estimated"]]
    weather = [
        {"date": day["weather"]["date"], "status": day["weather"]["status"]}
        for day in prior["assemble"]["plan"]["days"]
    ]
    summary = {
        "amap_configured": bool(os.environ.get("AMAP_API_KEY")),
        "qweather_configured": bool(os.environ.get("QWEATHER_API_KEY")),
        "legs": len(legs),
        "exact_legs": len(exact),
        "estimated_legs": len(legs) - len(exact),
        "sources": sorted({str(leg["source_record_id"]).split(":")[0] for leg in legs}),
        "weather": weather,
        "plan_days": len(prior["assemble"]["plan"]["days"]),
    }
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if exact else 1


if __name__ == "__main__":
    sys.exit(main())
