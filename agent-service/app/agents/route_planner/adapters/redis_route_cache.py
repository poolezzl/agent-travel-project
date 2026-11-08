"""Redis route cache: implements ``RouteCache`` with TTL read from S10 config (never hardcoded).

The cache stores a serialized ``RouteData`` under the caller-supplied ALG-14 key. TTL expiry is
enforced by the backing store (``setex``), so a still-cached entry is a fresh fact and an
expired entry is simply gone — never replayed as fresh. ``AUTH``/``QUOTA`` never write a
negative cache by construction: ``RouteProvider`` only calls ``put`` on a successful exact route.
"""

from __future__ import annotations

from typing import Protocol

from pydantic import ValidationError

from app.agents.route_planner.models import RouteData


class KeyValueStore(Protocol):
    """Minimal key/value backend the cache runs against (Redis or a test double)."""

    def get(self, key: str) -> str | None: ...
    def setex(self, key: str, ttl_seconds: int, value: str) -> None: ...


class RedisRouteCache:
    def __init__(
        self,
        store: KeyValueStore,
        ttl_seconds: int,
        key_prefix: str = "amap",
    ) -> None:
        self._store = store
        self._ttl_seconds = ttl_seconds
        self._key_prefix = key_prefix

    def _full_key(self, key: str) -> str:
        # ALG-14 requires the supplier version in the cache key. ``route_key`` (ALG-06) does
        # not carry it yet (format PENDING-R5), so the adapter namespaces it here.
        return f"{self._key_prefix}:{key}"

    def get(self, key: str) -> RouteData | None:
        raw = self._store.get(self._full_key(key))
        if raw is None:
            return None
        try:
            return RouteData.model_validate_json(raw)
        except ValidationError:
            return None

    def put(self, key: str, data: RouteData) -> None:
        self._store.setex(self._full_key(key), self._ttl_seconds, data.model_dump_json())
