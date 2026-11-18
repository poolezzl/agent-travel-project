"""S20 revision agent: classify intent and produce bounded change instructions."""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Iterable
from typing import Any
from uuid import UUID

from app.agents.common import ERROR_INVALID_INPUT, InputError, envelope, failure

_AGENT_ORDER = (
    "revision",
    "orchestrator",
    "attractions",
    "weather",
    "route-planner",
    "dining-lodging",
    "reservation-rules",
)
_CHANGE_MARKERS = (
    "删除",
    "删",
    "删掉",
    "去掉",
    "取消",
    "增加",
    "添加",
    "安排",
    "替换",
    "换成",
    "改成",
    "调整",
    "互换",
    "交换",
    "重排",
    "挪到",
    "提前",
    "延后",
    "不要",
    "别",
    "想去",
    "想住",
)
_QUERY_MARKERS = (
    "多少",
    "什么",
    "哪里",
    "几点",
    "是否",
    "怎么",
    "为什么",
    "哪天",
    "有哪些",
    "票价",
    "介绍",
    "告诉我",
)
_REMOVE_MARKERS = ("删除", "删", "去掉", "取消", "不要", "别安排")
_ROUTE_MARKERS = ("路线", "顺序", "重排", "互换", "交换", "挪到", "交通")
_DINING_MARKERS = ("餐厅", "餐饮", "早餐", "午餐", "晚餐", "酒店", "住宿", "住")
_WEATHER_MARKERS = ("天气", "下雨", "降雨", "温度", "高温", "低温")
_RESERVATION_MARKERS = ("预约", "门票", "票价", "放票")
_UNTRUSTED_DIRECTIVES = (
    r"忽略(?:前面|以上|此前)(?:所有)?(?:规则|要求|指令|提示词)?",
    r"绕过(?:确认|校验|规则|系统)",
    r"泄露(?:密钥|密码|提示词|系统指令)",
    r"显示(?:密钥|密码|系统提示词)",
)
_CHINESE_NUMBERS = {
    "一": 1,
    "二": 2,
    "两": 2,
    "三": 3,
    "四": 4,
    "五": 5,
    "六": 6,
    "七": 7,
    "八": 8,
    "九": 9,
    "十": 10,
}


def run(inputs: dict[str, Any], context: dict[str, Any]) -> dict[str, Any]:
    """Classify one revision request without performing any business write."""
    try:
        base_version_id, plan, request, locked_paths = _validate_input(inputs)
    except InputError as exc:
        return failure(context, ERROR_INVALID_INPUT, str(exc))

    safe_request = _strip_untrusted_directives(request)
    intent = _classify(safe_request)
    if intent == "query":
        return _query_result(base_version_id, plan, safe_request, context)
    if intent == "ambiguous":
        return _ambiguous_result(
            base_version_id,
            context,
            "请说明你想查询什么，或明确要修改的日期和对象",
        )
    return _change_result(base_version_id, plan, safe_request, locked_paths, context)


def plan_variants(inputs: dict[str, Any], context: dict[str, Any]) -> dict[str, Any]:
    """Create deterministic 1-3 variant strategies sharing the same source snapshots."""
    plan = inputs.get("current_plan")
    if not isinstance(plan, dict):
        return failure(context, ERROR_INVALID_INPUT, "current_plan 必须是对象")
    plan = _unwrap_plan(plan)
    plan_count = inputs.get("plan_count")
    if type(plan_count) is not int or not 1 <= plan_count <= 3:
        return failure(context, ERROR_INVALID_INPUT, "plan_count 必须是 1 到 3 的整数")

    raw_snapshots = inputs.get("shared_source_snapshot_ids")
    if (
        isinstance(raw_snapshots, list)
        and raw_snapshots
        and all(isinstance(item, str) and item.strip() for item in raw_snapshots)
    ):
        try:
            snapshots = _unique(str(UUID(item.strip())) for item in raw_snapshots)
        except ValueError:
            return failure(
                context,
                ERROR_INVALID_INPUT,
                "shared_source_snapshot_ids 必须是 UUID 数组",
            )
    else:
        return failure(
            context,
            ERROR_INVALID_INPUT,
            "shared_source_snapshot_ids 必须是非空 UUID 数组",
        )
    if any(source.get("freshness_status") != "fresh" for source in _plan_sources(plan)):
        return failure(
            context,
            "SOURCE_SNAPSHOT_STALE",
            "多方案只能复用新鲜来源快照",
        )

    profiles = _variant_profiles(plan_count)
    must_visit_ids = _must_visit_ids(plan)
    base_hash = _digest(plan)
    variants: list[dict[str, Any]] = []
    for profile in profiles:
        rerun = ["orchestrator", "route-planner"]
        if profile["budget_tier"] != "standard":
            rerun.append("dining-lodging")
        strategy = {
            **profile,
            "difference_summary": _variant_difference_summary(profile),
            "preserved_must_visit_point_ids": must_visit_ids,
            "shared_source_snapshot_ids": snapshots,
            "rerun_agents": rerun,
            "quality_gate_required": True,
            "required_result_metrics": [
                "quality_score",
                "total_duration_ms",
                "total_cost_minor",
            ],
        }
        strategy["variant_id"] = f"variant-{_digest({'base': base_hash, **strategy})[:16]}"
        variants.append(strategy)

    dedupe_key = _digest(
        {
            "base": base_hash,
            "plan_count": plan_count,
            "snapshots": snapshots,
            "must_visit": must_visit_ids,
        }
    )
    return envelope(
        context,
        status="ok",
        source_metadata=_plan_sources(plan),
        requested_count=plan_count,
        shared_source_snapshot_ids=snapshots,
        shared_agents=["weather", "attractions", "reservation-rules"],
        variants=variants,
        dedupe_key=dedupe_key,
        summary="多方案共享采集快照，仅编排、路线和预算差异部分进入重跑白名单",
    )


