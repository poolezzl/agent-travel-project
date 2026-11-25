"""Call the live Java S15 internal endpoints from Python.

Prerequisite: Spring Boot backend on the given base URL, MySQL with a
PUBLISHED knowledge entry and the matching POI. This is the live counterpart of
``tests/test_internal_client.py`` (which uses MockTransport).
"""

from __future__ import annotations

import argparse
from datetime import date

from app.knowledge.internal_client import InternalKnowledgeClient


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--city", default="北京")
    parser.add_argument("--point-id", default="live-point-1")
    parser.add_argument("--poi-id", default=None)
    parser.add_argument("--name", required=True)
    parser.add_argument("--latitude", type=float, default=39.9)
    parser.add_argument("--longitude", type=float, default=116.4)
    parser.add_argument("--date", default="2026-11-20")
    args = parser.parse_args(argv)

    point = {
        "point_id": args.point_id,
        "name": args.name,
        "latitude": args.latitude,
        "longitude": args.longitude,
    }
    if args.poi_id:
        point["poi_id"] = args.poi_id

    with InternalKnowledgeClient(args.base_url, timeout=5.0) as client:
        catalog = client.fetch_catalog(city=args.city)
        attractions = client.query_attractions(city=args.city, points=[point])
        reservations = client.query_reservations(
            city=args.city, points=[point], day=date.fromisoformat(args.date)
        )
    poi_count = len(catalog["pois"])
    entry_count = len(catalog["entries"])
    rule_count = len(catalog["rules"])
    print(f"catalog pois={poi_count} entries={entry_count} rules={rule_count}")
    card_count = len(attractions["cards"])
    attraction_sources = len(attractions["source_metadata"])
    print(f"attractions cards={card_count} sources={attraction_sources}")
    if attractions["cards"]:
        card = attractions["cards"][0]
        print(
            f"first point={card.get('point_id')} "
            f"fact_type={card.get('fact_type')} source={card.get('source')}"
        )
    reservation_count = len(reservations["reservations"])
    reservation_sources = len(reservations["source_metadata"])
    print(f"reservations={reservation_count} sources={reservation_sources}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
