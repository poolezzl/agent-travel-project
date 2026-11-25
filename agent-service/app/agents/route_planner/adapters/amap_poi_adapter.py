"""高德 POI adapter: implements ``PoiPort``.

``lookup`` maps name/city to 高德 keyword search. ``get`` is backend-first: it asks the Java
internal POI-by-ID contract (``contracts/s12/poi-lookup.md``), and the in-process index is only
a TTL cache. A cache miss/expiry always re-reads the backend; if the backend is unavailable the
adapter degrades explicitly by returning ``None`` (never stale/partial coordinates). When the
backend answers 404, the POI may be an external supplier-only point, so the adapter falls back
to 高德 ``/v3/place/detail``. Same-name results are passed through verbatim for upstream
disambiguation — never auto-merged.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from threading import Lock
from typing import Literal, cast

import httpx

from app.agents.route_planner.adapters._amap import parse_lnglat
from app.agents.route_planner.models import UNKNOWN_CATEGORY, PoiRecord
from app.knowledge.internal_client import InternalKnowledgeClient, InternalKnowledgeError

_COORDINATE_SYSTEM = "GCJ02"
_COORDINATE_SYSTEMS = frozenset({"GCJ02", "WGS84"})
_FRESHNESS = frozenset({"fresh", "stale", "unknown"})
Freshness = Literal["fresh", "stale", "unknown"]
_MAX_CACHE_ENTRIES = 4096


class AmapPoiAdapter:
    def __init__(
        self,
        client: httpx.Client,
        api_key: str,
        *,
        authoritative: InternalKnowledgeClient | None = None,
        cache_ttl_seconds: int = 300,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self._client = client
        self._api_key = api_key
        self._authoritative = authoritative
        self._cache_ttl = timedelta(seconds=max(1, cache_ttl_seconds))
        self._now = now if now is not None else (lambda: datetime.now(UTC))
        self._cache: dict[str, tuple[PoiRecord, datetime]] = {}
        self._cache_lock = Lock()
        self.last_degradation: str | None = None

    def lookup(self, name: str, city_id: str) -> list[PoiRecord]:
        try:
            response = self._client.get(
                "/v5/place/text",
                params={
                    "keywords": name,
                    "city": city_id,
                    "city_limit": "true",
                    "page_size": 20,
                    "key": self._api_key,
                },
            )
        except httpx.HTTPError:
            # PoiPort.lookup has no error channel: unreachable source -> empty -> unavailable.
            return []

        if response.status_code != 200:
            return []

        try:
            body = response.json()
        except ValueError:
            return []

        if not isinstance(body, dict) or body.get("status") != "1":
            return []

        pois = body.get("pois")
        if not isinstance(pois, list):
            return []

        now = self._now()
        records: list[PoiRecord] = []
        for poi in pois:
            record = self._parse(poi, city_id, now)
            if record is not None:
                self._remember(record)
                records.append(record)
        return records

    def get(self, poi_id: str) -> PoiRecord | None:
        """Return a fresh authoritative record, re-reading the backend after expiry.

        With an authoritative client configured, the backend is the source of truth. Cache is
        only a cache: an expired entry is dropped, never replayed. Backend transport/shape
        failures set ``last_degradation`` and return ``None`` so upper layers emit a structured
        "missing authoritative coordinates" error. A backend 404 falls back to the supplier's
        by-id detail because the POI may legitimately be an external point.
        """
        self.last_degradation = None
        cached = self._cached(poi_id)
        if cached is not None:
            return cached

        if self._authoritative is not None:
            return self._get_from_backend(poi_id)
        return self._get_from_detail(poi_id)

    def clear_cache(self) -> None:
        """Drop the in-process index (simulates a process restart in tests)."""
        with self._cache_lock:
            self._cache.clear()

    # ------------------------------------------------------------- backend path

    def _get_from_backend(self, poi_id: str) -> PoiRecord | None:
        authoritative = self._authoritative
        assert authoritative is not None  # guarded by the caller
        try:
            payload = authoritative.lookup_poi(poi_id)
        except InternalKnowledgeError as exc:
            self.last_degradation = exc.code
            self._forget(poi_id)
            return None

        if payload is None:
            # The backend does not own this ID: a supplier-only POI may still be resolvable.
            return self._get_from_detail(poi_id)

        record = self._from_backend(payload)
        if record is None:
            self.last_degradation = "INVALID_RESPONSE"
            self._forget(poi_id)
            return None
        self._remember(record)
        return record

    def _from_backend(self, payload: dict[str, object]) -> PoiRecord | None:
        poi_id = _text(payload.get("poi_id"))
        name = _text(payload.get("name"))
        city_id = _text(payload.get("city_id"))
        category = _text(payload.get("category")) or UNKNOWN_CATEGORY
        coordinate_system = _text(payload.get("coordinate_system"))
        latitude = _number(payload.get("latitude"))
        longitude = _number(payload.get("longitude"))
        if (
            poi_id is None
            or name is None
            or city_id is None
            or coordinate_system not in _COORDINATE_SYSTEMS
            or not _valid_coordinate(longitude, latitude)
        ):
            return None

        raw_data_at = payload.get("data_at")
        data_at = _parse_datetime(raw_data_at)
        if raw_data_at is not None and data_at is None:
            return None

        source = _source_fields(payload.get("source_metadata"))
        if source is None:
            return None
        source_record_id, retrieved_at, freshness = source
        return PoiRecord(
            poi_id=poi_id,
            name=name,
            city_id=city_id,
            latitude=latitude,
            longitude=longitude,
            coordinate_system=coordinate_system,
            category=category,
            data_at=data_at,
            source_record_id=source_record_id,
            retrieved_at=retrieved_at,
            freshness_status=freshness,
        )

    # ------------------------------------------------------------ supplier path

    def _get_from_detail(self, poi_id: str) -> PoiRecord | None:
        try:
            response = self._client.get(
                "/v3/place/detail",
                params={"id": poi_id, "key": self._api_key},
            )
        except httpx.HTTPError:
            return None
        if response.status_code != 200:
            return None
        try:
            body = response.json()
        except ValueError:
            return None
        if not isinstance(body, dict) or body.get("status") != "1":
            return None
        pois = body.get("pois")
        if not isinstance(pois, list) or not pois:
            return None
        record = self._parse(pois[0], str(pois[0].get("adcode", "")), self._now())
        if record is not None:
            self._remember(record)
        return record

    def _parse(
        self,
        poi: object,
        city_id: str,
        retrieved_at: datetime | None = None,
    ) -> PoiRecord | None:
        if not isinstance(poi, dict):
            return None
        poi_id = _text(poi.get("id"))
        name = _text(poi.get("name"))
        adcode = _text(poi.get("adcode"))
        if poi_id is None or name is None or adcode is None:
            return None
        lnglat = parse_lnglat(poi.get("location"))
        if lnglat is None:
            return None
        longitude, latitude = lnglat
        return PoiRecord(
            poi_id=poi_id,
            name=name,
            city_id=adcode,
            latitude=latitude,
            longitude=longitude,
            coordinate_system=_COORDINATE_SYSTEM,
            category=_text(poi.get("type")) or UNKNOWN_CATEGORY,
            data_at=None,
            source_record_id=f"amap-poi:{poi_id}",
            retrieved_at=retrieved_at if retrieved_at is not None else self._now(),
            freshness_status="fresh",
        )

    # ------------------------------------------------------------------- cache

    def _cached(self, poi_id: str) -> PoiRecord | None:
        with self._cache_lock:
            entry = self._cache.get(poi_id)
            if entry is None:
                return None
            record, expires_at = entry
            if self._now() >= expires_at:
                del self._cache[poi_id]
                return None
            return record

    def _remember(self, record: PoiRecord) -> None:
        if not _valid_coordinate(record.longitude, record.latitude):
            return
        with self._cache_lock:
            if len(self._cache) >= _MAX_CACHE_ENTRIES:
                self._prune_locked()
            self._cache[record.poi_id] = (record, self._now() + self._cache_ttl)

    def _forget(self, poi_id: str) -> None:
        with self._cache_lock:
            self._cache.pop(poi_id, None)

    def _prune_locked(self) -> None:
        now = self._now()
        expired = [key for key, (_, expires_at) in self._cache.items() if now >= expires_at]
        for key in expired:
            del self._cache[key]
        if len(self._cache) >= _MAX_CACHE_ENTRIES:
            oldest = min(self._cache, key=lambda key: self._cache[key][1])
            del self._cache[oldest]


def _text(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    stripped = value.strip()
    return stripped or None


def _number(value: object) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return float(value)


def _valid_coordinate(longitude: float | None, latitude: float | None) -> bool:
    return (
        longitude is not None
        and latitude is not None
        and -180.0 <= longitude <= 180.0
        and -90.0 <= latitude <= 90.0
    )


def _parse_datetime(value: object) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo is not None else None


def _source_fields(
    value: object,
) -> tuple[str, datetime, Freshness] | None:
    """Map the first source_metadata item; missing/invalid provenance is not authoritative."""
    if not isinstance(value, list) or not value:
        return None
    first = value[0]
    if not isinstance(first, dict):
        return None
    source_record_id = _text(first.get("source_record_id"))
    retrieved_at = _parse_datetime(first.get("retrieved_at"))
    freshness = _text(first.get("freshness_status"))
    if source_record_id is None or retrieved_at is None or freshness not in _FRESHNESS:
        return None
    return source_record_id, retrieved_at, cast(Freshness, freshness)
