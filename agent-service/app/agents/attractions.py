"""S16 attractions information agent: cite published knowledge, never invent facts."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from app.agents.common import (
    ERROR_INVALID_INPUT,
    InputError,
    dedupe_sources,
    envelope,
    failure,
    isoformat,
    now_utc,
    validate_points,
)
from app.knowledge.catalog import (
    Chunk,
    KnowledgeEntry,
    PublishedCatalog,
    parse_datetime,
    tokens,
)
from app.knowledge.rag import LexicalRag


def run(
    inputs: dict[str, Any],
    catalog: PublishedCatalog,
    context: dict[str, Any],
    *,
    moment: datetime | None = None,
) -> dict[str, Any]:
    """Build one card per input point, with citations for every fact."""
    try:
        points = validate_points(inputs.get("points"))
    except InputError as exc:
        return failure(context, ERROR_INVALID_INPUT, str(exc))

    city_id = inputs.get("city_id")
    if city_id is not None and (not isinstance(city_id, str) or not city_id.strip()):
        return failure(context, ERROR_INVALID_INPUT, "city_id 必须是非空字符串")
    city = city_id.strip() if isinstance(city_id, str) else None
    tags = inputs.get("preference_tags") or []
    if not isinstance(tags, list) or any(not isinstance(tag, str) for tag in tags):
        return failure(context, ERROR_INVALID_INPUT, "preference_tags 必须是字符串数组")
    raw_query = inputs.get("query")
    if raw_query is not None and (not isinstance(raw_query, str) or not raw_query.strip()):
        return failure(context, ERROR_INVALID_INPUT, "query 必须是非空字符串")
    query = raw_query.strip() if isinstance(raw_query, str) else ""

    current = moment or now_utc()
    retriever = LexicalRag(catalog) if query else None
    cards: list[dict[str, Any]] = []
    sources: list[dict[str, Any]] = []
    degraded: list[str] = []
    missing = 0
    stale_seen = False

    for index, point in enumerate(points):
        candidates, entries = catalog.knowledge_for(
            point_id=point["point_id"],
            name=point["name"],
            city_id=city,
            query="",
            moment=current,
        )

        if len(candidates) > 1:
            cards.append({"point_id": point["point_id"], "source": "unknown"})
            missing += 1
            degraded.append(f"cards[{index}].point_id")
            continue
        if not candidates or not entries:
            cards.append({"point_id": point["point_id"], "source": "unknown"})
            missing += 1
            degraded.append(f"cards[{index}].source")
            continue

        chunks = [chunk for item in entries for chunk in item.chunks]
        if not chunks:
            cards.append({"point_id": point["point_id"], "source": "unknown"})
            missing += 1
            degraded.append(f"cards[{index}].chunks")
            continue

        if retriever is not None:
            hits = retriever.search(
                point_id=point["point_id"],
                name=point["name"],
                city_id=city,
                query=query,
                moment=current,
                top_k=max(4, len(chunks)),
            )
            hit_ids = {hit.chunk_id for hit in hits}
            chunks = [chunk for chunk in chunks if chunk.chunk_id in hit_ids]
            if not chunks:
                # Low-confidence query: never fall back to unrelated text.
                cards.append({"point_id": point["point_id"], "source": "unknown"})
                missing += 1
                degraded.append(f"cards[{index}].source")
                continue

        stale = any(item.freshness(current) == "stale" for item in entries)
        stale_seen = stale_seen or stale
        if stale:
            degraded.append(f"cards[{index}].freshness_status")

        ordered = _rank_chunks(chunks, tags)
        card = _build_card(point["point_id"], entries, ordered, stale, current)
        cards.append(card)
        sources.extend(item.source.as_metadata() for item in entries)

    if missing == len(points):
        status = "degraded"
        message = "本地知识库暂无已发布的景点资讯，未生成事实内容"
    elif missing or stale_seen:
        status = "partial"
        message = "部分景点暂无已发布资讯或内容已过期，已按空卡/降级标记"
    else:
        status = "ok"
        message = "景点资讯均来自已发布知识库"

    payload: dict[str, Any] = {
        "status": status,
        "message": message,
        "source_metadata": dedupe_sources(sources),
        "cards": cards,
    }
    if degraded:
        payload["degraded_fields"] = sorted(set(degraded))
    return envelope(context, **payload)


def _rank_chunks(chunks: list[Chunk], tags: list[str]) -> list[Chunk]:
    tag_tokens = tokens(" ".join(tags))
    if not tag_tokens:
        return chunks
    return sorted(
        chunks,
        key=lambda chunk: len(tag_tokens & tokens(chunk.text)),
        reverse=True,
    )


def _build_card(
    point_id: str,
    entries: tuple[KnowledgeEntry, ...],
    chunks: list[Chunk],
    stale: bool,
    moment: datetime,
) -> dict[str, Any]:
    highlights = [chunk.text for chunk in chunks if chunk.fact_type == "highlights"]
    avoid_tips = [chunk.text for chunk in chunks if chunk.fact_type == "avoid_tips"]
    card: dict[str, Any] = {
        "point_id": point_id,
        "source": "knowledge_base",
        "knowledge_id": entries[0].knowledge_id,
        "chunk_ids": [chunk.chunk_id for chunk in chunks],
        "retrieved_at": _latest_retrieved_at(entries),
        "valid_until": _earliest_valid_until(entries),
        "freshness_status": "stale" if stale else entries[0].freshness(moment),
        "facts": [
            {
                "fact_type": chunk.fact_type,
                "value": chunk.text,
                "chunk_id": chunk.chunk_id,
                "source_record_id": _source_for_chunk(entries, chunk.chunk_id),
            }
            for chunk in chunks
        ],
    }
    if highlights:
        card["highlights"] = highlights
    if avoid_tips:
        card["avoid_tips"] = avoid_tips
    for field in ("suggested_duration", "best_time", "popularity"):
        values = [chunk.text for chunk in chunks if chunk.fact_type == field]
        if values:
            card[field] = values[0]
    return card


def _source_for_chunk(entries: tuple[KnowledgeEntry, ...], chunk_id: str) -> str:
    for entry in entries:
        if any(chunk.chunk_id == chunk_id for chunk in entry.chunks):
            return entry.source.source_record_id
    return "unknown"


def _latest_retrieved_at(entries: tuple[KnowledgeEntry, ...]) -> str:
    values = [parse_datetime(entry.source.retrieved_at) for entry in entries]
    present = [value for value in values if value is not None]
    return isoformat(max(present)) if present else ""


def _earliest_valid_until(entries: tuple[KnowledgeEntry, ...]) -> str | None:
    values = [parse_datetime(entry.effective_until) for entry in entries]
    present = [value for value in values if value is not None]
    return isoformat(min(present)) if present else None


__all__ = ["run"]
