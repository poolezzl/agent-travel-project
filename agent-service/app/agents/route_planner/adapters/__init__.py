"""Real map/POI adapters (S10 高德) and the route cache, wired by ``wiring.py``.

These mirror the backend ``RealAmapAdapter`` / ``AdapterCacheKeys`` / ``AdapterProperties``
(S10, dev-r5) but live in the Python agent service, which calls 高德 HTTP directly — the S10
real adapters are Java classes with no REST/OpenAPI contract, so the Python side must re-map
fields and error codes itself. No supplier key or raw response is ever logged.
"""

from app.agents.route_planner.adapters.amap_poi_adapter import AmapPoiAdapter
from app.agents.route_planner.adapters.amap_route_adapter import AmapRouteAdapter
from app.agents.route_planner.adapters.redis_route_cache import KeyValueStore, RedisRouteCache

__all__ = [
    "AmapPoiAdapter",
    "AmapRouteAdapter",
    "KeyValueStore",
    "RedisRouteCache",
]
