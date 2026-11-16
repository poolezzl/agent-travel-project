"""高德 POI adapter: implements ``PoiPort`` by calling 高德 ``/v5/place/text`` directly.

The S10 backend ``PoiAdapter`` exposes only ``search`` (query by name); there is no get-by-id.
``lookup`` therefore maps name/city to 高德 keyword search and caches every seen record by its
authoritative 高德 ``id``; ``get`` returns a previously seen record or ``None`` (PENDING-R5).
Same-name results are passed through verbatim for upstream disambiguation — never auto-merged.
"""

from __future__ import annotations

import httpx

from app.agents.route_planner.adapters._amap import parse_lnglat
from app.agents.route_planner.models import PoiRecord

_COORDINATE_SYSTEM = "GCJ02"


class AmapPoiAdapter:
    def __init__(self, client: httpx.Client, api_key: str) -> None:
        self._client = client
        self._api_key = api_key
        self._seen: dict[str, PoiRecord] = {}

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

        records: list[PoiRecord] = []
        for poi in pois:
            record = self._parse(poi, city_id)
            if record is not None:
                self._seen[record.poi_id] = record
                records.append(record)
        return records

    def get(self, poi_id: str) -> PoiRecord | None:
        """Return a seen record, or fetch it by authoritative 高德 id once.

        The S10 Java ``PoiAdapter`` only exposes search, so this adapter closes the
        by-id gap directly through ``/v3/place/detail``. Failures return ``None`` and
        never invent coordinates.
        """
        cached = self._seen.get(poi_id)
        if cached is not None:
            return cached
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
        record = self._parse(pois[0], str(pois[0].get("adcode", "")))
        if record is not None:
            self._seen[record.poi_id] = record
        return record

    def _parse(self, poi: object, city_id: str) -> PoiRecord | None:
        if not isinstance(poi, dict):
            return None
        poi_id = poi.get("id")
        name = poi.get("name")
        location = poi.get("location")
        adcode = poi.get("adcode")
        if not isinstance(poi_id, str) or not poi_id:
            return None
        if not isinstance(name, str) or not name:
            return None
        if not isinstance(adcode, str) or not adcode:
            return None
        lnglat = parse_lnglat(location)
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
            category=poi.get("type") if isinstance(poi.get("type"), str) else None,
        )
