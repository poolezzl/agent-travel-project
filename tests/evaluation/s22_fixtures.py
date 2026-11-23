"""S22 evaluation fixtures: one deterministic synthetic snapshot per case.

S22 runs the frozen S19 fixed DAG (parse -> collect -> schedule -> route -> assemble)
plus the S20 local-revision path against the S05 evaluation cases.  Each case is
executed against a *synthetic* published snapshot that is derived from the case
contract itself:

* city comes from ``city_code``;
* every fact is attributed to a source id taken from ``allowed_source_record_ids``;
* the snapshot is created in memory and marked ``sample_only`` so the resulting plan
  says "synthetic sample data" instead of implying supplier facts.

Nothing here reads a supplier response, a real user record or a secret.  The snapshot
exists so that the *pipeline behaviour* (source propagation, stale display, degraded
visibility, closure conflicts, local-modification fidelity) can be measured on fixed
inputs; it is not evidence about real venues, prices or weather.
"""
from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
SERVICE_ROOT = REPO_ROOT / "agent-service"
if str(SERVICE_ROOT) not in sys.path:  # noqa: E402 - service imports need this root
    sys.path.insert(0, str(SERVICE_ROOT))

from app.agents.weather.mocks import MockWeatherCache  # noqa: E402
from app.agents.weather.models import DailyWeather  # noqa: E402
from app.agents.weather.weather_provider import WeatherProvider  # noqa: E402
from app.knowledge.catalog import (  # noqa: E402
    Chunk,
    KnowledgeEntry,
    Poi,
    PublishedCatalog,
    ReservationRule,
    SourceRef,
)
from app.knowledge.dining_lodging import JsonPoiSource  # noqa: E402
from app.orchestrator.deps import OrchestratorDeps  # noqa: E402

CASE_DIR = REPO_ROOT / "tests" / "fixtures" / "evaluation" / "cases"
INDEX_PATH = REPO_ROOT / "tests" / "fixtures" / "evaluation" / "index.json"

# Fixed evaluation clock from docs/evaluation/methodology.md.
REFERENCE_TIME = datetime(2026, 10, 1, 0, 0, tzinfo=UTC)
TIMEZONE = "Asia/Shanghai"
TRIP_DAYS = 3
PARTY_SIZE = 2
BUDGET_MINOR = 300000

CITY_IDS = {
    "CN-BJ": "beijing",
    "CN-SH": "shanghai",
    "CN-HZ": "hangzhou",
    "CN-NJ": "nanjing",
    "CN-SZ": "suzhou",
    "CN-CD": "chengdu",
    "CN-XA": "xian",
    "CN-CQ": "chongqing",
    "CN-GZ": "guangzhou",
    "CN-XM": "xiamen",
}

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

# Approximate city centres; only used to give synthetic points a plausible geometry.
CITY_CENTROIDS = {
    "beijing": (39.9163, 116.3972),
    "shanghai": (31.2304, 121.4737),
    "hangzhou": (30.2590, 120.1300),
    "nanjing": (32.0603, 118.7969),
    "suzhou": (31.2989, 120.5853),
    "chengdu": (30.6598, 104.0633),
    "xian": (34.3416, 108.9398),
    "chongqing": (29.5630, 106.5516),
    "guangzhou": (23.1291, 113.2644),
    "xiamen": (24.4798, 118.0894),
}

# Nine attractions per case so that a three-day trip can hold three per day.
_OFFSETS = (
    (0.0000, 0.0000),
    (0.0100, 0.0040),
    (-0.0080, 0.0060),
    (0.0060, -0.0080),
    (-0.0060, -0.0040),
    (0.0120, -0.0040),
    (0.0020, 0.0120),
    (-0.0100, -0.0100),
    (0.0140, 0.0100),
)

_MUST_VISIT = re.compile(r"必须保留\s*(.+)$")
_GENERIC_MUST = ("内部", "必去点位", "必去点")
_DAY_BLOCK = re.compile(r"days\[(\d+)\]")


@dataclass(frozen=True)
class CaseSpec:
    """One S05 evaluation case, validated enough for the S22 runner."""

    case_id: str
    case_version: int
    city_code: str
    evaluation_date: date
    status: str
    sources: tuple[str, ...]
    behaviors: tuple[str, ...]
    constraints: tuple[dict[str, Any], ...]
    soft_preferences: tuple[str, ...]
    local_modification: dict[str, Any] | None
    summary: str

    @property
    def city_id(self) -> str:
        return CITY_IDS[self.city_code]

    @property
    def behavior_set(self) -> set[str]:
        return set(self.behaviors)


