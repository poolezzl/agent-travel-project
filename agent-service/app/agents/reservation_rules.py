"""S17 reservation-rules agent: published rules only, deterministic reminders."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
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
    schedule_problem = False
    non_working_seen = False
    holiday_seen = False
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

        closure_weekdays = _closed_weekdays(rule.holiday_exception)
        if day.weekday() in closure_weekdays:
            holiday_seen = True
            degraded.append(f"reservations[{index}].holiday_exception")
            messages.append(f"{point['name']}：预约规则标注当日可能闭馆，请以官方渠道为准")

        reservations.append(_reservation_card(point["point_id"], rule, stale))
        sources.append(rule.source.as_metadata())

        outcome = _timeline_item(point["point_id"], rule, day, current)
        if outcome.item is None:
            if outcome.reason == "not_required":
                continue
            schedule_problem = True
            if outcome.reason == "invalid_timezone":
                degraded.append(f"timeline[{index}].timezone")
                messages.append(f"{point['name']}：预约规则时区无效，无法生成提醒，请以官方渠道为准")
            elif outcome.reason in {"missing_schedule", "invalid_time"}:
                degraded.append(f"timeline[{index}].release_time")
                messages.append(f"{point['name']}：预约规则缺少可解析的放票时间，请以官方渠道为准")
            else:
                degraded.append(f"timeline[{index}]")
            continue

        item = outcome.item
        if item["status"] == "needs_verification":
            non_working_seen = True
            degraded.append(f"timeline[{index}].non_working_day")
            messages.append(f"{point['name']}：放票日为非工作日，实际安排请以官方渠道为准")
        if item["dedupe_key"] in seen_timeline:
            degraded.append(f"timeline[{index}].dedupe_key")
            continue
        seen_timeline.add(item["dedupe_key"])
        timeline.append(item)

    if missing == len(points):
        status = "degraded"
        message = "暂未收录预约信息，请以官方渠道为准"
    elif missing or stale_seen or schedule_problem or non_working_seen or holiday_seen:
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


@dataclass(frozen=True)
class _TimelineOutcome:
    item: dict[str, Any] | None = None
    reason: str | None = None


def _timeline_item(
    point_id: str,
    rule: ReservationRule,
    day: date,
    moment: datetime,
) -> _TimelineOutcome:
    if not rule.reservation_required:
        return _TimelineOutcome(reason="not_required")
    if rule.advance_days is None or not rule.release_time:
        return _TimelineOutcome(reason="missing_schedule")
    try:
        parts = [int(part) for part in rule.release_time.split(":")]
        if len(parts) not in (2, 3):
            raise ValueError("release_time must be HH:MM or HH:MM:SS")
        release_clock = time(
            hour=parts[0],
            minute=parts[1],
            second=parts[2] if len(parts) == 3 else 0,
        )
    except (ValueError, TypeError):
        return _TimelineOutcome(reason="invalid_time")
    zone_name = (rule.release_timezone or "").strip()
    if not zone_name:
        return _TimelineOutcome(reason="invalid_timezone")
    try:
        zone = ZoneInfo(zone_name)
    except (ZoneInfoNotFoundError, ValueError):
        # Never silently reinterpret an unknown zone as UTC: that shifts the
        # reminder by hours and creates a reminder that does not exist.
        return _TimelineOutcome(reason="invalid_timezone")
    release_day = day - timedelta(days=max(0, rule.advance_days))
    release_at = datetime.combine(release_day, release_clock, tzinfo=zone)
    if release_at <= moment:
        return _TimelineOutcome(reason="missed")
    status = "needs_verification" if release_day.weekday() >= 5 else "pending"
    item: dict[str, Any] = {
        "point_id": point_id,
        "policy_id": rule.policy_id,
        "action": "reserve",
        "release_at": release_at.isoformat(),
        "advance_days": rule.advance_days,
        "timezone": rule.release_timezone,
        "dedupe_key": f"{point_id}|{rule.policy_id}|{release_at.isoformat()}",
        "status": status,
    }
    if status == "needs_verification":
        item["warning"] = "放票日为非工作日，实际放票安排请以官方渠道为准"
    return _TimelineOutcome(item=item)


_WEEKDAY_TEXT = {
    "周一": 0,
    "星期一": 0,
    "周1": 0,
    "周二": 1,
    "星期二": 1,
    "周2": 1,
    "周三": 2,
    "星期三": 2,
    "周3": 2,
    "周四": 3,
    "星期四": 3,
    "周4": 3,
    "周五": 4,
    "星期五": 4,
    "周5": 4,
    "周六": 5,
    "星期六": 5,
    "周6": 5,
    "周日": 6,
    "星期日": 6,
    "周天": 6,
    "星期天": 6,
    "周7": 6,
}


def _closed_weekdays(holiday_exception: str | None) -> set[int]:
    """Extract weekdays mentioned by a free-text closure note.

    We only surface what the published note actually says; we never infer an
    official holiday calendar that is not in the rule snapshot.
    """
    if not holiday_exception:
        return set()
    return {
        weekday
        for text, weekday in _WEEKDAY_TEXT.items()
        if text in holiday_exception
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
