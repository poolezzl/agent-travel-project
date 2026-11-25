"""S18 dining/lodging agent: legal POIs only, detour-first, never invent prices."""

from __future__ import annotations

import math
from collections import defaultdict
from datetime import UTC, date, datetime, time
from typing import Any

from app.agents.common import (
    ERROR_INVALID_INPUT,
    InputError,
    dedupe_sources,
    envelope,
    failure,
    isoformat,
    now_utc,
)
from app.knowledge.dining_lodging import PoiSource

DINING_RADIUS_M = 1500.0
LODGING_RADIUS_M = 3000.0
DINING_MAX_DETOUR_MIN = 30.0
LODGING_MAX_DETOUR_MIN = 40.0
MAX_CANDIDATES = 3

_MEAL_MIN = 2
_LODGING_MIN = 1
_MEAL_ANCHOR_MINUTES = {"lunch": 12 * 60, "dinner": 18 * 60}
_CATEGORY_PRICE_BASIS = {"restaurant": "per_person", "lodging": "per_night"}


def run(
    inputs: dict[str, Any],
    context: dict[str, Any],
    *,
    poi_source: PoiSource,
    moment: datetime | None = None,
) -> dict[str, Any]:
    """Return meal/lodging slots from legal POI records, sorted by detour."""
    try:
        day = _parse_date(inputs.get("date"))
        _validate_preferences(inputs)
        city, segments = _parse_itinerary(inputs.get("itinerary_draft"), day)
    except InputError as exc:
        return failure(context, ERROR_INVALID_INPUT, str(exc))

    current = moment or now_utc()
    lunch = _anchor_for(segments, _MEAL_ANCHOR_MINUTES["lunch"])
    dinner = _anchor_for(segments, _MEAL_ANCHOR_MINUTES["dinner"])
    last = segments[-1]

    slots: list[dict[str, Any]] = [
        {
            "kind": "meal",
            "meal": "lunch",
            "path": "meals[0]",
            "date": day.isoformat(),
            "anchor": lunch,
            "category": "restaurant",
            "radius_m": DINING_RADIUS_M,
            "max_detour_min": DINING_MAX_DETOUR_MIN,
            "minimum": _MEAL_MIN,
        },
        {
            "kind": "meal",
            "meal": "dinner",
            "path": "meals[1]",
            "date": day.isoformat(),
            "anchor": dinner,
            "category": "restaurant",
            "radius_m": DINING_RADIUS_M,
            "max_detour_min": DINING_MAX_DETOUR_MIN,
            "minimum": _MEAL_MIN,
        },
        {
            "kind": "lodging",
            "path": "lodging[0]",
            "date": day.isoformat(),
            "anchor": last,
            "category": "lodging",
            "radius_m": LODGING_RADIUS_M,
            "max_detour_min": LODGING_MAX_DETOUR_MIN,
            "minimum": _LODGING_MIN,
        },
    ]

    meals: list[dict[str, Any]] = []
    lodging: list[dict[str, Any]] = []
    sources: list[dict[str, Any]] = []
    degraded: list[str] = []
    filtered_any = False
    timeouts = 0
    source_failures = 0
    empty_slots = 0

    for slot in slots:
        candidates, notes, timed_out, failed = _slot_candidates(
            poi_source,
            city=city,
            category=slot["category"],
            latitude=slot["anchor"]["latitude"],
            longitude=slot["anchor"]["longitude"],
            radius_m=slot["radius_m"],
            max_detour_min=slot["max_detour_min"],
            current=current,
        )
        timeouts += int(timed_out)
        source_failures += int(failed)
        filtered_any = filtered_any or bool(notes)
        if len(candidates) < slot["minimum"] or notes:
            degraded.append(f"{slot['path']}.candidates")
        if not candidates:
            empty_slots += 1

        if slot["kind"] == "meal":
            meals.append(
                {
                    "date": slot["date"],
                    "meal": slot["meal"],
                    "anchor_point_id": slot["anchor"]["point_id"],
                    "candidates": candidates,
                }
            )
        else:
            lodging.append(
                {
                    "date": slot["date"],
                    "area": slot["anchor"].get("district"),
                    "anchor_point_id": slot["anchor"]["point_id"],
                    "candidates": candidates,
                }
            )
        for candidate in candidates:
            sources.append(
                {
                    "source_type": "poi",
                    "source_record_id": candidate["source_record_id"],
                    "retrieved_at": candidate["retrieved_at"],
                    "valid_until": candidate["valid_until"],
                    "freshness_status": candidate["freshness_status"],
                }
            )

    all_empty = empty_slots == len(slots)
    if all_empty:
        status = "degraded"
        if timeouts:
            message = "附近暂无推荐：POI 检索超时，已降级。"
        elif source_failures:
            message = "附近暂无推荐：POI 检索失败，已降级。"
        else:
            message = "附近暂无推荐：未检索到符合条件的餐饮或住宿 POI。"
    elif (
        filtered_any
        or any(len(item["candidates"]) < _MEAL_MIN for item in meals)
        or not lodging[0]["candidates"]
    ):
        status = "partial"
        message = "部分餐次或住宿附近暂无推荐，已按合法 POI 结果降级。"
    else:
        status = "ok"
        message = "餐饮住宿候选来自合法 POI 检索，已按顺路性排序。"

    payload: dict[str, Any] = {
        "status": status,
        "message": message,
        "source_metadata": dedupe_sources(sources),
        "meals": meals,
        "lodging": lodging,
    }
    if degraded:
        payload["degraded_fields"] = sorted(set(degraded))
    return envelope(context, **payload)