@dataclass(frozen=True)
class CaseBundle:
    """Everything the runner needs to execute one case end to end."""

    case: CaseSpec
    request: dict[str, Any]
    deps: OrchestratorDeps
    catalog: PublishedCatalog
    trip_dates: tuple[date, ...]
    must_visit_name: str
    must_visit_poi_ids: tuple[str, ...]


def load_cases(case_dir: Path = CASE_DIR) -> list[CaseSpec]:
    """Load every case fixture in stable file order."""
    cases: list[CaseSpec] = []
    for path in sorted(case_dir.glob("*.json")):
        payload = json.loads(path.read_text(encoding="utf-8"))
        cases.append(
            CaseSpec(
                case_id=payload["case_id"],
                case_version=int(payload["case_version"]),
                city_code=payload["city_code"],
                evaluation_date=date.fromisoformat(payload["evaluation_date"]),
                status=payload["status"],
                sources=tuple(payload["allowed_source_record_ids"]),
                behaviors=tuple(payload.get("expected_behaviors") or ()),
                constraints=tuple(payload.get("hard_constraints") or ()),
                soft_preferences=tuple(payload.get("soft_preferences") or ()),
                local_modification=payload.get("local_modification"),
                summary=str((payload.get("user_request") or {}).get("summary", "")),
            )
        )
    return cases


def load_index(index_path: Path = INDEX_PATH) -> dict[str, Any]:
    return json.loads(index_path.read_text(encoding="utf-8"))


def must_visit_name(case: CaseSpec) -> str:
    """Derive the must-visit label the case contract declares.

    Cases whose constraint only says "内部必去点位" get a stable internal label instead of
    a real venue name, so no real-world fact is implied by the fixture.
    """
    for constraint in case.constraints:
        match = _MUST_VISIT.search(str(constraint.get("description", "")))
        if not match:
            continue
        name = match.group(1).strip().strip("。；;，,")
        if name and not any(marker in name for marker in _GENERIC_MUST):
            return name
    return f"{CITY_NAMES[case.city_id]}内部必去点"


def _source_ref(source_id: str, *, stale: bool, now: datetime) -> SourceRef:
    retrieved = now - timedelta(days=40 if stale else 1)
    return SourceRef(
        source_record_id=source_id,
        source_type="knowledge_base",
        retrieved_at=retrieved.isoformat().replace("+00:00", "Z"),
        valid_until=(now + timedelta(days=180)).isoformat().replace("+00:00", "Z"),
        freshness_status="stale" if stale else "fresh",
    )


def _closed_weekdays(case: CaseSpec, trip_dates: tuple[date, ...]) -> tuple[int, ...]:
    if "closed_conflict" not in case.behavior_set:
        return ()
    return tuple(sorted({day.isoweekday() for day in trip_dates}))


def _poi_names(case: CaseSpec, must_name: str) -> list[str]:
    city_name = CITY_NAMES[case.city_id]
    names = [must_name] + [f"{city_name}样例景点{index}" for index in range(2, 10)]
    if "same_name_poi" in case.behavior_set:
        # Two published points share one name: the pipeline must not merge them.
        names[1] = must_name
    return names


def _entry(
    *,
    case: CaseSpec,
    poi: Poi,
    source_id: str,
    stale: bool,
    now: datetime,
    index: int,
) -> KnowledgeEntry:
    verified = (now - timedelta(days=40 if stale else 1)).isoformat().replace("+00:00", "Z")
    return KnowledgeEntry(
        knowledge_id=f"KB-{case.case_id}-{index}",
        poi_id=poi.poi_id,
        city_id=poi.city_id,
        title=f"{poi.name}玩法与建议",
        status="PUBLISHED",
        effective_from="2020-01-01T00:00:00+00:00",
        effective_until="2030-12-31T00:00:00+00:00",
        last_verified_at=verified,
        source=_source_ref(source_id, stale=stale, now=now),
        chunks=(
            Chunk(
                chunk_id=f"CH-{case.case_id}-{index}-1",
                fact_type="suggested_duration",
                text="建议游玩 1 小时",
            ),
            Chunk(
                chunk_id=f"CH-{case.case_id}-{index}-2",
                fact_type="highlights",
                text=f"{poi.name}的公开介绍要点，仅作行程参考。",
            ),
            Chunk(
                chunk_id=f"CH-{case.case_id}-{index}-3",
                fact_type="avoid_tips",
                text="建议错峰入场，具体开放时间以官方公告为准。",
            ),
        ),
    )


