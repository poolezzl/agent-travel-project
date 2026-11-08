"""Assert that a fixed stdin input produces byte-identical stdout across two runs.

The script reads one JSON scenario from stdin, runs the S12 route step and the S13 weather
step against the deterministic mock ports (no network), and prints canonical JSON. With
``--self-check`` it spawns two fresh processes, feeds both the same bytes and compares the
two stdout streams byte for byte.

Usage::

    python scripts/check_determinism.py --self-check
    python scripts/check_determinism.py < fixture.json > out1.json
"""

from __future__ import annotations

import json
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.agents.route_planner.mocks import (  # noqa: E402
    MockPoiPort,
    MockRouteCache,
    MockRoutePort,
)
from app.agents.route_planner.models import PoiRecord, RoutePlanRequest  # noqa: E402
from app.agents.route_planner.planner import plan_route  # noqa: E402
from app.agents.route_planner.route_provider import RouteProvider  # noqa: E402
from app.agents.weather.mocks import MockWeatherCache, MockWeatherPort  # noqa: E402
from app.agents.weather.models import WeatherPlanRequest  # noqa: E402
from app.agents.weather.weather_provider import WeatherProvider  # noqa: E402
from app.agents.weather.worker import run_weather_step  # noqa: E402

FIXED_INPUT = {
    "weather": {
        "task_id": "det-task",
        "attempt_no": 1,
        "now": "2026-09-21T08:00:00+00:00",
        "timezone": "Asia/Shanghai",
        "requests": [
            {
                "city_id": "beijing",
                "date_range": {"start": "2026-09-21", "end": "2026-09-23"},
                "today": "2026-09-21",
                "forecast_horizon_days": 1,
            }
        ],
    },
    "route": {
        "request": {
            "date": "2026-09-21",
            "city_id": "110101",
            "points": [
                {"poi_id": "p1", "name": "A", "city_id": "110101"},
                {"poi_id": "p2", "name": "B", "city_id": "110101"},
                {"poi_id": "p3", "name": "C", "city_id": "110101"},
            ],
            "time_window": {"start": "09:00", "end": "18:00"},
        },
        "pois": [
            {
                "poi_id": "p1",
                "name": "A",
                "city_id": "110101",
                "latitude": 39.9042,
                "longitude": 116.3912,
                "coordinate_system": "GCJ02",
            },
            {
                "poi_id": "p2",
                "name": "B",
                "city_id": "110101",
                "latitude": 39.8997,
                "longitude": 116.3912,
                "coordinate_system": "GCJ02",
            },
            {
                "poi_id": "p3",
                "name": "C",
                "city_id": "110101",
                "latitude": 39.9200,
                "longitude": 116.4100,
                "coordinate_system": "GCJ02",
            },
        ],
        "coords": {
            "p1": [39.9042, 116.3912],
            "p2": [39.8997, 116.3912],
            "p3": [39.92, 116.41],
        },
        "speed_kmh": 40.0,
    },
}


def run(scenario: dict[str, object]) -> str:
    weather = scenario["weather"]
    route = scenario["route"]
    assert isinstance(weather, dict)
    assert isinstance(route, dict)

    now = datetime.fromisoformat(str(weather["now"])).astimezone(UTC)
    requests = [WeatherPlanRequest.model_validate(item) for item in weather["requests"]]
    port = MockWeatherPort()
    provider = WeatherProvider(
        port, MockWeatherCache(), timezone=str(weather["timezone"]), now=lambda: now
    )
    weather_result = run_weather_step(
        str(weather["task_id"]),
        int(weather["attempt_no"]),
        requests,
        provider,
        port,
        now=lambda: now,
    )

    request = RoutePlanRequest.model_validate(route["request"])
    pois = [PoiRecord.model_validate(item) for item in route["pois"]]
    coords = {key: (float(value[0]), float(value[1])) for key, value in route["coords"].items()}
    route_result = plan_route(
        request,
        MockPoiPort(pois),
        RouteProvider(MockRoutePort(coords, speed_kmh=float(route["speed_kmh"])), MockRouteCache()),
    )

    payload = {
        "weather": weather_result.model_dump(mode="json"),
        "route": route_result.model_dump(mode="json"),
    }
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _one_run(stdin_bytes: bytes) -> bytes:
    completed = subprocess.run(
        [sys.executable, str(Path(__file__).resolve())],
        input=stdin_bytes,
        cwd=PROJECT_ROOT,
        check=True,
        capture_output=True,
    )
    return completed.stdout


def main(argv: list[str]) -> int:
    if "--self-check" in argv:
        stdin_bytes = json.dumps(FIXED_INPUT, ensure_ascii=False).encode()
        first = _one_run(stdin_bytes)
        second = _one_run(stdin_bytes)
        if first != second:
            print("determinism check failed: two runs differ", file=sys.stderr)
            return 1
        print(f"determinism check passed: {len(first)} identical bytes", file=sys.stderr)
        return 0

    scenario = json.loads(sys.stdin.read())
    sys.stdout.write(run(scenario) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