def _slot_candidates(
    poi_source: PoiSource,
    *,
    city: str,
    category: str,
    latitude: float,
    longitude: float,
    radius_m: float,
    max_detour_min: float,
    current: datetime,
) -> tuple[list[dict[str, Any]], set[str], bool, bool]:
    try:
        raw = poi_source.nearby(
            city_id=city,
            category=category,
            latitude=latitude,
            longitude=longitude,
            radius_m=radius_m,
        )
    except TimeoutError:
        return [], {"timeout"}, True, False
    except Exception:
        return [], {"source"}, False, True
    if not isinstance(raw, list):
        return [], {"source"}, False, True

    notes: set[str] = set()
    by_id: dict[str, dict[str, Any]] = {}
    for item in raw:
        candidate, reason = _normalise(
            item,
            expected_category=category,
            expected_city=city,
            max_detour_min=max_detour_min,
            current=current,
        )
        if candidate is None:
            if reason:
                notes.add(reason)
            continue
        if reason:
            notes.add(reason)
        by_id[candidate["poi_id"]] = candidate

    candidates = list(by_id.values())
    groups: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for candidate in candidates:
        groups[(candidate["category"], candidate["name"].strip().casefold())].append(candidate)
    ambiguous = {
        candidate["poi_id"]
        for group in groups.values()
        if len({item["poi_id"] for item in group}) > 1
        for candidate in group
    }
    if ambiguous:
        notes.add("same_name")
        candidates = [candidate for candidate in candidates if candidate["poi_id"] not in ambiguous]

    candidates.sort(key=lambda item: (float(item["detour_min"]), item["poi_id"]))
    return candidates[:MAX_CANDIDATES], notes, False, False


def _normalise(
    item: Any,
    *,
    expected_category: str,
    expected_city: str,
    max_detour_min: float,
    current: datetime,
) -> tuple[dict[str, Any] | None, str | None]:
    if not isinstance(item, dict):
        return None, "invalid"
    poi_id = _text(item.get("poi_id"))
    name = _text(item.get("name"))
    city_id = _text(item.get("city_id"))
    category = _text(item.get("category"))
    if not poi_id or not name or not city_id or category != expected_category:
        return None, "invalid"
    if category not in _CATEGORY_PRICE_BASIS:
        return None, "invalid"
    if city_id != expected_city:
        return None, "city_mismatch"

    latitude = _finite(item.get("latitude"))
    longitude = _finite(item.get("longitude"))
    if (
        latitude is None
        or longitude is None
        or not -90 <= latitude <= 90
        or not -180 <= longitude <= 180
    ):
        return None, "invalid"
    retrieved_at = _parse_instant(item.get("retrieved_at"))
    if retrieved_at is None:
        return None, "invalid"
    valid_until = _parse_instant(item.get("valid_until"))
    if item.get("valid_until") is not None and valid_until is None:
        return None, "valid_until"
    if valid_until is not None and valid_until <= current:
        return None, "expired"

    source_record_id = _text(item.get("source_record_id"))
    if not source_record_id:
        return None, "invalid"
    detour_min = _finite(item.get("detour_min"))
    if detour_min is None or detour_min < 0:
        return None, "detour_min"
    if detour_min > max_detour_min:
        return None, "detour"

    price, price_ok = _price(item.get("price"), _CATEGORY_PRICE_BASIS[category])
    freshness = item.get("freshness_status")
    if freshness not in {"fresh", "stale", "unknown"}:
        freshness = "unknown"
    candidate = {
        "poi_id": poi_id,
        "name": name,
        "city_id": city_id,
        "category": category,
        "latitude": latitude,
        "longitude": longitude,
        "price": price,
        "retrieved_at": isoformat(retrieved_at),
        "valid_until": isoformat(valid_until) if valid_until is not None else None,
        "freshness_status": freshness,
        "source_record_id": source_record_id,
        "detour_min": round(detour_min, 3),
    }
    return candidate, None if price_ok else "price"


