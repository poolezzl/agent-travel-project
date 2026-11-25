"""S15 retrieval facade used by the attractions agent.

The production target is Chroma + bge-small-zh-v1.5 (ADR-0008).  This module
defines the retrieval boundary and a deterministic lexical implementation used
when Chroma/embedding dependencies are not installed (offline development and
unit tests).  Both implementations must only return published, city-matched,
unexpired chunks and must always carry source/chunk citations.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any, Protocol

from app.knowledge.catalog import KnowledgeEntry, PublishedCatalog, tokens

DEFAULT_MIN_SCORE = 0.15


@dataclass(frozen=True)
class RagHit:
    point_id: str
    knowledge_id: str
    chunk_id: str
    text: str
    fact_type: str
    source_record_id: str
    score: float


class RagRetriever(Protocol):
    def search(
        self,
        *,
        point_id: str,
        name: str,
        city_id: str | None,
        query: str,
        moment: datetime,
        top_k: int = 4,
    ) -> list[RagHit]: ...


class LexicalRag:
    """Deterministic fallback used offline; never invents missing facts."""

    def __init__(self, catalog: PublishedCatalog, *, min_score: float = DEFAULT_MIN_SCORE) -> None:
        self.catalog = catalog
        self.min_score = min_score

    def search(
        self,
        *,
        point_id: str,
        name: str,
        city_id: str | None,
        query: str,
        moment: datetime,
        top_k: int = 4,
    ) -> list[RagHit]:
        _, entries = self.catalog.knowledge_for(
            point_id=point_id,
            name=name,
            city_id=city_id,
            query=query,
            moment=moment,
            top_k=top_k,
        )
        if not entries:
            return []
        query_tokens = tokens(query)
        hits: list[RagHit] = []
        for entry in entries:
            for chunk in entry.chunks:
                score = _score(query_tokens, chunk.text) if query_tokens else 1.0
                if score < self.min_score:
                    continue
                hits.append(_hit(entry, chunk, score))
        hits.sort(key=lambda hit: hit.score, reverse=True)
        return hits[:top_k]


class ChromaRag:
    """Thin adapter around a Chroma collection owned by the Agent service.

    The import is intentionally lazy: deployments without Chroma keep working
    with LexicalRag, while production can switch once the embedding model and
    persistent path are configured.  Ingestion is driven by published Java
    events; Chroma is never the status/source of truth.
    """

    def __init__(self, collection: object, *, min_score: float = DEFAULT_MIN_SCORE) -> None:
        self.collection = collection
        self.min_score = min_score

    def search(
        self,
        *,
        point_id: str,
        name: str,
        city_id: str | None,
        query: str,
        moment: datetime,
        top_k: int = 4,
    ) -> list[RagHit]:
        conditions: list[dict[str, object]] = [
            {"status": "PUBLISHED"},
            {"point_id": point_id},
            {"effective_until_epoch": {"$gt": moment.timestamp()}},
        ]
        if city_id:
            conditions.append({"city_id": city_id})
        where: dict[str, object] = conditions[0] if len(conditions) == 1 else {"$and": conditions}
        result = self.collection.query(  # type: ignore[attr-defined]
            query_texts=[query],
            n_results=top_k,
            where=where,
        )
        hits = _hits_from_chroma(result, point_id)
        return [hit for hit in hits if hit.score >= self.min_score]


def _hit(entry: KnowledgeEntry, chunk: object, score: float) -> RagHit:
    return RagHit(
        point_id=entry.poi_id,
        knowledge_id=entry.knowledge_id,
        chunk_id=chunk.chunk_id,  # type: ignore[attr-defined]
        text=chunk.text,  # type: ignore[attr-defined]
        fact_type=chunk.fact_type,  # type: ignore[attr-defined]
        source_record_id=entry.source.source_record_id,
        score=round(score, 4),
    )


def _score(query_tokens: set[str], text: str) -> float:
    if not query_tokens:
        return 1.0
    return len(query_tokens & tokens(text)) / len(query_tokens)


def _first_row(value: object) -> list[Any]:
    """Return the first Chroma result row, or an empty row for any unexpected shape."""
    if isinstance(value, list) and value and isinstance(value[0], list):
        return value[0]
    return []


def _hits_from_chroma(result: dict[str, object], point_id: str) -> list[RagHit]:
    """Convert a raw Chroma response without trusting unsupported payloads."""
    documents = _first_row(result.get("documents"))
    metadatas = _first_row(result.get("metadatas"))
    distances = _first_row(result.get("distances"))
    ids = _first_row(result.get("ids"))
    hits: list[RagHit] = []
    for index, text in enumerate(documents):
        metadata = metadatas[index] if index < len(metadatas) else {}
        if not isinstance(metadata, dict):
            metadata = {}
        chunk_id = ids[index] if index < len(ids) else ""
        distance = distances[index] if index < len(distances) else 1.0
        hits.append(
            RagHit(
                point_id=point_id,
                knowledge_id=str(metadata.get("knowledge_id", "")),
                chunk_id=str(chunk_id),
                text=str(text),
                fact_type=str(metadata.get("fact_type", "general")),
                source_record_id=str(metadata.get("source_record_id", "")),
                score=round(max(0.0, 1.0 - float(distance)), 4),
            )
        )
    return [hit for hit in hits if hit.knowledge_id and hit.chunk_id and hit.source_record_id]