def _rule(
    *,
    case: CaseSpec,
    poi: Poi,
    source_id: str,
    stale: bool,
    now: datetime,
    index: int,
) -> ReservationRule:
    verified = (now - timedelta(days=40 if stale else 1)).isoformat().replace("+00:00", "Z")
    return ReservationRule(
        policy_id=f"POL-{case.case_id}-{index}",
        poi_id=poi.poi_id,
        city_id=poi.city_id,
        status="PUBLISHED",
        ticket_type="adult",
        audience="成人",
        reservation_required=True,
        channel="官方小程序",
        release_time="08:00",
        release_timezone=TIMEZONE,
        advance_days=3,
        currency="CNY",
        amount_minor=6000,
        free_condition=None,
        effective_from="2020-01-01",
        effective_until="2030-12-31",
        holiday_exception=None,
        last_verified_at=verified,
        stale_after_days=30,
        version=1,
        source=_source_ref(source_id, stale=stale, now=now),
    )


def _dining_pois(city_id: str, names: list[str], coordinates: list[tuple[float, float]]) -> list[dict[str, Any]]:
    """Synthetic legal POIs for the S18 dining/lodging agent (no prices invented)."""
    pois: list[dict[str, Any]] = []
    for index, (latitude, longitude) in enumerate(coordinates):
        for slot, (kind, category) in enumerate(
            (("A", "restaurant"), ("B", "restaurant"), ("C", "lodging"))
        ):
            poi_id = f"EVAL-{city_id.upper()}-{index + 1}-{slot}"
            price = (
                {"kind": "exact", "currency": "CNY", "amount_minor": 6800}
                if category == "restaurant"
                else {"kind": "range", "currency": "CNY", "min_minor": 45000, "max_minor": 60000}
            )
            pois.append(
                {
                    "poi_id": poi_id,
                    "name": f"{names[index]}周边{kind}样例",
                    "city_id": city_id,
                    "category": category,
                    "latitude": latitude + 0.0012,
                    "longitude": longitude + 0.0010,
                    "price": price,
                    "retrieved_at": "2026-09-30T09:00:00Z",
                    "valid_until": "2027-09-30T00:00:00Z",
                    "freshness_status": "fresh",
                    "source_record_id": f"SRC-{poi_id}",
                    "detour_min": 6,
                }
            )
    return pois


class CaseWeatherPort:
    """Deterministic weather port: forecast inside the horizon, climate outside."""

    def __init__(self, *, source_id: str, now: datetime) -> None:
        self._source_id = source_id
        self._now = now

    def forecast(self, city_id: str, day: date) -> DailyWeather:
        return DailyWeather(
            city_id=city_id,
            date=day,
            temp_min=6.0,
            temp_max=17.0,
            precip_prob=0.2,
            condition="晴间多云",
            data_type="forecast",
            source_record_id=self._source_id,
            retrieved_at=self._now - timedelta(hours=2),
            valid_until=datetime.combine(day + timedelta(days=2), datetime.min.time(), tzinfo=UTC),
            freshness_status="fresh",
            forecast_published_at=self._now - timedelta(hours=3),
        )

    def climate(self, city_id: str, day: date) -> DailyWeather:
        return DailyWeather(
            city_id=city_id,
            date=day,
            temp_min=5.0,
            temp_max=16.0,
            precip_prob=None,
            condition="历史同期多云",
            data_type="climate_statistics",
            source_record_id=self._source_id,
            retrieved_at=self._now - timedelta(days=30),
            valid_until=None,
            freshness_status="fresh",
        )