def _validate_input(inputs: dict[str, Any]) -> tuple[str, dict[str, Any], str, list[str]]:
    base_version_id = inputs.get("base_version_id")
    if not isinstance(base_version_id, str) or not base_version_id.strip():
        raise InputError("base_version_id 不能为空")
    plan = inputs.get("current_plan")
    if not isinstance(plan, dict):
        raise InputError("current_plan 必须是对象")
    plan = _unwrap_plan(plan)
    if not isinstance(plan.get("days"), list):
        raise InputError("current_plan.days 必须是数组")
    request = inputs.get("revision_request")
    if not isinstance(request, str) or not request.strip():
        raise InputError("revision_request 不能为空")
    locked_paths = inputs.get("locked_paths", [])
    if not isinstance(locked_paths, list) or any(
        not isinstance(path, str) or not path.startswith("/") for path in locked_paths
    ):
        raise InputError("locked_paths 必须是 JSON Pointer 风格字符串数组")
    return base_version_id.strip(), plan, request.strip(), list(dict.fromkeys(locked_paths))


def _unwrap_plan(plan: dict[str, Any]) -> dict[str, Any]:
    nested = plan.get("plan")
    return nested if isinstance(nested, dict) else plan


def _strip_untrusted_directives(request: str) -> str:
    result = request
    for pattern in _UNTRUSTED_DIRECTIVES:
        result = re.sub(pattern, "", result, flags=re.IGNORECASE)
    result = re.sub(r"(?:API[ _-]?KEY|LLM_API_KEY|密码|密钥)", "敏感配置", result, flags=re.I)
    return result.strip(" ，,。;；：:")


def _classify(request: str) -> str:
    if not request:
        return "ambiguous"
    imperative_markers = tuple(marker for marker in _CHANGE_MARKERS if marker != "安排")
    if any(marker in request for marker in imperative_markers):
        return "change"
    if "?" in request or "？" in request or any(marker in request for marker in _QUERY_MARKERS):
        return "query"
    if "安排" in request:
        return "change"
    return "ambiguous"


def _query_result(
    base_version_id: str,
    plan: dict[str, Any],
    request: str,
    context: dict[str, Any],
) -> dict[str, Any]:
    price_query = any(marker in request for marker in ("票价", "多少钱", "费用"))
    answer = (
        _price_answer(plan, request)
        if price_query
        else _day_answer(plan, request) or _segment_answer(plan, request)
    )
    if answer is None and not price_query:
        trip = plan.get("trip")
        if isinstance(trip, dict) and any(key in request for key in ("行程", "几天", "日期")):
            city = trip.get("city_name") or trip.get("city_id") or "当前城市"
            count = trip.get("day_count") or len(plan.get("days", []))
            answer = f"当前版本是{city}{count}日行程。"

    if answer is None:
        return envelope(
            context,
            status="partial",
            message="当前行程版本没有足够的已发布事实回答该问题",
            degraded_fields=["answer"],
            source_metadata=_plan_sources(plan),
            intent="query",
            base_version_id=base_version_id,
            answer="当前版本未包含这项事实，请补充问题对象或等待知识库核验。",
            summary="只读问答；未创建版本或变更",
        )
    return envelope(
        context,
        status="ok",
        source_metadata=_plan_sources(plan),
        intent="query",
        base_version_id=base_version_id,
        answer=answer,
        summary="只读问答；未创建版本或变更",
    )


