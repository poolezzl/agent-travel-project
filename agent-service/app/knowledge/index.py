"""Turn the immutable published catalog into deterministic Chroma records.

The Java/MySQL side is the source of truth (ADR-0001/0008).  This module only
projects the already-published snapshot into the Agent-owned vector index; it
never decides whether a fact is published.  Expired entries are skipped at
index time, and the query side also filters on ``effective_until_epoch`` so a
stale index cannot leak expired text.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from app.knowledge.catalog import PublishedCatalog, parse_datetime

# 2100-01-01T00:00:00Z, used as the "no expiry" sentinel for numeric metadata.
NO_EXPIRY_EPOCH = 4_102_444_800.0


def _epoch(value: str | None, default: float) -> float:
    parsed = parse_datetime(value)
    return parsed.timestamp() if parsed is not None else default


def index_records(catalog: PublishedCatalog, *, moment: datetime | None = None) -> list[dict[str, Any]]:
    """Build Chroma ``(id, document, metadata)`` rows from published entries."""
    now = moment or datetime.now(timezone.utc)
    now_epoch = now.timestamp()
    records: list[dict[str, Any]] = []
    for entry in catalog.entries:
        if entry.status != "PUBLISHED":
            continue
        effective_from_epoch = _epoch(entry.effective_from, 0.0)
        effective_until_epoch = _epoch(entry.effective_until, NO_EXPIRY_EPOCH)
        if effective_until_epoch <= now_epoch:
            continue
        for chunk in entry.chunks:
            records.append(
                {
                    "id": chunk.chunk_id,
                    "document": chunk.text,
                    "metadata": {
                        "knowledge_id": entry.knowledge_id,
                        "chunk_id": chunk.chunk_id,
                        "point_id": entry.poi_id,
                        "city_id": entry.city_id,
                        "fact_type": chunk.fact_type,
                        "source_record_id": entry.source.source_record_id,
                        "source_type": entry.source.source_type,
                        "retrieved_at": entry.source.retrieved_at,
                        "status": entry.status,
                        "effective_from_epoch": effective_from_epoch,
                        "effective_until_epoch": effective_until_epoch,
                        "last_verified_at": entry.last_verified_at or "",
                        "catalog_version": catalog.catalog_version,
                    },
                }
            )
    return records


__all__ = ["NO_EXPIRY_EPOCH", "index_records"]
