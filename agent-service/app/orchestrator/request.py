"""S19 stage 1: deterministic requirement parsing and feasibility precheck."""

from __future__ import annotations

from datetime import date, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

from app.orchestrator.common import (
    CITY_NAMES,
    MAX_DAYS,
    MAX_MUST_VISIT,
    TRANSPORT_MODES,
    StageInputError,
    city_id_for,
)

_DATE_ERROR = "日期必须是 YYYY-MM-DD"


def parse_request(raw: Any, *, today: date | None = None) -> dict[str, Any]:
    """Validate the immutable planning payload and return the internal constraints."""
    if not isinstance(raw, dict):
        raise StageInputError("request 必须是对象")

    city_id = city_id_for(raw.get("city_id") or raw.get("city"))
    if city_id is None:
        raise StageInputError("暂不支持该城市；M1 首批城市见开发决策基线")

    start = _date(raw.get("start_date"), "start_date")
    end = _date(raw.get("end_date"), "end_date")
    if end < start:
        raise StageInputError("end_date 不得早于 start_date")
    day_count = (end - start).days + 1
    if day_count > MAX_DAYS:
        raise StageInputError(f"单次规划最多 {MAX_DAYS} 天")

    reference = today or datetime.now(ZoneInfo("Asia/Shanghai")).date()
    if start < reference:
        raise StageInputError("开始日期不得早于今天")

    party_size = _int(raw.get("party_size", 1), "party_size", minimum=1, maximum=10)
    plan_count = _int(raw.get("plan_count", 1), "plan_count", minimum=1, maximum=3)

    must_visit = _string_list(raw.get("must_visit"), "must_visit", limit=MAX_MUST_VISIT)
    preferences = _preferences(raw.get("preferences"))

    transport = raw.get("transport")
    transport_preference = (
        transport.strip()
        if isinstance(transport, str) and transport.strip() in TRANSPORT_MODES
        else "混合"
    )

    budget = _money(raw.get("budget"))

    return {
        "constraints": {
            "city_id": city_id,
            "city_name": CITY_NAMES[city_id],
            "start_date": start.isoformat(),
            "end_date": end.isoformat(),
            "dates": [(start + timedelta(days=offset)).isoformat() for offset in range(day_count)],
            "day_count": day_count,
            "party_size": party_size,
            "budget": budget,
            "must_visit": must_visit,
            "preferences": preferences,
            "transport_preference": transport_preference,
            "plan_count": plan_count,
        },
        "precheck": {
            "ok": True,
            "issues": [] if must_visit else ["未指定必去点，将仅使用已发布城市候选"],
        },
    }


def _date(value: Any, field: str) -> date:
    if not isinstance(value, str):
        raise StageInputError(f"{field}：{_DATE_ERROR}")
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise StageInputError(f"{field}：{_DATE_ERROR}") from exc


def _int(value: Any, field: str, *, minimum: int, maximum: int) -> int:
    if isinstance(value, bool):
        raise StageInputError(f"{field} 必须是整数")
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise StageInputError(f"{field} 必须是整数") from exc
    if not minimum <= parsed <= maximum:
        raise StageInputError(f"{field} 必须在 {minimum}..{maximum} 之间")
    return parsed


def _string_list(value: Any, field: str, *, limit: int) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list):
        raise StageInputError(f"{field} 必须是字符串数组")
    result: list[str] = []
    for item in value:
        if not isinstance(item, str) or not item.strip():
            raise StageInputError(f"{field} 必须是非空字符串数组")
        text = item.strip()
        if text not in result:
            result.append(text)
    if len(result) > limit:
        raise StageInputError(f"{field} 最多 {limit} 项")
    return result


def _preferences(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        parts = value.replace("，", ",").replace("、", ",").replace(" ", ",").split(",")
        return [part.strip() for part in parts if part.strip()]
    return _string_list(value, "preferences", limit=10)


def _money(value: Any) -> dict[str, Any] | None:
    if value is None:
        return None
    if not isinstance(value, dict):
        raise StageInputError("budget 必须是 currency/amount_minor 对象")
    currency = value.get("currency")
    amount = value.get("amount_minor")
    if not isinstance(currency, str) or not _currency(currency):
        raise StageInputError("budget.currency 必须是 ISO 4217 三位代码")
    if isinstance(amount, bool) or not isinstance(amount, int) or amount < 0:
        raise StageInputError("budget.amount_minor 必须是非负整数")
    return {"currency": currency, "amount_minor": amount, "basis": "total"}


def _currency(value: str) -> bool:
    return len(value) == 3 and value.isalpha() and value.isupper()


__all__ = ["parse_request"]
