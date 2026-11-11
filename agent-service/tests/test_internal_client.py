"""Tests for the Python -> Java S15 internal knowledge client."""

from __future__ import annotations

from datetime import date

import httpx
import pytest

from app.knowledge.internal_client import (
    ERROR_HTTP,
    ERROR_INVALID_RESPONSE,
    ERROR_TIMEOUT,
    InternalKnowledgeClient,
    InternalKnowledgeError,
)


def _client(handler) -> InternalKnowledgeClient:
    return InternalKnowledgeClient(
        base_url="http://java.internal",
        client=httpx.Client(base_url="http://java.internal", transport=httpx.MockTransport(handler)),
    )


def _point() -> dict[str, object]:
    return {
        "point_id": "POI-BJ-001",
        "name": "故宫博物院",
        "latitude": 39.9,
        "longitude": 116.4,
    }


def test_catalog_success_returns_snapshot_sections() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "GET"
        assert request.url.path == "/internal/v1/knowledge/catalog"
        assert request.url.params["city"] == "北京"
        return httpx.Response(
            200,
            json={
                "catalog_version": "java-1",
                "pois": [{"poi_id": "p1", "city_id": "北京", "name": "故宫博物院", "aliases": []}],
                "entries": [{"knowledge_id": "k1", "poi_id": "p1", "chunks": []}],
                "rules": [{"policy_id": "r1", "poi_id": "p1"}],
            },
        )

    with _client(handler) as client:
        catalog = client.fetch_catalog(city="北京")
    assert catalog["catalog_version"] == "java-1"
    assert len(catalog["pois"]) == 1
    assert len(catalog["entries"]) == 1
    assert len(catalog["rules"]) == 1


def test_attractions_success_maps_request_and_returns_payload() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/internal/v1/knowledge/attractions/query"
        body = request.read()
        assert b'"city":"beijing"' in body
        assert b'"point_id":"POI-BJ-001"' in body
        return httpx.Response(
            200,
            json={
                "city": "beijing",
                "cards": [{"point_id": "POI-BJ-001", "knowledge_id": "uuid"}],
                "source_metadata": [],
                "retrieved_at": "2026-10-29T00:00:00Z",
            },
        )

    with _client(handler) as client:
        result = client.query_attractions(city="beijing", points=[_point()], preference_tags=["避坑"])
    assert result["cards"][0]["point_id"] == "POI-BJ-001"


def test_reservations_success_maps_date() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/internal/v1/knowledge/reservations/query"
        assert b'"date":"2026-11-20"' in request.read()
        return httpx.Response(
            200,
            json={
                "city": "beijing",
                "date": "2026-11-20",
                "reservations": [{"point_id": "POI-BJ-001"}],
                "source_metadata": [],
                "retrieved_at": "2026-10-29T00:00:00Z",
            },
        )

    with _client(handler) as client:
        result = client.query_reservations(city="beijing", points=[_point()], day=date(2026, 11, 20))
    assert result["reservations"][0]["point_id"] == "POI-BJ-001"


def test_http_error_has_stable_code_and_status() -> None:
    with _client(lambda request: httpx.Response(503, text="down")) as client:
        with pytest.raises(InternalKnowledgeError) as error:
            client.query_attractions(city="beijing", points=[_point()])
    assert error.value.code == ERROR_HTTP
    assert error.value.status_code == 503


def test_timeout_has_stable_code() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow", request=request)

    with _client(handler) as client:
        with pytest.raises(InternalKnowledgeError) as error:
            client.query_attractions(city="beijing", points=[_point()])
    assert error.value.code == ERROR_TIMEOUT


def test_connection_error_is_source_unavailable() -> None:
    from app.knowledge.internal_client import ERROR_UNAVAILABLE

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("down", request=request)

    with _client(handler) as client:
        with pytest.raises(InternalKnowledgeError) as error:
            client.query_attractions(city="beijing", points=[_point()])
    assert error.value.code == ERROR_UNAVAILABLE


def test_non_json_is_invalid_response() -> None:
    with _client(lambda request: httpx.Response(200, content=b"not-json")) as client:
        with pytest.raises(InternalKnowledgeError) as error:
            client.query_attractions(city="beijing", points=[_point()])
    assert error.value.code == ERROR_INVALID_RESPONSE


def test_missing_cards_is_invalid_response() -> None:
    with _client(lambda request: httpx.Response(200, json={"city": "beijing"})) as client:
        with pytest.raises(InternalKnowledgeError) as error:
            client.query_attractions(city="beijing", points=[_point()])
    assert error.value.code == ERROR_INVALID_RESPONSE