def _ambiguous_result(
    base_version_id: str,
    context: dict[str, Any],
    question: str,
) -> dict[str, Any]:
    return envelope(
        context,
        status="ok",
        source_metadata=[],
        intent="ambiguous",
        base_version_id=base_version_id,
        clarification_question=question,
        summary="需要澄清；未创建版本或变更",
    )


def _change_result(
    base_version_id: str,
    plan: dict[str, Any],
    request: str,
    locked_paths: list[str],
    context: dict[str, Any],
) -> dict[str, Any]:
    days = _extract_days(plan, request)
    matches = _matching_segments(plan, request)
    if not days and matches:
        days = sorted({match[0] for match in matches})
    if not days and any(marker in request for marker in ("酒店", "住宿", "预算档")):
        days = list(range(1, len(plan.get("days", [])) + 1))

    if ("一个景点" in request or "某个景点" in request) and not matches:
        return _ambiguous_result(
            base_version_id,
            context,
            "请说明要修改的景点名称，系统不会自行选择删除对象",
        )
    if not days:
        return _ambiguous_result(
            base_version_id,
            context,
            "请说明修改涉及哪一天或哪个具体景点",
        )
    if any(day < 1 or day > len(plan.get("days", [])) for day in days):
        return _ambiguous_result(base_version_id, context, "指定日期不在当前行程范围内")

    changes = _build_changes(plan, request, days, matches)
    if not changes:
        return _ambiguous_result(base_version_id, context, "请明确要增加、删除、替换或重排的内容")

    protected_paths = _protected_paths(plan, changes, locked_paths)
    cross_day = len(days) > 1
    needs_confirm = bool(protected_paths or cross_day)
    for change in changes:
        change["needs_confirm"] = needs_confirm or _path_is_protected(
            change["path"], protected_paths
        )
    agents = _affected_agents(request, changes, cross_day)
    preserved_days = [day for day in range(1, len(plan.get("days", [])) + 1) if day not in days]
    impact_scope = {
        "affected_days": days,
        "affected_agents": agents,
        "locked_paths": locked_paths,
        "protected_paths": protected_paths,
        "preserved_days": preserved_days,
        "needs_confirm": needs_confirm,
    }
    return envelope(
        context,
        status="ok",
        source_metadata=_change_sources(plan, context),
        intent="change",
        base_version_id=base_version_id,
        impact_scope=impact_scope,
        changes=changes,
        summary=f"定位到 {len(days)} 个受影响日期和 {len(changes)} 项原子变更",
    )


def _extract_days(plan: dict[str, Any], request: str) -> list[int]:
    days: set[int] = set()
    for token in re.findall(r"第\s*([一二两三四五六七八九十\d]+)\s*天", request):
        number = _day_number(token)
        if number is not None:
            days.add(number)
    for index, day in enumerate(plan.get("days", []), start=1):
        if isinstance(day, dict) and isinstance(day.get("date"), str) and day["date"] in request:
            days.add(index)
    if not days and any(marker in request for marker in ("两天互换", "两天交换")):
        if len(plan.get("days", [])) == 2:
            days.update((1, 2))
    return sorted(days)


def _day_number(token: str) -> int | None:
    if token.isdigit():
        return int(token)
    if token in _CHINESE_NUMBERS:
        return _CHINESE_NUMBERS[token]
    if token.startswith("十") and token[1:] in _CHINESE_NUMBERS:
        return 10 + _CHINESE_NUMBERS[token[1:]]
    return None


def _matching_segments(plan: dict[str, Any], request: str) -> list[tuple[int, int, dict[str, Any]]]:
    matches: list[tuple[int, int, dict[str, Any]]] = []
    for day_number, day in enumerate(plan.get("days", []), start=1):
        if not isinstance(day, dict):
            continue
        for position, segment in enumerate(day.get("segments", []), start=1):
            if not isinstance(segment, dict):
                continue
            name = segment.get("name")
            if (
                isinstance(name, str)
                and name
                and (
                    name in request or (len(_short_name(name)) > 1 and _short_name(name) in request)
                )
            ):
                sequence = segment.get("sequence_no")
                index = sequence if isinstance(sequence, int) and sequence > 0 else position
                matches.append((day_number, index, segment))
    return matches