def build_bundle(case: CaseSpec, *, now: datetime = REFERENCE_TIME) -> CaseBundle:
    """Build the synthetic snapshot, request payload and deps for one case."""
    if case.status != "ready":
        raise ValueError(f"{case.case_id}: case is not ready ({case.status})")
    if not case.sources:
        raise ValueError(f"{case.case_id}: case declares no allowed source")
    city_id = case.city_id
    behaviors = case.behavior_set
    trip_dates = tuple(case.evaluation_date + timedelta(days=offset) for offset in range(TRIP_DAYS))
    must_name = must_visit_name(case)
    stale = bool({"stale_visible", "stale_displayed"} & behaviors)
    skip_knowledge = "unknown_fact" in behaviors
    partial_knowledge = "degraded_explained" in behaviors

    base_latitude, base_longitude = CITY_CENTROIDS[city_id]
    names = _poi_names(case, must_name)
    closed = _closed_weekdays(case, trip_dates)

    pois: list[Poi] = []
    entries: list[KnowledgeEntry] = []
    rules: list[ReservationRule] = []
    coordinates: list[tuple[float, float]] = []
    for index, (offset_lat, offset_lon) in enumerate(_OFFSETS):
        latitude = round(base_latitude + offset_lat, 6)
        longitude = round(base_longitude + offset_lon, 6)
        coordinates.append((latitude, longitude))
        source_id = case.sources[0] if len(case.sources) == 1 else (
            case.sources[0] if index < 5 else case.sources[1]
        )
        poi = Poi(
            poi_id=f"POI-{case.case_id}-{index + 1}",
            city_id=city_id,
            name=names[index],
            aliases=(),
            district=None,
            category="attraction",
            latitude=latitude,
            longitude=longitude,
            open_time="09:00",
            close_time="17:00",
            closed_weekdays=closed if index == 0 else (),
        )
        pois.append(poi)
        # BJ-07 asks for a genuinely unknown point: no published knowledge at all.
        if skip_knowledge:
            continue
        # Seven "degraded, explained" cases carry one point without published knowledge.
        if partial_knowledge and index == 5:
            continue
        entries.append(
            _entry(case=case, poi=poi, source_id=source_id, stale=stale, now=now, index=index + 1)
        )
        rules.append(
            _rule(case=case, poi=poi, source_id=source_id, stale=stale, now=now, index=index + 1)
        )

    catalog = PublishedCatalog(
        catalog_version=f"eval-{case.case_id}-v{case.case_version}",
        pois=tuple(pois),
        entries=tuple(entries),
        rules=tuple(rules),
        sample_only=True,
    )
    poi_source = JsonPoiSource(_dining_pois(city_id, names, coordinates))
    horizon = 2 if "climate_statistics" in behaviors else 10
    weather = WeatherProvider(
        CaseWeatherPort(source_id=case.sources[0], now=now),
        MockWeatherCache(),
        TIMEZONE,
        now=lambda: now,
    )
    deps = OrchestratorDeps(
        catalog=catalog,
        poi_source=poi_source,
        weather_provider=weather,
        now=lambda: now,
        forecast_horizon_days=horizon,
        max_points_per_day=3,
    )
    request = {
        "city": CITY_NAMES[city_id],
        "start_date": trip_dates[0].isoformat(),
        "end_date": trip_dates[-1].isoformat(),
        "party_size": PARTY_SIZE,
        "budget": {"currency": "CNY", "amount_minor": BUDGET_MINOR},
        "must_visit": [must_name],
        "preferences": list(case.soft_preferences),
        "transport": "混合",
        "plan_count": 1,
    }
    must_ids = tuple(poi.poi_id for poi in pois if poi.name == must_name)
    return CaseBundle(
        case=case,
        request=request,
        deps=deps,
        catalog=catalog,
        trip_dates=trip_dates,
        must_visit_name=must_name,
        must_visit_poi_ids=must_ids,
    )


def day_index_from_block(block: str) -> int:
    """Parse ``days[1]`` / ``days[0].segments[2]`` into a zero-based day index."""
    match = _DAY_BLOCK.match(block)
    if match is None:
        raise ValueError(f"unsupported block path: {block}")
    return int(match.group(1))


def segment_position_from_block(block: str) -> int | None:
    marker = "segments["
    if marker not in block:
        return None
    tail = block.split(marker, 1)[1].split("]", 1)[0]
    return int(tail) + 1


__all__ = [
    "CASE_DIR",
    "CITY_IDS",
    "CITY_NAMES",
    "INDEX_PATH",
    "REFERENCE_TIME",
    "CaseBundle",
    "CaseSpec",
    "build_bundle",
    "day_index_from_block",
    "load_cases",
    "load_index",
    "must_visit_name",
    "segment_position_from_block",
]