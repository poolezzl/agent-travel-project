"""Shared constants and deterministic time helpers for the S19 orchestrator."""

from __future__ import annotations

from datetime import date, datetime, time

CITY_NAMES = {
    "beijing": "北京",
    "shanghai": "上海",
    "hangzhou": "杭州",
    "nanjing": "南京",
    "suzhou": "苏州",
    "chengdu": "成都",
    "xian": "西安",
    "chongqing": "重庆",
    "guangzhou": "广州",
    "xiamen": "厦门",
}

CITY_IDS_BY_NAME = {name: city_id for city_id, name in CITY_NAMES.items()}

TRANSPORT_MODES = frozenset({"步行", "公交", "地铁", "打车", "混合"})

WEEKDAY_TEXT = ("周一", "周二", "周三", "周四", "周五", "周六", "周日")

DAY_WINDOW_START = "09:00"
# Leave one hour for lunch: the route feasibility gate must not consume it.
DAY_WINDOW_END = "17:00"
LUNCH_START = "12:00"
LUNCH_END = "13:00"
DEFAULT_VISIT_MINUTES = 90.0
MAX_DAYS = 5
MAX_POINTS_PER_DAY = 3
MAX_MUST_VISIT = 10


class StageInputError(ValueError):
    """Raised when a stage input violates the S19 request contract."""


def city_id_for(value: object) -> str | None:
    """Accept either ``beijing`` or ``北京`` and return the canonical city id."""
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text:
        return None
    if text in CITY_NAMES:
        return text
    if text in CITY_IDS_BY_NAME:
        return CITY_IDS_BY_NAME[text]
    lowered = text.casefold()
    return lowered if lowered in CITY_NAMES else None


def parse_hhmm(value: str) -> int:
    """Parse ``HH:MM`` into minutes after midnight."""
    parts = value.split(":")
    if len(parts) != 2:
        raise StageInputError(f"非法时间 {value!r}")
    hour, minute = int(parts[0]), int(parts[1])
    if not 0 <= hour <= 23 or not 0 <= minute <= 59:
        raise StageInputError(f"非法时间 {value!r}")
    return hour * 60 + minute


def format_hhmm(minutes: float) -> str:
    total = int(round(minutes))
    total = max(0, min(total, 23 * 60 + 59))
    return f"{total // 60:02d}:{total % 60:02d}"


def weekday_text(day: date) -> str:
    return WEEKDAY_TEXT[day.weekday()]


def isoformat_utc(value: datetime) -> str:
    return value.astimezone().isoformat()


def time_text_to_minutes(value: str) -> int:
    return parse_hhmm(value)


def combine(day: date, minutes: int) -> datetime:
    return datetime.combine(day, time(hour=minutes // 60, minute=minutes % 60))


__all__ = [
    "CITY_NAMES",
    "CITY_IDS_BY_NAME",
    "TRANSPORT_MODES",
    "WEEKDAY_TEXT",
    "DAY_WINDOW_START",
    "DAY_WINDOW_END",
    "LUNCH_START",
    "LUNCH_END",
    "DEFAULT_VISIT_MINUTES",
    "MAX_DAYS",
    "MAX_POINTS_PER_DAY",
    "MAX_MUST_VISIT",
    "StageInputError",
    "city_id_for",
    "parse_hhmm",
    "format_hhmm",
    "weekday_text",
    "isoformat_utc",
    "combine",
]