def _short_name(name: str) -> str:
    return re.sub(r"(?:博物院|博物馆|公园|景区|风景区)$", "", name)


def _build_changes(
    plan: dict[str, Any],
    request: str,
    days: list[int],
    matches: list[tuple[int, int, dict[str, Any]]],
) -> list[dict[str, Any]]:
    if any(marker in request for marker in ("互换", "交换")):
        if len(days) != 2:
            return []
        first, second = days
        return [
            {"operation": "reorder", "path": f"/days/{first}", "value": {"swap_with_day": second}},
            {"operation": "reorder", "path": f"/days/{second}", "value": {"swap_with_day": first}},
        ]
    if any(marker in request for marker in _REMOVE_MARKERS):
        return [
            {"operation": "remove", "path": f"/days/{day}/segments/{position}"}
            for day, position, _ in matches
            if day in days
        ]
    if any(marker in request for marker in ("酒店", "住宿", "预算档", "想住")):
        target = _target_name(request, r"换成|改成|想住")
        if not target:
            return []
        return [
            {
                "operation": "replace",
                "path": f"/days/{day}/lodging",
                "value": {"target_name": target},
            }
            for day in days
        ]
    if any(marker in request for marker in _ROUTE_MARKERS):
        return [
            {
                "operation": "reorder",
                "path": f"/days/{day}/segments",
            }
            for day in days
        ]
    target = _target_name(request, r"换成|改成|替换为")
    if target and matches:
        return [
            {
                "operation": "replace",
                "path": f"/days/{day}/segments/{position}",
                "value": {"target_name": target},
            }
            for day, position, _ in matches
            if day in days
        ]
    target = _target_name(request, r"增加|添加|安排|想去")
    if not target:
        return []
    return [
        {
            "operation": "add",
            "path": f"/days/{day}/segments",
            "value": {"target_name": target},
        }
        for day in days
    ]


def _target_name(request: str, markers: str) -> str | None:
    match = re.search(rf"(?:{markers})[\s：:]*[「“]?(?P<name>[^，,。；;？！?!「」“”]+)", request)
    return match["name"].strip() if match else None


def _affected_agents(
    request: str,
    changes: list[dict[str, Any]],
    cross_day: bool,
) -> list[str]:
    selected = {"revision", "orchestrator"}
    paths = " ".join(str(change["path"]) for change in changes)
    segment_changes = [change for change in changes if "/segments" in str(change["path"])]
    if segment_changes or cross_day:
        selected.update(("route-planner", "dining-lodging"))
    if cross_day:
        selected.add("reservation-rules")
    if any(change["operation"] in {"add", "replace"} for change in segment_changes):
        selected.update(("attractions", "reservation-rules"))
    if any(marker in request for marker in _ROUTE_MARKERS):
        selected.add("route-planner")
    if any(marker in request for marker in _WEATHER_MARKERS):
        selected.add("weather")
    if any(marker in request for marker in _DINING_MARKERS) or "/lodging" in paths:
        selected.add("dining-lodging")
    if any(marker in request for marker in _RESERVATION_MARKERS):
        selected.add("reservation-rules")
    return [agent for agent in _AGENT_ORDER if agent in selected]


def _protected_paths(
    plan: dict[str, Any],
    changes: list[dict[str, Any]],
    locked_paths: list[str],
) -> list[str]:
    protected = set(locked_paths)
    for day_number, day in enumerate(plan.get("days", []), start=1):
        if not isinstance(day, dict):
            continue
        reservations = {
            item.get("point_id")
            for item in day.get("reservations", [])
            if isinstance(item, dict) and item.get("point_id")
        }
        for position, segment in enumerate(day.get("segments", []), start=1):
            if not isinstance(segment, dict):
                continue
            sequence = segment.get("sequence_no")
            index = sequence if isinstance(sequence, int) and sequence > 0 else position
            path = f"/days/{day_number}/segments/{index}"
            if segment.get("is_must_visit") is True or segment.get("point_id") in reservations:
                protected.add(path)
    touched = {
        protected_path
        for protected_path in protected
        if any(_paths_overlap(change["path"], protected_path) for change in changes)
    }
    return sorted(touched)


def _paths_overlap(first: str, second: str) -> bool:
    return (
        first == second
        or first.startswith(second.rstrip("/") + "/")
        or second.startswith(first.rstrip("/") + "/")
    )


