"""Real ASGI HTTP tests for the S19 planning stage endpoints."""

from __future__ import annotations

import json
from datetime import date, timedelta
from pathlib import Path

from app.main import app
from fastapi.testclient import TestClient
from jsonschema import Draft7Validator

client = TestClient(app)
ROOT = Path(__file__).resolve().parents[3]
PLAN_SCHEMA = json.loads(
    (ROOT / "contracts" / "s19" / "plan.schema.json").read_text(encoding="utf-8")
)

# Relative dates keep this HTTP smoke test valid on any run date; a fixed 2026-11 date
# silently started failing once the calendar passed it.
_START = date.today() + timedelta(days=7)
PAYLOAD = {
    "city": "北京",
    "start_date": _START.isoformat(),
    "end_date": (_START + timedelta(days=2)).isoformat(),
    "party_size": 2,
    "budget": {"currency": "CNY", "amount_minor": 300000},
    "must_visit": ["故宫博物院"],
    "preferences": "历史",
    "transport": "混合",
    "plan_count": 1,
}


def test_stage_list_exposes_the_fixed_dag() -> None:
    response = client.get("/v1/planning/steps")

    assert response.status_code == 200
    assert response.json()["steps"] == ["parse", "collect", "schedule", "route", "assemble"]


def test_full_dag_over_http_returns_a_contract_valid_plan() -> None:
    prior: dict[str, object] = {}
    for step in ("parse", "collect", "schedule", "route", "assemble"):
        response = client.post(
            f"/v1/planning/steps/{step}",
            json={"task_id": "task-http-s19", "request": PAYLOAD, "prior": prior},
        )
        assert response.status_code == 200
        body = response.json()
        assert body["task_id"] == "task-http-s19"
        assert body["status"] != "failed", (step, body.get("error_code"), body.get("message"))
        prior[step] = body

    assembled = prior["assemble"]
    assert isinstance(assembled, dict)
    plan = assembled["plan"]
    Draft7Validator(PLAN_SCHEMA).validate(plan)
    assert plan["trip"]["city_name"] == "北京"
    assert plan["sample_only"] is True


def test_unknown_step_and_invalid_request_are_stable_envelopes() -> None:
    unknown = client.post("/v1/planning/steps/teleport", json={"request": PAYLOAD})
    assert unknown.status_code == 200
    assert unknown.json()["error_code"] == "INVALID_STEP"

    invalid = client.post(
        "/v1/planning/steps/parse",
        json={"request": {**PAYLOAD, "start_date": "2026-01-01", "end_date": "2026-01-02"}},
    )
    assert invalid.status_code == 200
    assert invalid.json()["status"] == "failed"
    assert invalid.json()["error_code"] == "INVALID_INPUT"
