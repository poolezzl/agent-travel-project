"""Real ASGI HTTP checks for the S20 internal revision endpoints."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from app.main import app
from fastapi.testclient import TestClient
from jsonschema import Draft7Validator

client = TestClient(app)


def _plan() -> dict[str, object]:
    return {
        "days": [
            {
                "date": "2026-11-20",
                "segments": [
                    {
                        "sequence_no": 1,
                        "point_id": "POI-BJ-001",
                        "name": "故宫博物院",
                        "is_must_visit": True,
                    }
                ],
                "reservations": [],
            }
        ],
        "sources": [],
    }


def test_revision_endpoint_preserves_envelope_and_confirmation_gate() -> None:
    response = client.post(
        "/v1/agents/revision/invoke",
        json={
            "task_id": "task-http-revision",
            "input": {
                "base_version_id": "version-1",
                "current_plan": _plan(),
                "revision_request": "删除第一天的故宫",
            },
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["task_id"] == "task-http-revision"
    assert body["intent"] == "change"
    assert body["impact_scope"]["affected_days"] == [1]
    assert body["impact_scope"]["needs_confirm"] is True


def test_variant_endpoint_rejects_client_count_above_limit() -> None:
    response = client.post(
        "/v1/agents/revision/variants",
        json={
            "input": {
                "current_plan": _plan(),
                "plan_count": 4,
                "shared_source_snapshot_ids": ["11111111-1111-4111-8111-111111111111"],
            }
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "failed"
    assert body["error_code"] == "INVALID_INPUT"


@pytest.mark.parametrize(
    ("request_text", "expected"),
    [
        ("删除第二天的中国国家博物馆", [{"POI-BJ-001"}, set()]),
        ("把第二天的中国国家博物馆换成天坛公园", [{"POI-BJ-001"}, {"POI-BJ-003"}]),
        ("把第一天和第二天互换", [{"POI-BJ-002"}, {"POI-BJ-001"}]),
    ],
)
def test_apply_changes_only_confirmed_dates(monkeypatch, request_text, expected) -> None:
    monkeypatch.setenv("ROUTE_PROVIDER", "mock")
    monkeypatch.setenv("WEATHER_PROVIDER", "mock")
    contracts = Path(__file__).resolve().parents[2] / "contracts"
    fixture = contracts / "examples/s19/plan-ok.json"
    plan = json.loads(fixture.read_text(encoding="utf-8"))
    proposal = client.post(
        "/v1/agents/revision/invoke",
        json={
            "input": {
                "base_version_id": "version-1",
                "current_plan": plan,
                "revision_request": request_text,
            }
        },
    ).json()
    result = client.post(
        "/v1/agents/revision/apply",
        json={
            "input": {
                "current_plan": plan,
                "changes": proposal["changes"],
                **proposal["impact_scope"],
            }
        },
    ).json()
    assert result["status"] in {"ok", "partial"}, result
    updated = result["plan"]
    assert [{seg["point_id"] for seg in day["segments"]} for day in updated["days"]] == expected
    for index, original in enumerate(plan["days"], start=1):
        if index not in proposal["impact_scope"]["affected_days"]:
            assert updated["days"][index - 1] == original
    assert updated["summary"]["point_count"] == sum(len(ids) for ids in expected)
    schema = json.loads((contracts / "s19/plan.schema.json").read_text(encoding="utf-8"))
    Draft7Validator(schema).validate(updated)
