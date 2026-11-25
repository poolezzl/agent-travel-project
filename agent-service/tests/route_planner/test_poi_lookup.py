"""Backend-first ``PoiPort.get`` tests (S12 contract-gap closure, 2026-11-24).

The Java POI-by-ID contract (``contracts/s12/poi-lookup.md``, DRAFT) is authoritative. The
in-process index is only a TTL cache: restart/clear must re-read the backend, and backend
outages must degrade explicitly instead of replaying stale or partial coordinates.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import httpx
import pytest
from app.agents.route_planner.adapters import AmapPoiAdapter
from app.agents.route_planner.adapters.amap_route_adapter import AmapRouteAdapter
from app.agents.route_planner.errors import RouteErrorKind
from app.agents.route_planner.models import RoutePortError, RouteQuery
from app.agents.route_planner.wiring import build_route_deps
from app.core.config import get_route_settings
from app.knowledge.internal_client import InternalKnowledgeClient

AMAP_BASE = "https://restapi.amap.com"
BACKEND_BASE = "http://java.internal"
POI_ID = "8cdb7a2c-a88b-4529-99b2-9e74496c5bf4"
POI_PATH = f"/internal/v1/knowledge/pois/{POI_ID}"
AMAP_DETAIL = "/v3/place/detail"


class Clock:
    def __init__(self) -> None:
        self.now = datetime(2026, 11, 24, 5, 0, 0, tzinfo=UTC)

    def __call__(self) -> datetime:
        return self.now

    def advance(self, seconds: int) -> None:
        self.now += timedelta(seconds=seconds)


def _payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "poi_id": POI_ID,
        "city_id": "beijing",
        "name": "故宫博物院",
        "category": "attraction",
        "latitude": 39.916,
        "longitude": 116.397,
        "coordinate_system": "GCJ02",
        "data_at": "2026-11-24T04:00:00Z",
        "source_metadata": [
            {
                "source_type": "knowledge_base",
                "source_record_id": "daa42808-dc0a-4152-a88a-e03c4af15b09",
                "retrieved_at": "2026-11-24T03:59:00Z",
                "valid_until": None,
                "freshness_status": "fresh",
                "is_estimated": False,
            }
        ],
        "retrieved_at": "2026-11-24T05:00:00Z",
    }
    payload.update(overrides)
    return payload


def _backend_client(
    payloads: dict[str, dict[str, object]],
    calls: list[str],
    *,
    error: Exception | None = None,
) -> InternalKnowledgeClient:
    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        if error is not None:
            raise error
        body = payloads.get(request.url.path)
        if body is None:
            return httpx.Response(404, json={"code": "POI_NOT_FOUND"})
        return httpx.Response(200, json=body)

    return InternalKnowledgeClient(
        base_url=BACKEND_BASE,
        client=httpx.Client(base_url=BACKEND_BASE, transport=httpx.MockTransport(handler)),
    )


def _amap_client(calls: list[str], *, detail: dict[str, object] | None = None) -> httpx.Client:
    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        if request.url.path == AMAP_DETAIL and detail is not None:
            return httpx.Response(200, json={"status": "1", "pois": [detail]})
        return httpx.Response(404, json={"status": "0"})

    return httpx.Client(base_url=AMAP_BASE, transport=httpx.MockTransport(handler))


def _adapter(
    backend: InternalKnowledgeClient | None,
    amap_calls: list[str],
    clock: Clock,
    *,
    detail: dict[str, object] | None = None,
) -> AmapPoiAdapter:
    return AmapPoiAdapter(
        _amap_client(amap_calls, detail=detail),
        "test-key",
        authoritative=backend,
        cache_ttl_seconds=60,
        now=clock,
    )


def test_backend_is_authoritative_before_supplier_detail() -> None:
    backend_calls: list[str] = []
    amap_calls: list[str] = []
    clock = Clock()
    adapter = _adapter(_backend_client({POI_PATH: _payload()}, backend_calls), amap_calls, clock)

    record = adapter.get(POI_ID)

    assert record is not None
    assert (record.latitude, record.longitude) == (39.916, 116.397)
    assert record.category == "attraction"
    assert record.data_at == datetime(2026, 11, 24, 4, 0, tzinfo=UTC)
    assert record.source_record_id == "daa42808-dc0a-4152-a88a-e03c4af15b09"
    assert record.freshness_status == "fresh"
    assert backend_calls == [POI_PATH]
    assert amap_calls == []


def test_fresh_cache_avoids_second_backend_call() -> None:
    backend_calls: list[str] = []
    clock = Clock()
    adapter = _adapter(_backend_client({POI_PATH: _payload()}, backend_calls), [], clock)

    first = adapter.get(POI_ID)
    second = adapter.get(POI_ID)

    assert first is not None and second is first
    assert backend_calls == [POI_PATH]


def test_expired_cache_requeries_backend_and_uses_new_coordinates() -> None:
    backend_calls: list[str] = []
    clock = Clock()
    payloads = {POI_PATH: _payload()}
    adapter = _adapter(_backend_client(payloads, backend_calls), [], clock)
    assert adapter.get(POI_ID) is not None

    clock.advance(61)
    payloads[POI_PATH] = _payload(latitude=39.917, longitude=116.398)
    record = adapter.get(POI_ID)

    assert record is not None
    assert (record.latitude, record.longitude) == (39.917, 116.398)
    assert backend_calls == [POI_PATH, POI_PATH]


def test_process_restart_clears_index_and_still_returns_authoritative_coordinates() -> None:
    backend_calls: list[str] = []
    clock = Clock()
    backend = _backend_client({POI_PATH: _payload()}, backend_calls)
    first_adapter = _adapter(backend, [], clock)
    assert first_adapter.get(POI_ID) is not None

    # A fresh adapter models a restarted process: no in-process index is inherited.
    second_adapter = _adapter(backend, [], clock)
    restarted = second_adapter.get(POI_ID)

    assert restarted is not None
    assert (restarted.latitude, restarted.longitude) == (39.916, 116.397)
    assert restarted.data_at == datetime(2026, 11, 24, 4, 0, tzinfo=UTC)
    assert backend_calls == [POI_PATH, POI_PATH]


def test_backend_unavailable_degrades_explicitly_without_stale_or_amap_fallback() -> None:
    amap_calls: list[str] = []
    clock = Clock()

    def failing(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("down", request=request)

    backend = InternalKnowledgeClient(
        base_url=BACKEND_BASE,
        client=httpx.Client(base_url=BACKEND_BASE, transport=httpx.MockTransport(failing)),
    )
    adapter = _adapter(backend, amap_calls, clock)

    assert adapter.get(POI_ID) is None
    assert adapter.last_degradation == "SOURCE_UNAVAILABLE"
    assert amap_calls == []

    route = AmapRouteAdapter(adapter, _amap_client(amap_calls), "test-key").route(
        RouteQuery(
            from_point_id=POI_ID,
            to_point_id=POI_ID,
            transport="打车",
            city_id="beijing",
        )
    )
    assert isinstance(route, RoutePortError)
    assert route.kind is RouteErrorKind.DIRTY
    assert "authoritative coordinates" in route.message
    assert amap_calls == []


def test_backend_404_falls_back_to_supplier_detail() -> None:
    backend_calls: list[str] = []
    amap_calls: list[str] = []
    clock = Clock()
    detail = {
        "id": POI_ID,
        "name": "外部点位",
        "location": "116.400000,39.900000",
        "adcode": "110101",
        "type": "风景名胜",
    }
    adapter = _adapter(_backend_client({}, backend_calls), amap_calls, clock, detail=detail)

    record = adapter.get(POI_ID)

    assert record is not None
    assert record.name == "外部点位"
    assert record.category == "风景名胜"
    assert record.source_record_id == f"amap-poi:{POI_ID}"
    assert record.data_at is None
    assert backend_calls == [POI_PATH]
    assert amap_calls == [AMAP_DETAIL]


def test_unknown_category_and_missing_data_at_stay_explicit() -> None:
    backend_calls: list[str] = []
    clock = Clock()
    payload = _payload(category="unknown", data_at=None)
    adapter = _adapter(_backend_client({POI_PATH: payload}, backend_calls), [], clock)

    record = adapter.get(POI_ID)

    assert record is not None
    assert record.category == "unknown"
    assert record.data_at is None


@pytest.mark.parametrize(
    "overrides",
    [
        {"latitude": None},
        {"longitude": 999.0},
        {"coordinate_system": "BD09"},
        {"data_at": "not-a-date"},
        {"source_metadata": []},
    ],
)
def test_invalid_backend_record_is_not_authoritative(overrides: dict[str, object]) -> None:
    backend_calls: list[str] = []
    amap_calls: list[str] = []
    clock = Clock()
    adapter = _adapter(
        _backend_client({POI_PATH: _payload(**overrides)}, backend_calls), amap_calls, clock
    )

    assert adapter.get(POI_ID) is None
    assert adapter.last_degradation == "INVALID_RESPONSE"
    assert amap_calls == []


def test_invalid_response_does_not_poison_cache() -> None:
    backend_calls: list[str] = []
    clock = Clock()
    payloads = {POI_PATH: _payload(latitude=None)}
    adapter = _adapter(_backend_client(payloads, backend_calls), [], clock)
    assert adapter.get(POI_ID) is None

    clock.advance(1)
    payloads[POI_PATH] = _payload()
    record = adapter.get(POI_ID)

    assert record is not None
    assert (record.latitude, record.longitude) == (39.916, 116.397)
    assert backend_calls == [POI_PATH, POI_PATH]


def test_supplier_style_id_skips_backend_and_uses_detail() -> None:
    backend_calls: list[str] = []
    amap_calls: list[str] = []
    clock = Clock()
    detail = {
        "id": "B000A1",
        "name": "天安门",
        "location": "116.391200,39.904200",
        "adcode": "110101",
        "type": "风景名胜",
    }
    adapter = _adapter(_backend_client({}, backend_calls), amap_calls, clock, detail=detail)

    record = adapter.get("B000A1")

    assert record is not None
    assert record.poi_id == "B000A1"
    assert record.category == "风景名胜"
    assert backend_calls == []
    assert amap_calls == [AMAP_DETAIL]


def test_backend_400_for_uuid_falls_back_to_supplier_detail() -> None:
    backend_calls: list[str] = []
    amap_calls: list[str] = []
    clock = Clock()
    detail = {
        "id": POI_ID,
        "name": "外部点位",
        "location": "116.400000,39.900000",
        "adcode": "110101",
        "type": "风景名胜",
    }

    def handler(request: httpx.Request) -> httpx.Response:
        backend_calls.append(request.url.path)
        return httpx.Response(400, json={"code": "POI_ID_INVALID"})

    backend = InternalKnowledgeClient(
        base_url=BACKEND_BASE,
        client=httpx.Client(base_url=BACKEND_BASE, transport=httpx.MockTransport(handler)),
    )
    adapter = _adapter(backend, amap_calls, clock, detail=detail)

    record = adapter.get(POI_ID)

    assert record is not None
    assert record.name == "外部点位"
    assert backend_calls == [POI_PATH]
    assert amap_calls == [AMAP_DETAIL]


def test_wiring_uses_injected_backend_client(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ROUTE_PROVIDER", "amap")
    get_route_settings.cache_clear()
    backend_calls: list[str] = []
    amap_calls: list[str] = []
    try:
        poi_port, _provider, _cache = build_route_deps(
            client=_amap_client(amap_calls),
            internal_client=_backend_client({POI_PATH: _payload()}, backend_calls),
        )
        assert isinstance(poi_port, AmapPoiAdapter)
        record = poi_port.get(POI_ID)
        assert record is not None
        assert record.category == "attraction"
        assert backend_calls == [POI_PATH]
        assert amap_calls == []
    finally:
        monkeypatch.delenv("ROUTE_PROVIDER", raising=False)
        get_route_settings.cache_clear()
