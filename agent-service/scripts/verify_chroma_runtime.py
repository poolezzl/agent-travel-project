"""Verify the R3 Chroma adapter against a real persistent Chroma collection.

This check intentionally uses a tiny deterministic embedding function so it can
run without downloading bge weights. The production indexer still uses
bge-small-zh-v1.5; this script proves collection persistence, metadata filters,
query wiring and expiry exclusion.

Usage:
    pip install chromadb
    python scripts/verify_chroma_runtime.py
"""

from __future__ import annotations

import argparse
import hashlib
import math
import tempfile
from datetime import UTC, datetime
from pathlib import Path

from app.knowledge.catalog import PublishedCatalog
from app.knowledge.index import index_records
from app.knowledge.rag import ChromaRag


class HashEmbedding:
    """Deterministic 8-dim embedding used only for adapter verification."""

    @staticmethod
    def name() -> str:
        return "hash-verify"

    def get_config(self) -> dict:
        return {}

    @staticmethod
    def build_from_config(config: dict) -> HashEmbedding:
        return HashEmbedding()

    def embed_query(self, input):  # noqa: A002 - Chroma protocol name
        return self(input)

    def embed_documents(self, input):  # noqa: A002 - Chroma protocol name
        return self(input)

    def __call__(self, input):  # noqa: A002 - Chroma protocol name
        vectors = []
        for text in input:
            digest = hashlib.sha256(text.encode("utf-8")).digest()
            vector = [((byte / 255.0) * 2.0 - 1.0) for byte in digest[:8]]
            norm = math.sqrt(sum(value * value for value in vector)) or 1.0
            vectors.append([value / norm for value in vector])
        return vectors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--catalog",
        default=str(Path(__file__).resolve().parents[1] / "resources" / "published_catalog.json"),
    )
    parser.add_argument("--persist-dir", default=None)
    args = parser.parse_args(argv)

    try:
        import chromadb
    except ImportError:
        print("chromadb 未安装，无法执行真实 Chroma 验证", flush=True)
        return 2

    catalog = PublishedCatalog.load(args.catalog)
    moment = datetime(2026, 10, 29, tzinfo=UTC)
    records = index_records(catalog, moment=moment)
    with tempfile.TemporaryDirectory(prefix="r3-chroma-", ignore_cleanup_errors=True) as temp_dir:
        persist_dir = args.persist_dir or temp_dir
        client = chromadb.PersistentClient(path=persist_dir)
        collection = client.get_or_create_collection(
            name="r3_knowledge_verify",
            embedding_function=HashEmbedding(),
        )
        collection.upsert(
            ids=[record["id"] for record in records],
            documents=[record["document"] for record in records],
            metadatas=[record["metadata"] for record in records],
        )
        rag = ChromaRag(collection, min_score=0.0)
        hits = rag.search(
            point_id="POI-BJ-001",
            name="故宫博物院",
            city_id="beijing",
            query="建议预留 3 到 4 小时，旺季适当增加排队时间。",
            moment=moment,
            top_k=10,
        )
        ids = [hit.chunk_id for hit in hits]
        assert ids and ids[0] == "CHUNK-BJ-001-02", ids
        assert "CHUNK-BJ-001-OLD-01" not in ids

        expired_probe = rag.search(
            point_id="POI-BJ-001",
            name="故宫博物院",
            city_id="beijing",
            query="这条过期内容不得被检索或展示。",
            moment=moment,
            top_k=10,
        )
        assert "CHUNK-BJ-001-OLD-01" not in [hit.chunk_id for hit in expired_probe]
        print(f"real chroma ok: indexed={len(records)} top={ids[0]} hits={len(ids)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
