"""Sync the Java published catalog snapshot to a Python catalog JSON file."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from app.knowledge.internal_client import InternalKnowledgeClient


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--city", default="北京")
    parser.add_argument(
        "--output",
        default=str(Path(__file__).resolve().parents[1] / "resources" / "published_catalog.java.json"),
    )
    args = parser.parse_args(argv)

    with InternalKnowledgeClient(args.base_url, timeout=10.0) as client:
        catalog = client.fetch_catalog(city=args.city)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"wrote {output}: pois={len(catalog['pois'])} entries={len(catalog['entries'])} "
        f"rules={len(catalog['rules'])}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