def _path_is_protected(path: str, protected_paths: list[str]) -> bool:
    return any(_paths_overlap(path, protected) for protected in protected_paths)


def _price_answer(plan: dict[str, Any], request: str) -> str | None:
    if not any(marker in request for marker in ("票价", "多少钱", "费用")):
        return None
    matches = _matching_segments(plan, request)
    for day_number, _, segment in matches:
        day = plan["days"][day_number - 1]
        for reservation in day.get("reservations", []):
            if not isinstance(reservation, dict):
                continue
            if reservation.get("point_id") != segment.get("point_id"):
                continue
            amount = reservation.get("amount_minor")
            currency = reservation.get("currency")
            if (
                isinstance(amount, int)
                and isinstance(currency, str)
                and not reservation.get("stale")
            ):
                return f"{segment.get('name')}当前版本记录的票价为 {currency} {amount / 100:.2f}。"
    return None


def _day_answer(plan: dict[str, Any], request: str) -> str | None:
    days = _extract_days(plan, request)
    if len(days) != 1:
        return None
    day_number = days[0]
    if not 1 <= day_number <= len(plan.get("days", [])):
        return None
    day = plan["days"][day_number - 1]
    if not isinstance(day, dict):
        return None
    segments = []
    for segment in day.get("segments", []):
        if not isinstance(segment, dict) or not segment.get("name"):
            continue
        period = ""
        if segment.get("start_time") and segment.get("end_time"):
            period = f"（{segment['start_time']}-{segment['end_time']}）"
        segments.append(f"{segment['name']}{period}")
    if not segments:
        return f"第{day_number}天当前没有已确认的景点安排。"
    return f"第{day_number}天安排：" + "、".join(segments) + "。"


def _segment_answer(plan: dict[str, Any], request: str) -> str | None:
    matches = _matching_segments(plan, request)
    if len(matches) != 1:
        return None
    day, _, segment = matches[0]
    period = ""
    if segment.get("start_time") and segment.get("end_time"):
        period = f"，时间为 {segment['start_time']}-{segment['end_time']}"
    return f"{segment.get('name')}安排在第{day}天{period}。"


def _plan_sources(plan: dict[str, Any]) -> list[dict[str, Any]]:
    allowed = {
        "source_type",
        "source_record_id",
        "retrieved_at",
        "valid_until",
        "freshness_status",
        "is_estimated",
    }
    sources = []
    for item in plan.get("sources", []):
        if not isinstance(item, dict):
            continue
        filtered = {key: value for key, value in item.items() if key in allowed}
        if all(filtered.get(key) for key in ("source_type", "source_record_id", "retrieved_at")):
            sources.append(filtered)
    return sources


def _change_sources(plan: dict[str, Any], context: dict[str, Any]) -> list[dict[str, Any]]:
    sources = _plan_sources(plan)
    sources.append(
        {
            "source_type": "user_input",
            "source_record_id": context["request_id"],
            "retrieved_at": context["generated_at"],
            "freshness_status": "fresh",
            "is_estimated": False,
        }
    )
    return sources


def _must_visit_ids(plan: dict[str, Any]) -> list[str]:
    values: list[str] = []
    for day in plan.get("days", []):
        if not isinstance(day, dict):
            continue
        for segment in day.get("segments", []):
            if isinstance(segment, dict) and segment.get("is_must_visit") is True:
                point_id = segment.get("point_id")
                if isinstance(point_id, str) and point_id:
                    values.append(point_id)
    return _unique(values)


def _variant_profiles(plan_count: int) -> list[dict[str, str]]:
    compact = {
        "strategy": "compact-economy",
        "pace": "compact",
        "route_strategy": "minimum-travel-time",
        "budget_tier": "economy",
    }
    balanced = {
        "strategy": "balanced-standard",
        "pace": "balanced",
        "route_strategy": "balanced-coverage",
        "budget_tier": "standard",
    }
    relaxed = {
        "strategy": "relaxed-comfort",
        "pace": "relaxed",
        "route_strategy": "minimum-transfers",
        "budget_tier": "comfort",
    }
    if plan_count == 1:
        return [balanced]
    if plan_count == 2:
        return [compact, relaxed]
    return [compact, balanced, relaxed]


def _variant_difference_summary(profile: dict[str, str]) -> str:
    return (
        f"节奏={profile['pace']}；路线={profile['route_strategy']}；预算档={profile['budget_tier']}"
    )


def _unique(values: Iterable[str]) -> list[str]:
    return list(dict.fromkeys(values))


def _digest(value: Any) -> str:
    canonical = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
