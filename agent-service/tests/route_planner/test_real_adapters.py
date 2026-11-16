"""Real 高德 (AMap) adapter integration tests (S12 Step 4).

Every HTTP call is served by ``httpx.MockTransport`` — the suite never contacts a real
supplier. Each test fixes an explicit input fixture and asserts the resulting status,
ordered points, segments, ``is_estimated`` and ``degraded_fields``.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from app.agents.route_planner.adapters import (
    AmapPoiAdapter,
    AmapRouteAdapter,
    RedisRouteCache,
)
from app.agents.route_planner.errors import RouteErrorKind
from app.agents.route_planner.models import (
    PointCandidate,
    RouteData,
    RoutePlanRequest,
    RoutePortError,
    RouteQuery,
    TimeWindow,
)
from app.agents.route_planner.planner import plan_route
from app.agents.route_planner.route_provider import RouteProvider, route_key
from app.agents.route_planner.wiring import build_route_deps
from app.core.config import get_route_settings

BASE_URL = "https://restapi.amap.com"
CITY = "110101"
FIXED_NOW = datetime(2026, 9, 21, 8, 0, 0, tzinfo=UTC)

POI_TIANANMEN = {
    "id": "B000A1",
    "name": "天安门",
    "location": "116.391200,39.904200",
    "adcode": CITY,
}
POI_HOTEL = {
    "id": "B000A2",
    "name": "酒店",
    "location": "116.391200,39.899700",
    "adcode": CITY,
}
ROUTE_OK = {"status": "1", "route": {"paths": [{"distance": "5000", "duration": "900"}]}}

Handler = Callable[[httpx.Request], httpx.Response]


class FakeStore:
    """Redis double: honours ``setex`` TTL against an injectable clock."""

    def __init__(self, now: Callable[[], datetime]) -> None:
        self._now = now
        self._data: dict[str, tuple[str, datetime]] = {}
        self.ttl_seen: int | None = None

    def get(self, key: str) -> str | None:
        entry = self._data.get(key)
        if entry is None:
            return None
        value, expires_at = entry
        if self._now() >= expires_at:
            del self._data[key]
            return None
        return value

    def setex(self, key: str, ttl_seconds: int, value: str) -> None:
        self.ttl_seen = ttl_seconds
        self._data[key] = (value, self._now() + timedelta(seconds=ttl_seconds))

    @property
    def keys(self) -> list[str]:
        now = self._now()
        return [key for key, (_, expires_at) in self._data.items() if now < expires_at]


def _handler(
    *,
    pois: list[dict[str, object]] | None = None,
    route_status: int = 200,
    route_body: object = ROUTE_OK,
    route_exc: Exception | None = None,
    calls: list[str] | None = None,
    raw_route_text: str | None = None,
) -> Handler:
    def handle(request: httpx.Request) -> httpx.Response:
        if calls is not None:
            calls.append(request.url.path)
        if request.url.path == "/v5/place/text":
            keyword = request.url.params.get("keywords", "")
            matched = [poi for poi in (pois or []) if poi.get("name") == keyword]
            return httpx.Response(200, json={"status": "1", "pois": matched})
        if request.url.path.startswith("/v3/direction/"):
            if route_exc is not None:
                raise route_exc
            if raw_route_text is not None:
                return httpx.Response(route_status, text=raw_route_text)
            return httpx.Response(route_status, json=route_body)
        return httpx.Response(404, json={"status": "0", "infocode": "20003"})

    return handle


def _client(handler: Handler) -> httpx.Client:
    return httpx.Client(base_url=BASE_URL, transport=httpx.MockTransport(handler))


def _poi_and_route_port(
    *,
    pois: list[dict[str, object]] | None = None,
    route_status: int = 200,
    route_body: object = ROUTE_OK,
    route_exc: Exception | None = None,
    calls: list[str] | None = None,
    raw_route_text: str | None = None,
) -> tuple[AmapPoiAdapter, AmapRouteAdapter]:
    client = _client(
        _handler(
            pois=pois,
            route_status=route_status,
            route_body=route_body,
            route_exc=route_exc,
            calls=calls,
            raw_route_text=raw_route_text,
        )
    )
    poi = AmapPoiAdapter(client, "test-key")
    route_port = AmapRouteAdapter(poi, client, "test-key", now=lambda: FIXED_NOW)
    return poi, route_port


def _stack(
    handler: Handler,
    *,
    store: FakeStore | None = None,
    ttl_seconds: int = 604800,
) -> tuple[AmapPoiAdapter, RouteProvider]:
    client = _client(handler)
    poi = AmapPoiAdapter(client, "test-key")
    port = AmapRouteAdapter(poi, client, "test-key", now=lambda: FIXED_NOW)
    if store is None:
        return poi, RouteProvider(port, _MemoryCache())
    cache = RedisRouteCache(store, ttl_seconds, key_prefix="amap")
    return poi, RouteProvider(port, cache)


class _MemoryCache:
    def __init__(self) -> None:
        self._store: dict[str, RouteData] = {}

    def get(self, key: str) -> RouteData | None:
        return self._store.get(key)

    def put(self, key: str, data: RouteData) -> None:
        self._store[key] = data


def _request(names: list[str], transport: str = "打车") -> RoutePlanRequest:
    return RoutePlanRequest(
        date="2026-10-01",
        city_id=CITY,
        coordinate_system="GCJ02",
        points=[PointCandidate(name=name, city_id=CITY) for name in names],
        origin=PointCandidate(name="酒店", city_id=CITY),
        destination=PointCandidate(name="酒店", city_id=CITY),
        transport_preference=transport,
        time_window=TimeWindow(start="09:00", end="18:00"),
    )


def _leg() -> RouteQuery:
    return RouteQuery(
        from_point_id="B000A2",
        to_point_id="B000A1",
        transport="打车",
        city_id=CITY,
    )


# ---------------------------------------------------------------- POI (ALG-04)


def test_poi_unique_hit_passes_alg04() -> None:
    poi, provider = _stack(_handler(pois=[POI_TIANANMEN, POI_HOTEL]))

    result = plan_route(_request(["天安门"]), poi, provider)

    assert result.status == "ok"
    assert result.degraded_fields == []
    assert result.route is not None
    assert result.route.ordered_points == ["B000A2", "B000A1", "B000A2"]
    assert result.route.is_estimated is False
    assert [(s.from_point_id, s.to_point_id) for s in result.route.segments] == [
        ("B000A2", "B000A1"),
        ("B000A1", "B000A2"),
    ]
    assert all(s.distance_m == 5000.0 and s.duration_min == 15.0 for s in result.route.segments)
    assert result.route.segments[0].source_record_id.startswith("amap-route:")


@pytest.mark.parametrize(
    ("transport", "city_id", "expected_city"),
    [
        ("公交", CITY, CITY),
        ("地铁", "beijing", "110000"),
        ("混合", "beijing", "110000"),
    ],
)
def test_transit_route_uses_city_adcode_and_transits(
    transport: str, city_id: str, expected_city: str
) -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/v5/place/text":
            keyword = request.url.params.get("keywords", "")
            matched = [poi for poi in (POI_TIANANMEN, POI_HOTEL) if poi["name"] == keyword]
            return httpx.Response(200, json={"status": "1", "pois": matched})
        seen["path"] = request.url.path
        seen["params"] = dict(request.url.params)
        return httpx.Response(
            200,
            json={"status": "1", "route": {"transits": [{"distance": "7200", "duration": "1500"}]}},
        )

    client = httpx.Client(base_url=BASE_URL, transport=httpx.MockTransport(handler))
    poi = AmapPoiAdapter(client, "test-key")
    poi.lookup("天安门", CITY)
    poi.lookup("酒店", CITY)
    port = AmapRouteAdapter(poi, client, "test-key", now=lambda: FIXED_NOW)

    result = port.route(
        RouteQuery(
            from_point_id="B000A2",
            to_point_id="B000A1",
            transport=transport,
            city_id=city_id,
        )
    )

    assert isinstance(result, RouteData)
    assert result.is_estimated is False
    assert result.distance_m == 7200.0
    assert result.duration_min == 25.0
    assert seen["path"] == "/v3/direction/transit/integrated"
    params = seen["params"]
    assert isinstance(params, dict)
    assert params["city"] == expected_city
    assert params["cityd"] == expected_city
    assert params["strategy"] == "0"


def test_poi_same_name_requires_clarification_without_merge() -> None:
    twin = {**POI_TIANANMEN, "id": "B000A9"}
    poi, provider = _stack(_handler(pois=[POI_TIANANMEN, twin, POI_HOTEL]))

    result = plan_route(_request(["天安门"]), poi, provider)

    assert result.status == "failed"
    assert result.error_code == "INVALID_INPUT"
    assert result.message is not None
    assert "AMBIGUOUS_NAME" in result.message
    assert "B000A1" in result.message and "B000A9" in result.message


def test_poi_get_by_id_fetches_detail_once_and_maps_category() -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        if request.url.path == "/v3/place/detail":
            poi = {**POI_TIANANMEN, "type": "风景名胜;世界遗产"}
            return httpx.Response(200, json={"status": "1", "pois": [poi]})
        return httpx.Response(404, json={"status": "0"})

    client = httpx.Client(base_url=BASE_URL, transport=httpx.MockTransport(handler))
    poi = AmapPoiAdapter(client, "test-key")

    first = poi.get("B000A1")
    second = poi.get("B000A1")

    assert first is not None
    assert first.name == "天安门"
    assert first.category == "风景名胜;世界遗产"
    assert second is first
    assert calls == ["/v3/place/detail"]


def test_poi_out_of_range_coordinate_is_dropped() -> None:
    bad = {**POI_TIANANMEN, "location": "999.0,999.0"}
    poi, provider = _stack(_handler(pois=[bad, POI_HOTEL]))

    assert poi.lookup("天安门", CITY) == []
    result = plan_route(_request(["天安门"]), poi, provider)
    assert result.status == "failed"
    assert result.error_code == "SOURCE_UNAVAILABLE"


def test_poi_missing_id_is_dropped() -> None:
    nameless = {"name": "天安门", "location": "116.391200,39.904200", "adcode": CITY}
    poi, _ = _stack(_handler(pois=[nameless, POI_HOTEL]))

    assert poi.lookup("天安门", CITY) == []


def test_poi_empty_response_is_not_guessed() -> None:
    poi, provider = _stack(_handler(pois=[POI_HOTEL]))

    result = plan_route(_request(["天安门"]), poi, provider)

    assert result.status == "failed"
    assert result.error_code == "SOURCE_UNAVAILABLE"


# ------------------------------------------------------- Route responses (ALG-06)


def test_route_success_returns_exact_data() -> None:
    poi, port = _poi_and_route_port(pois=[POI_TIANANMEN, POI_HOTEL])
    poi.lookup("酒店", CITY)
    poi.lookup("天安门", CITY)

    result = port.route(_leg())

    assert isinstance(result, RouteData)
    assert result.distance_m == 5000.0
    assert result.duration_min == 15.0
    assert result.retrieved_at == FIXED_NOW
    assert result.is_estimated is False
    assert result.freshness_status == "fresh"
    assert result.source_record_id.startswith("amap-route:")


def test_route_timeout_maps_to_timeout() -> None:
    poi, port = _poi_and_route_port(
        pois=[POI_TIANANMEN, POI_HOTEL],
        route_exc=httpx.ReadTimeout("slow"),
    )
    poi.lookup("酒店", CITY)
    poi.lookup("天安门", CITY)

    result = port.route(_leg())

    assert isinstance(result, RoutePortError)
    assert result.kind is RouteErrorKind.TIMEOUT


def test_route_429_maps_to_rate_limited() -> None:
    body = {"status": "0", "infocode": "10004"}
    poi, port = _poi_and_route_port(
        pois=[POI_TIANANMEN, POI_HOTEL],
        route_status=429,
        route_body=body,
    )
    poi.lookup("酒店", CITY)
    poi.lookup("天安门", CITY)

    result = port.route(_leg())

    assert isinstance(result, RoutePortError)
    assert result.kind is RouteErrorKind.RATE_LIMITED


@pytest.mark.parametrize(
    ("status", "infocode", "expected"),
    [
        (401, None, RouteErrorKind.AUTH),
        (200, "10003", RouteErrorKind.QUOTA),
        (200, "10001", RouteErrorKind.AUTH),
        (500, None, RouteErrorKind.UNREACHABLE),
    ],
)
def test_route_status_and_infocode_mapping(
    status: int, infocode: str | None, expected: RouteErrorKind
) -> None:
    body: object = {"status": "0", "infocode": infocode} if infocode else {"status": "0"}
    poi, port = _poi_and_route_port(
        pois=[POI_TIANANMEN, POI_HOTEL],
        route_status=status,
        route_body=body,
    )
    poi.lookup("酒店", CITY)
    poi.lookup("天安门", CITY)

    result = port.route(_leg())

    assert isinstance(result, RoutePortError)
    assert result.kind is expected


def test_route_empty_paths_maps_to_empty() -> None:
    poi, port = _poi_and_route_port(
        pois=[POI_TIANANMEN, POI_HOTEL],
        route_body={"status": "1", "route": {"paths": []}},
    )
    poi.lookup("酒店", CITY)
    poi.lookup("天安门", CITY)

    result = port.route(_leg())

    assert isinstance(result, RoutePortError)
    assert result.kind is RouteErrorKind.EMPTY


def test_route_missing_fields_maps_to_empty() -> None:
    poi, port = _poi_and_route_port(
        pois=[POI_TIANANMEN, POI_HOTEL],
        route_body={"status": "1", "route": {"paths": [{"distance": "5000"}]}},
    )
    poi.lookup("酒店", CITY)
    poi.lookup("天安门", CITY)

    result = port.route(_leg())

    assert isinstance(result, RoutePortError)
    assert result.kind is RouteErrorKind.EMPTY


def test_route_dirty_json_maps_to_dirty() -> None:
    poi, port = _poi_and_route_port(
        pois=[POI_TIANANMEN, POI_HOTEL],
        raw_route_text="<html>oops</html>",
    )
    poi.lookup("酒店", CITY)
    poi.lookup("天安门", CITY)

    result = port.route(_leg())

    assert isinstance(result, RoutePortError)
    assert result.kind is RouteErrorKind.DIRTY


def test_unknown_transport_maps_to_dirty_without_http() -> None:
    calls: list[str] = []
    poi, port = _poi_and_route_port(pois=[POI_TIANANMEN, POI_HOTEL], calls=calls)
    poi.lookup("酒店", CITY)
    poi.lookup("天安门", CITY)

    result = port.route(_leg().model_copy(update={"transport": "飞机"}))

    assert isinstance(result, RoutePortError)
    assert result.kind is RouteErrorKind.DIRTY
    assert [path for path in calls if path.startswith("/v3/direction/")] == []


# ------------------------------------------------------------------ Cache


def test_auth_and_quota_write_no_negative_cache() -> None:
    for status, infocode in ((401, None), (200, "10003")):
        body: object = {"status": "0", "infocode": infocode} if infocode else {"status": "0"}
        store = FakeStore(now=lambda: FIXED_NOW)
        poi, provider = _stack(
            _handler(pois=[POI_TIANANMEN, POI_HOTEL], route_status=status, route_body=body),
            store=store,
        )

        result = plan_route(_request(["天安门"]), poi, provider)

        assert result.status == "failed"
        assert result.error_code == "SOURCE_UNAVAILABLE"
        assert store.keys == []


def test_cache_hit_avoids_second_http_and_keeps_original_retrieved_at() -> None:
    store = FakeStore(now=lambda: FIXED_NOW)
    calls: list[str] = []
    poi, provider = _stack(
        _handler(pois=[POI_TIANANMEN, POI_HOTEL], calls=calls),
        store=store,
    )

    first = plan_route(_request(["天安门"]), poi, provider)
    route_calls_after_first = sum(1 for path in calls if path.startswith("/v3/direction/"))
    second = plan_route(_request(["天安门"]), poi, provider)
    route_calls_after_second = sum(1 for path in calls if path.startswith("/v3/direction/"))

    assert first.status == second.status == "ok"
    assert first.route is not None and second.route is not None
    assert first.route.segments == second.route.segments
    assert route_calls_after_first > 0
    assert route_calls_after_second == route_calls_after_first
    assert store.keys, "successful exact routes must be cached"


def test_cache_ttl_comes_from_config_and_expiry_refetches() -> None:
    settings = get_route_settings()
    assert settings.route_cache_ttl_seconds == 604800

    clock = [FIXED_NOW]
    store = FakeStore(now=lambda: clock[0])
    calls: list[str] = []
    poi, provider = _stack(
        _handler(pois=[POI_TIANANMEN, POI_HOTEL], calls=calls),
        store=store,
    )

    plan_route(_request(["天安门"]), poi, provider)
    first_route_calls = sum(1 for path in calls if path.startswith("/v3/direction/"))
    assert store.ttl_seen == settings.route_cache_ttl_seconds
    assert first_route_calls > 0

    clock[0] = FIXED_NOW + timedelta(seconds=settings.route_cache_ttl_seconds + 1)
    assert store.keys == []

    plan_route(_request(["天安门"]), poi, provider)
    assert sum(1 for path in calls if path.startswith("/v3/direction/")) == 2 * first_route_calls


def _cache_data() -> RouteData:
    return RouteData(
        from_point_id="B000A2",
        to_point_id="B000A1",
        transport="打车",
        distance_m=5000.0,
        duration_min=15.0,
        source_record_id="amap-route:abc",
        retrieved_at=FIXED_NOW,
        freshness_status="fresh",
        is_estimated=False,
    )


def test_expired_cache_entry_is_never_replayed_as_fresh() -> None:
    clock = [FIXED_NOW]
    store = FakeStore(now=lambda: clock[0])
    cache = RedisRouteCache(store, ttl_seconds=60, key_prefix="amap")
    key = route_key(_leg())
    cache.put(key, _cache_data())

    assert cache.get(key) is not None

    clock[0] = FIXED_NOW + timedelta(seconds=61)

    assert cache.get(key) is None


def test_corrupt_cache_entry_is_ignored() -> None:
    store = FakeStore(now=lambda: FIXED_NOW)
    cache = RedisRouteCache(store, ttl_seconds=60, key_prefix="amap")
    key = route_key(_leg())
    store.setex(f"amap:{key}", 60, "not-json")

    assert cache.get(key) is None


def test_cache_key_is_not_shared_across_city_leg_transport_or_supplier() -> None:
    store = FakeStore(now=lambda: FIXED_NOW)
    cache = RedisRouteCache(store, ttl_seconds=60, key_prefix="amap")
    leg = _leg()
    cache.put(route_key(leg), _cache_data())

    variants = (
        leg.model_copy(update={"city_id": "310101"}),
        leg.model_copy(update={"from_point_id": "B999"}),
        leg.model_copy(update={"to_point_id": "B999"}),
        leg.model_copy(update={"transport": "步行"}),
        leg.model_copy(update={"time_slot": "morning"}),
        leg.model_copy(update={"coordinate_system": "WGS84"}),
    )
    for other in variants:
        assert route_key(other) != route_key(leg)
        assert cache.get(route_key(other)) is None

    # A different supplier version must not reuse the entry either.
    other_supplier = RedisRouteCache(store, ttl_seconds=60, key_prefix="amap-v2")
    assert other_supplier.get(route_key(leg)) is None
    # The exact key still hits, proving the cache itself works.
    assert cache.get(route_key(leg)) is not None


# ----------------------------------------------------------------- Wiring


def test_wiring_mock_mode_returns_ports(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ROUTE_PROVIDER", "mock")
    get_route_settings.cache_clear()
    try:
        poi_port, provider, cache = build_route_deps()
        assert hasattr(poi_port, "lookup")
        assert isinstance(provider, RouteProvider)
        assert hasattr(cache, "get")
    finally:
        monkeypatch.delenv("ROUTE_PROVIDER", raising=False)
        get_route_settings.cache_clear()


def test_wiring_amap_mode_uses_injected_client(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ROUTE_PROVIDER", "amap")
    get_route_settings.cache_clear()
    try:
        poi_port, provider, _ = build_route_deps(
            client=_client(_handler(pois=[POI_TIANANMEN, POI_HOTEL]))
        )
        assert isinstance(poi_port, AmapPoiAdapter)

        result = plan_route(_request(["天安门"]), poi_port, provider)
        assert result.status == "ok"
    finally:
        monkeypatch.delenv("ROUTE_PROVIDER", raising=False)
        get_route_settings.cache_clear()


def test_amap_mode_is_byte_identical_across_two_runs() -> None:
    def run() -> str:
        poi, provider = _stack(_handler(pois=[POI_TIANANMEN, POI_HOTEL]))
        return plan_route(_request(["天安门"]), poi, provider).model_dump_json()

    assert run() == run()
