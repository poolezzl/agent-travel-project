"""Hard-constraint checks and schedule simulation (ALG-05).

Keeps commute, visit, queue and dining time strictly separate: the schedule only consumes
travel duration from the matrix and visit duration from ``NodeTiming``; it never mixes door-to
door, walking, queueing or dining time into route duration.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass


def parse_hhmm(text: str) -> int:
    """Parse ``HH:MM`` into minutes since midnight, raising ``ValueError`` on invalid input."""
    parts = text.split(":")
    if len(parts) != 2:
        raise ValueError(f"invalid HH:MM value: {text!r}")
    try:
        hour = int(parts[0])
        minute = int(parts[1])
    except ValueError as exc:
        raise ValueError(f"invalid HH:MM value: {text!r}") from exc
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        raise ValueError(f"invalid HH:MM value: {text!r}")
    return hour * 60 + minute


@dataclass(frozen=True)
class NodeTiming:
    """Schedule inputs for a single node (a point, or a fixed origin/destination)."""

    poi_id: str
    visit_duration_min: float
    open_min: int | None
    close_min: int | None
    lunch_start_min: int | None
    lunch_end_min: int | None


@dataclass(frozen=True)
class OrderEvaluation:
    """Result of simulating a full order against hard constraints."""

    total_duration: float
    conflicts: tuple[str, ...]
    has_estimated: bool
    has_unavailable: bool


def evaluate_order(
    order: tuple[int, ...],
    cost: Callable[[int, int], float],
    leg_status: Callable[[int, int], str],
    timings: Sequence[NodeTiming],
    day_start_min: int,
    day_end_min: int,
) -> OrderEvaluation:
    """Simulate arrivals/departures across an order and collect hard-constraint conflicts.

    ``leg_status(i, j)`` returns ``"exact"`` / ``"estimated"`` / ``"unavailable"``. The caller
    decides whether estimated legs are allowed for the hard gate.
    """
    conflicts: list[str] = []
    has_estimated = False
    has_unavailable = False
    total_duration = 0.0

    if not order:
        return OrderEvaluation(0.0, ("empty order",), False, False)

    clock = float(day_start_min)
    for idx, node in enumerate(order):
        if idx > 0:
            prev = order[idx - 1]
            status = leg_status(prev, node)
            travel = cost(prev, node)
            if status == "unavailable":
                has_unavailable = True
                conflicts.append(f"leg {prev}->{node} unavailable")
            if status == "estimated":
                has_estimated = True
            if travel <= 0:
                conflicts.append(f"leg {prev}->{node} non-positive duration")
            total_duration += travel
            clock += travel

        timing = timings[node]
        arrival = clock
        if timing.open_min is not None and arrival < timing.open_min:
            conflicts.append(f"{timing.poi_id} arrival before opening")
        if timing.close_min is not None and arrival + timing.visit_duration_min > timing.close_min:
            conflicts.append(f"{timing.poi_id} departure after closing")
        if timing.lunch_start_min is not None and timing.lunch_end_min is not None:
            visit_end = arrival + timing.visit_duration_min
            if visit_end > timing.lunch_start_min and arrival < timing.lunch_end_min:
                conflicts.append(f"{timing.poi_id} visit overlaps lunch break")

        clock += timing.visit_duration_min
        if clock > day_end_min:
            conflicts.append(f"{timing.poi_id} departure beyond day window")

    return OrderEvaluation(
        total_duration=total_duration,
        conflicts=tuple(conflicts),
        has_estimated=has_estimated,
        has_unavailable=has_unavailable,
    )
