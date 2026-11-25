"""Build the Agent-owned Chroma index from the published catalog snapshot.

Usage:
    python scripts/build_chroma_index.py --catalog resources/published_catalog.json \
        --persist-dir .chroma --collection r3_knowledge --reset

Requires the optional ``rag`` dependencies:
    pip install -e ".[rag]"

The script only indexes entries that are ``PUBLISHED`` and not expired at the
time of indexing.  ``ChromaRag`` additionally filters ``effective_until_epoch``
at query time, so an index that has not been refreshed cannot leak expired
content.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from app.knowledge.catalog import PublishedCatalog
from app.knowledge.index import index_records


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[1]
    parser.add_argument("--catalog", default=str(root / "resources" / "published_catalog.json"))
    parser.add_argument("--persist-dir", default=str(root / ".chroma"))
    parser.add_argument("--collection", default="r3_knowledge")
    parser.add_argument("--model", default="BAAI/bge-small-zh-v1.5")
    parser.add_argument("--batch-size", type=int, default=100)
    parser.add_argument("--reset", action="store_true", help="drop and recreate the collection")
    args = parser.parse_args(argv)

    try:
        import chromadb
        from chromadb.utils.embedding_functions import SentenceTransformerEmbeddingFunction
    except ImportError as exc:
        print('缺少 Chroma/bge 依赖，请先安装：pip install -e ".[rag]"', file=sys.stderr)
        print(f"原始错误：{exc}", file=sys.stderr)
        return 2

    catalog = PublishedCatalog.load(args.catalog)
    records = index_records(catalog)
    if not records:
        print("没有可索引的已发布内容", file=sys.stderr)
        return 1

    client = chromadb.PersistentClient(path=args.persist_dir)
    if args.reset:
        try:
            client.delete_collection(args.collection)
        except Exception:  # noqa: BLE001 - collection may not exist yet
            pass
    embedding = SentenceTransformerEmbeddingFunction(model_name=args.model)
    collection = client.get_or_create_collection(name=args.collection, embedding_function=embedding)

    for start in range(0, len(records), args.batch_size):
        batch = records[start : start + args.batch_size]
        collection.upsert(
            ids=[record["id"] for record in batch],
            documents=[record["document"] for record in batch],
            metadatas=[record["metadata"] for record in batch],
        )

    print(
        f"indexed {len(records)} chunks into {args.collection!r} "
        f"at {args.persist_dir} (catalog {catalog.catalog_version})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
