"""Read-only published catalog for the R3 attractions and reservation agents.

The Java/MySQL side is the system of record (ADR-0001, ADR-0008).  This module
models the immutable *published snapshot* that the Agent service is allowed to
read.  It deliberately has no database driver and no network access so the
agent logic can be unit-tested with the standard library only.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

DEFAULT_STALE_AFTER_DAYS = 30
_CJK = re.compile(r"[\u3400-\u9fff]")
_WORD = re.compile(r"[a-z0-9]+")


class CatalogError(ValueError):
    """Raised when the published catalog is missing or malformed."""


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def parse_datetime(value: str | None) -> datetime | None:
    if not value:
        return None
    normalized = value.replace("Z", "+00:00")
    parsed = datetime.fromisoformat(normalized)
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def parse_date(value: str | None) -> date | None:
    if not value:
        return None
    # Accept both YYYY-MM-DD and full RFC3339 date-times from the Java API.
    return date.fromisoformat(value[:10])


def isoformat(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def tokens(text: str) -> set[str]:
    """Small deterministic tokenizer: latin words plus CJK bigrams."""
    lowered = (text or "").lower()
    result = set(_WORD.findall(lowered))
    cjk = "".join(_CJK.findall(lowered))
    result.update(cjk[index : index + 2] for index in range(max(0, len(cjk) - 1)))
    result.update(cjk)
    return {token for token in result if token}


@dataclass(frozen=True)
class SourceRef:
    source_record_id: str
    source_type: str
    retrieved_at: str
    valid_until: str | None = None
    freshness_status: str = "fresh"

    def as_metadata(self) -> dict[str, Any]:
        return {
            "source_type": self.source_type,
            "source_record_id": self.source_record_id,
            "retrieved_at": self.retrieved_at,
            "valid_until": self.valid_until,
            "freshness_status": self.freshness_status,
            "is_estimated": False,
        }


@dataclass(frozen=True)
class Chunk:
    chunk_id: str
    fact_type: str
    text: str


@dataclass(frozen=True)
class KnowledgeEntry:
    knowledge_id: str
    poi_id: str
    city_id: str
    title: str
    status: str
    effective_from: str | None
    effective_until: str | None
    last_verified_at: str | None
    source: SourceRef
    chunks: tuple[Chunk, ...]

    def is_readable(self, moment: datetime) -> bool:
        if self.status != "PUBLISHED":
            return False
        start = parse_datetime(self.effective_from)
        end = parse_datetime(self.effective_until)
        if start and moment < start:
            return False
        if end and moment > end:
            return False
        return True

    def freshness(self, moment: datetime, stale_after_days: int = DEFAULT_STALE_AFTER_DAYS) -> str:
        verified = parse_datetime(self.last_verified_at)
        if verified is None:
            return "unknown"
        if moment - verified > timedelta(days=stale_after_days):
            return "stale"
        return "fresh"


@dataclass(frozen=True)
class ReservationRule:
    policy_id: str
    poi_id: str
    city_id: str
    status: str
    ticket_type: str
    audience: str | None
    reservation_required: bool
    channel: str | None
    release_time: str | None
    release_timezone: str
    advance_days: int | None
    currency: str | None
    amount_minor: int | None
    free_condition: str | None
    effective_from: str
    effective_until: str | None
    holiday_exception: str | None
    last_verified_at: str
    stale_after_days: int
    version: int
    source: SourceRef

    def is_stale(self, moment: datetime) -> bool:
        verified = parse_datetime(self.last_verified_at)
        if verified is None:
            return True
        return moment - verified > timedelta(days=self.stale_after_days)

    def covers(self, day: date) -> bool:
        start = parse_date(self.effective_from)
        end = parse_date(self.effective_until)
        if start and day < start:
            return False
        if end and day > end:
            return False
        return True


@dataclass(frozen=True)
class Poi:
    poi_id: str
    city_id: str
    name: str
    aliases: tuple[str, ...]
    district: str | None
    category: str


class PublishedCatalog:
    """Immutable, versioned bundle of published facts and reservation rules."""

    def __init__(
        self,
        *,
        catalog_version: str,
        pois: tuple[Poi, ...],
        entries: tuple[KnowledgeEntry, ...],
        rules: tuple[ReservationRule, ...],
    ) -> None:
        self.catalog_version = catalog_version
        self.pois = pois
        self.entries = entries
        self.rules = rules

    @classmethod
    def load(cls, path: str | Path) -> "PublishedCatalog":
        catalog_path = Path(path)
        if not catalog_path.is_file():
            raise CatalogError(f"catalog not found: {catalog_path}")
        try:
            payload = json.loads(catalog_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise CatalogError(f"cannot read catalog: {exc}") from exc
        return cls.from_payload(payload)

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "PublishedCatalog":
        pois = tuple(
            Poi(
                poi_id=item["poi_id"],
                city_id=item["city_id"],
                name=item["name"],
                aliases=tuple(item.get("aliases") or ()),
                district=item.get("district"),
                category=item.get("category", "attraction"),
            )
            for item in payload.get("pois", [])
        )
        entries = tuple(_entry_from_payload(item) for item in payload.get("entries", []))
        rules = tuple(_rule_from_payload(item) for item in payload.get("rules", []))
        return cls(
            catalog_version=payload.get("catalog_version", "unknown"),
            pois=pois,
            entries=entries,
            rules=rules,
        )

    def find_pois(self, point_id: str, name: str, city_id: str | None) -> tuple[Poi, ...]:
        """Match by internal id first; name/alias only as a controlled fallback.

        A name that maps to more than one POI is a conflict and must not be
        silently merged; callers receive every candidate and decide to degrade.
        """
        by_id = [poi for poi in self.pois if poi.poi_id == point_id]
        if by_id:
            # An explicit internal id must still respect the requested city:
            # otherwise a caller can read another city's facts by passing its id.
            if city_id:
                by_id = [poi for poi in by_id if poi.city_id == city_id]
            return tuple(by_id)
        normalized = (name or "").strip().lower()
        if not normalized:
            return ()
        matches = [
            poi
            for poi in self.pois
            if poi.name.strip().lower() == normalized
            or normalized in {alias.strip().lower() for alias in poi.aliases}
        ]
        if city_id:
            matches = [poi for poi in matches if poi.city_id == city_id]
        return tuple(matches)

    def knowledge_for(
        self,
        *,
        point_id: str,
        name: str,
        city_id: str | None,
        query: str,
        moment: datetime,
        top_k: int = 4,
    ) -> tuple[tuple[Poi, ...], tuple[KnowledgeEntry, ...]]:
        candidates = self.find_pois(point_id, name, city_id)
        if len(candidates) != 1:
            return candidates, ()
        poi = candidates[0]
        readable = [
            entry
            for entry in self.entries
            if entry.poi_id == poi.poi_id
            and entry.city_id == poi.city_id
            and entry.is_readable(moment)
        ]
        if query.strip():
            query_tokens = tokens(query)
            scored: list[tuple[float, KnowledgeEntry]] = []
            for entry in readable:
                text = " ".join([entry.title, *(chunk.text for chunk in entry.chunks)])
                overlap = len(query_tokens & tokens(text))
                score = overlap / max(1, len(query_tokens))
                if score > 0:
                    scored.append((score, entry))
            readable = [entry for _, entry in sorted(scored, key=lambda pair: pair[0], reverse=True)]
        return candidates, tuple(readable[:top_k])

    def rules_for(
        self,
        *,
        point_id: str,
        name: str,
        city_id: str | None,
        day: date,
        moment: datetime,
    ) -> tuple[tuple[Poi, ...], tuple[ReservationRule, ...]]:
        candidates = self.find_pois(point_id, name, city_id)
        if len(candidates) != 1:
            return candidates, ()
        poi = candidates[0]
        matches = [
            rule
            for rule in self.rules
            if rule.poi_id == poi.poi_id
            and rule.city_id == poi.city_id
            and rule.status == "PUBLISHED"
            and rule.covers(day)
        ]
        matches.sort(key=lambda rule: rule.version, reverse=True)
        return candidates, tuple(matches[:1])


def _source_from_payload(payload: dict[str, Any] | None) -> SourceRef:
    data = payload or {}
    return SourceRef(
        source_record_id=data.get("source_record_id", "unknown"),
        source_type=data.get("source_type", "knowledge_base"),
        retrieved_at=data.get("retrieved_at", ""),
        valid_until=data.get("valid_until"),
        freshness_status=data.get("freshness_status", "unknown"),
    )


def _entry_from_payload(payload: dict[str, Any]) -> KnowledgeEntry:
    return KnowledgeEntry(
        knowledge_id=payload["knowledge_id"],
        poi_id=payload["poi_id"],
        city_id=payload["city_id"],
        title=payload.get("title", ""),
        status=payload.get("status", "DRAFT"),
        effective_from=payload.get("effective_from"),
        effective_until=payload.get("effective_until"),
        last_verified_at=payload.get("last_verified_at"),
        source=_source_from_payload(payload.get("source")),
        chunks=tuple(
            Chunk(
                chunk_id=chunk["chunk_id"],
                fact_type=chunk.get("fact_type", "general"),
                text=chunk.get("text", ""),
            )
            for chunk in payload.get("chunks", [])
        ),
    )


def _rule_from_payload(payload: dict[str, Any]) -> ReservationRule:
    return ReservationRule(
        policy_id=payload["policy_id"],
        poi_id=payload["poi_id"],
        city_id=payload["city_id"],
        status=payload.get("status", "DRAFT"),
        ticket_type=payload.get("ticket_type", "general"),
        audience=payload.get("audience"),
        reservation_required=bool(payload.get("reservation_required", True)),
        channel=payload.get("channel"),
        release_time=payload.get("release_time"),
        release_timezone=payload.get("release_timezone", "Asia/Shanghai"),
        advance_days=payload.get("advance_days"),
        currency=payload.get("currency"),
        amount_minor=payload.get("amount_minor"),
        free_condition=payload.get("free_condition"),
        effective_from=payload.get("effective_from", "1970-01-01"),
        effective_until=payload.get("effective_until"),
        holiday_exception=payload.get("holiday_exception"),
        last_verified_at=payload.get("last_verified_at", "1970-01-01T00:00:00+00:00"),
        stale_after_days=int(payload.get("stale_after_days", DEFAULT_STALE_AFTER_DAYS)),
        version=int(payload.get("version", 1)),
        source=_source_from_payload(payload.get("source")),
    )
