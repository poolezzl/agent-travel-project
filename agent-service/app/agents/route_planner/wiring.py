"""Composition root wiring the route-planner dependencies (mock or amap).

Reads ``ROUTE_PROVIDER`` / cache switch / TTL from ``app.core.config``. Returns the concrete
``(poi_port, route_provider, cache)`` pair consumed by ``plan_route``. The existing ALG-06
``RouteProvider`` is reused unchanged; ``plan_route(request, poi_port, route_provider)`` is
unchanged. The ``mock`` branch is a placeholder — tests inject their own seeded mocks directly.
"""

from __future__ import annotations

import httpx

from app.agents.route_planner.adapters.amap_poi_adapter import AmapPoiAdapter
from app.agents.route_planner.adapters.amap_route_adapter import AmapRouteAdapter
from app.agents.route_planner.adapters.redis_route_cache import KeyValueStore, RedisRouteCache
from app.agents.route_planner.mocks import MockPoiPort, MockRouteCache, MockRoutePort
from app.agents.route_planner.ports import PoiPort, RouteCache
from app.agents.route_planner.route_provider import RouteProvider
from app.core.config import get_route_settings


def build_route_deps(
    store: KeyValueStore | None = None,
    client: httpx.Client | None = None,
) -> tuple[PoiPort, RouteProvider, RouteCache]:
    """Build (poi_port, route_provider, cache) for the configured ``ROUTE_PROVIDER``.

    ``store``/``client`` are injection points for tests (Redis double, MockTransport client);
    in production both are omitted and created from :class:`RouteAdapterSettings`.
    """
    settings = get_route_settings()

    if settings.route_provider == "mock":
        # Wire-level placeholder: deterministic seeded mocks are supplied by tests. No network.
        poi_port: PoiPort = MockPoiPort([])
        cache: RouteCache = MockRouteCache()
        provider = RouteProvider(MockRoutePort({}), cache)
        return poi_port, provider, cache

    if client is None:
        client = httpx.Client(
            base_url=settings.amap_base_url,
            timeout=settings.amap_timeout_ms / 1000.0,
        )
    poi_port = AmapPoiAdapter(client, settings.amap_api_key)
    route_port = AmapRouteAdapter(poi_port, client, settings.amap_api_key)

    if settings.route_cache_enabled and store is not None:
        cache = RedisRouteCache(store, settings.route_cache_ttl_seconds)
    else:
        cache = MockRouteCache()

    provider = RouteProvider(route_port, cache)
    return poi_port, provider, cache
