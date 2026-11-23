"""Real ASGI HTTP tests for the R3 attractions and reservation endpoints."""

from __future__ import annotations

from datetime import UTC, datetime

from app.agents import reservation_rules
from app.main import app
from fastapi.testclient import TestClient

client = TestClient(app)


def _point() -> dict[str, object]:
    return {
        "point_id": "POI-BJ-001",
        "name": "故宫博物院",
        "latitude": 39.9,
        "longitude": 116.4,
    }


def test_attractions_endpoint_returns_cited_cards() -> None:
    response = client.post(
        "/v1/agents/attractions/invoke",
        json={
            "task_id": "task-http-attractions",
            "input": {"points": [_point()], "city_id": "beijing", "query": "预留"},
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["task_id"] == "task-http-attractions"
    # Sample knowledge ages: a stale card must be reported as partial, never hidden.
    expected = "ok" if body["cards"][0]["freshness_status"] == "fresh" else "partial"
    assert body["status"] == expected
    assert body["cards"][0]["knowledge_id"] == "KN-BJ-001"
    assert body["cards"][0]["chunk_ids"] == ["CHUNK-BJ-001-02"]
    assert body["cards"][0]["facts"][0]["source_record_id"] == "SRC-BJ-001"


def test_reservation_endpoint_returns_timeline(monkeypatch) -> None:
    # Freeze "now" before the release time so the fixture stays deterministic instead of
    # silently failing once the calendar passes the release date.
    monkeypatch.setattr(
        reservation_rules,
        "now_utc",
        lambda: datetime(2026, 11, 13, 8, 0, tzinfo=UTC),
    )
    response = client.post(
        "/v1/agents/reservation-rules/invoke",
        json={
            "task_id": "task-http-reservation",
            "input": {"points": [_point()], "date": "2026-11-20", "city_id": "beijing"},
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["task_id"] == "task-http-reservation"
    assert body["status"] == "ok"
    assert body["reservations"][0]["policy_id"] == "POLICY-BJ-001-V2"
    assert body["timeline"][0]["release_at"] == "2026-11-13T20:00:00+08:00"


def test_dining_lodging_endpoint_returns_legal_candidates() -> None:
    response = client.post(
        "/v1/agents/dining-lodging/invoke",
        json={
            "task_id": "task-http-dining",
            "input": {
                "date": "2026-11-20",
                "itinerary_draft": {
                    "city_id": "beijing",
                    "days": [
                        {
                            "date": "2026-11-20",
                            "segments": [
                                {
                                    "point_id": "POI-BJ-001",
                                    "name": "故宫博物院",
                                    "district": "东城区",
                                    "latitude": 39.916,
                                    "longitude": 116.397,
                                    "start_time": "09:00",
                                    "end_time": "11:30",
                                },
                                {
                                    "point_id": "POI-BJ-002",
                                    "name": "景山公园",
                                    "district": "东城区",
                                    "latitude": 39.928,
                                    "longitude": 116.396,
                                    "start_time": "14:00",
                                    "end_time": "17:30",
                                },
                            ],
                        }
                    ],
                },
            },
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["task_id"] == "task-http-dining"
    assert body["status"] == "ok"
    assert len(body["meals"]) == 2
    assert len(body["lodging"]) == 1
    assert body["meals"][0]["candidates"][0]["poi_id"] == "DINING-BJ-001"


def test_invalid_input_returns_failed_envelope_over_http() -> None:
    response = client.post(
        "/v1/agents/attractions/invoke",
        json={"task_id": "task-http-invalid", "input": {"points": []}},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "failed"
    assert body["error_code"] == "INVALID_INPUT"


def test_catalog_endpoint_exposes_only_snapshot_counts() -> None:
    response = client.get("/v1/agents/catalog")

    assert response.status_code == 200
    body = response.json()
    assert body["catalog_version"] == "r3-catalog-v1"
    assert body["poi_count"] == 3
    assert set(body) == {
        "catalog_version",
        "poi_count",
        "published_entry_count",
        "published_rule_count",
    }
