"""S17 reservation-rules agent: published rules only, deterministic reminders."""

from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from app.agents.common import (
    ERROR_INVALID_INPUT,
    InputError,
    dedupe_sources,
    envelope,
    failure,
    now_utc,
    validate_points,
)
from app.knowledge.catalog import PublishedCatalog, ReservationRule

_DATE_ERROR = "date 必须是 YYYY-MM-DD"


def run(
    inputs: dict[str, Any],
    catalog: PublishedCatalog,
    context: dict[str, Any],
    *,
    moment: datetime | None = None,
) -> dict[str, Any]:
    """Return reservation cards and de-duplicated reminder candidates."""
    try:
        points = validate_points(inputs.get("points"))
    except InputError as exc:
        return failure(context, ERROR_INVALID_INPUT, str(exc))

    raw_date = inputs.get("date")
    if not isinstance(raw_date, str):
        return failure(context, ERROR_INVALID_INPUT, _DATE_ERROR)
    try:
        day = date.fromisoformat(raw_date)
    except ValueError:
        return failure(context, ERROR_INVALID_INPUT, _DATE_ERROR)

    city_id = inputs.get("city_id")
    if city_id is not None and (not isinstance(city_id, str) or not city_id.strip()):
        return failure(context, ERROR_INVALID_INPUT, "city_id 必须是非空字符串")
    city = city_id.strip() if isinstance(city_id, str) else None

    current = moment or now_utc()
    reservations: list[dict[str, Any]] = []
    timeline: list[dict[str, Any]] = []
    sources: list[dict[str, Any]] = []
    degraded: list[str] = []
    messages: list[str] = []
    missing = 0
    stale_seen = False
    timeline_skipped = False
    seen_timeline: set[str] = set()

    for index, point in enumerate(points):
        candidates, rules = catalog.rules_for(
            point_id=point["point_id"],
            name=point["name"],
            city_id=city,
            day=day,
            moment=current,
        )

        if len(candidates) > 1 or not candidates or not rules:
            missing += 1
            degraded.append(f"reservations[{index}].policy_id")
            messages.append(f"{point['name']}：暂未收录预约信息")
            continue

        rule = rules[0]
        stale = rule.is_stale(current)
        stale_seen = stale_seen or stale
        if stale:
            degraded.append(f"reservations[{index}].stale")
            messages.append(f"{point['name']}：预约规则超过 {rule.stale_after_days} 天未核验，请以官方渠道为准")

        reservations.append(_reservation_card(point["point_id"], rule, stale))
        sources.append(rule.source.as_metadata())

        item = _timeline_item(point["point_id"], rule, day, current)
        if item is None:
            timeline_skipped = True
            degraded.append(f"timeline[{index}]")
            continue
        if item["dedupe_key"] in seen_timeline:
            degraded.append(f"timeline[{index}].dedupe_key")
            continue
        seen_timeline.add(item["dedupe_key"])
        timeline.append(item)

    if missing == len(points):
        status = "degraded"
        message = "暂未收录预约信息，请以官方渠道为准"
    elif missing or stale_seen or timeline_skipped:
        status = "partial"
        message = "；".join(_unique(messages)) or "部分预约信息未收录或已过期"
    else:
        status = "ok"
        message = "预约信息来自已发布规则库"

    payload: dict[str, Any] = {
        "status": status,
        "message": message,
        "source_metadata": dedupe_sources(sources),
        "reservations": reservations,
        "timeline": timeline,
    }
    if degraded:
        payload["degraded_fields"] = sorted(set(degraded))
    return envelope(context, **payload)


def _reservation_card(point_id: str, rule: ReservationRule, stale: bool) -> dict[str, Any]:
    card: dict[str, Any] = {
        "point_id": point_id,
        "policy_id": rule.policy_id,
        "ticket_type": rule.ticket_type,
        "effective_from": rule.effective_from,
        "effective_until": rule.effective_until,
        "timezone": rule.release_timezone,
        "last_verified": rule.last_verified_at,
        "stale": stale,
        "reservation_required": rule.reservation_required,
    }
    if rule.audience:
        card["audience"] = rule.audience
    if rule.channel:
        card["channel"] = rule.channel
    if rule.free_condition:
        card["free_condition"] = rule.free_condition
    if rule.holiday_exception:
        card["holiday_exception"] = rule.holiday_exception
    if rule.currency is not None and rule.amount_minor is not None:
        card["currency"] = rule.currency
        card["amount_minor"] = rule.amount_minor
    # Missing price stays absent; never coerce unknown to zero.
    return card


def _timeline_item(
    point_id: str,
    rule: ReservationRule,
    day: date,
    moment: datetime,
) -> dict[str, Any] | None:
    if not rule.reservation_required:
        return None
    if rule.advance_days is None or not rule.release_time:
        return None
    try:
        hour, minute = (int(part) for part in rule.release_time.split(":"))
        release_clock = time(hour=hour, minute=minute)
    except (ValueError, TypeError):
        return None
    try:
        zone = ZoneInfo(rule.release_timezone)
    except ZoneInfoNotFoundError:
        zone = timezone.utc
    release_day = day - timedelta(days=max(0, rule.advance_days))
    release_at = datetime.combine(release_day, release_clock, tzinfo=zone)
    if release_at <= moment:
        return None
    return {
        "point_id": point_id,
        "policy_id": rule.policy_id,
        "action": "reserve",
        "release_at": release_at.isoformat(),
        "advance_days": rule.advance_days,
        "timezone": rule.release_timezone,
        "dedupe_key": f"{point_id}|{rule.policy_id}|{release_at.isoformat()}",
        "status": "pending",
    }


def _unique(values: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value not in seen:
            seen.add(value)
            result.append(value)
    return result


__all__ = ["run"]