def _price(value: Any, basis: str) -> tuple[dict[str, Any], bool]:
    unknown = {
        "basis": basis,
        "kind": "unknown",
        "currency": None,
        "amount_minor": None,
        "min_minor": None,
        "max_minor": None,
    }
    if value is None:
        return unknown, True
    if not isinstance(value, dict):
        return unknown, False
    kind = value.get("kind")
    currency = value.get("currency")
    if kind == "unknown":
        return unknown, True
    if kind not in {"exact", "range"} or not isinstance(currency, str) or not _currency(currency):
        return unknown, False
    if kind == "exact":
        amount = _money(value.get("amount_minor"))
        if amount is None:
            return unknown, False
        return {**unknown, "kind": "exact", "currency": currency, "amount_minor": amount}, True
    low = _money(value.get("min_minor"))
    high = _money(value.get("max_minor"))
    if low is None or high is None or low > high:
        return unknown, False
    return (
        {**unknown, "kind": "range", "currency": currency, "min_minor": low, "max_minor": high},
        True,
    )


def _currency(value: str) -> bool:
    return len(value) == 3 and value.isalpha() and value.isupper()


def _money(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return None
    return value


def _parse_date(value: Any) -> date:
    if not isinstance(value, str):
        raise InputError("date 必须是 YYYY-MM-DD")
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise InputError("date 必须是 YYYY-MM-DD") from exc


def _validate_preferences(inputs: dict[str, Any]) -> None:
    taste = inputs.get("taste_preference", [])
    if not isinstance(taste, list) or any(not isinstance(item, str) for item in taste):
        raise InputError("taste_preference 必须是字符串数组")
    companion = inputs.get("companion")
    if companion is not None and not isinstance(companion, str):
        raise InputError("companion 必须是字符串")
    budget = inputs.get("budget")
    if budget is not None and not isinstance(budget, dict):
        raise InputError("budget 必须是对象")


def _parse_itinerary(value: Any, day: date) -> tuple[str, list[dict[str, Any]]]:
    if not isinstance(value, dict):
        raise InputError("itinerary_draft 必须是对象")
    city = _text(value.get("city_id"))
    if not city:
        raise InputError("itinerary_draft.city_id 不能为空")
    days = value.get("days")
    if not isinstance(days, list) or not days:
        raise InputError("itinerary_draft.days 必须是非空数组")
    selected = next((item for item in days if _is_day(item, day.isoformat())), None)
    if selected is None:
        raise InputError("itinerary_draft 缺少 date 对应的一天")
    raw_segments = selected.get("segments")
    if not isinstance(raw_segments, list) or not raw_segments:
        raise InputError("itinerary_draft.days[].segments 必须是非空数组")
    segments = [_parse_segment(item) for item in raw_segments]
    return city, segments


def _is_day(value: Any, iso: str) -> bool:
    return isinstance(value, dict) and value.get("date") == iso


def _parse_segment(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise InputError("segment 必须是对象")
    point_id = _text(value.get("point_id"))
    name = _text(value.get("name"))
    latitude = _finite(value.get("latitude"))
    longitude = _finite(value.get("longitude"))
    if not point_id or not name or latitude is None or longitude is None:
        raise InputError("segment 缺少 point_id/name/坐标")
    if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
        raise InputError("segment 坐标越界")
    start_minutes = _parse_clock(value.get("start_time"))
    end_minutes = _parse_clock(value.get("end_time"))
    return {
        "point_id": point_id,
        "name": name,
        "district": _text(value.get("district")) or None,
        "latitude": latitude,
        "longitude": longitude,
        "start_minutes": start_minutes,
        "end_minutes": end_minutes,
    }


def _parse_clock(value: Any) -> int | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise InputError("segment 时间必须是 HH:MM")
    try:
        parsed = time.fromisoformat(value)
    except ValueError as exc:
        raise InputError("segment 时间必须是 HH:MM") from exc
    return parsed.hour * 60 + parsed.minute


def _anchor_for(segments: list[dict[str, Any]], target_minutes: int) -> dict[str, Any]:
    timed = [segment for segment in segments if segment["end_minutes"] is not None]
    if not timed:
        return segments[0] if target_minutes < 18 * 60 else segments[-1]
    return min(
        timed,
        key=lambda segment: (
            abs(segment["end_minutes"] - target_minutes),
            segment["end_minutes"],
        ),
    )


def _parse_instant(value: Any) -> datetime | None:
    if value is None:
        return None
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)


def _text(value: Any) -> str:
    if not isinstance(value, str):
        return ""
    return value.strip()


def _finite(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    number = float(value)
    return number if math.isfinite(number) else None


__all__ = ["run"]
