"""Deterministic greedy construction and 2-opt improvement (ALG-05).

The order is solved here, never by the LLM. Soft preferences (e.g. "walk less", "fewer
transfers") must be translated into weights/constraints upstream and are then transparently
applied through the ``cost`` function.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence


def greedy_construct(
    nodes: Sequence[int],
    start: int,
    cost: Callable[[int, int], float],
) -> tuple[int, ...]:
    """Build an initial visiting order via nearest-neighbour.

    Tie-break is deterministic: among equal-cost candidates the smaller index wins, which
    mirrors the centralized tie-break documented in ``app.algorithms.config``.
    """
    remaining = set(nodes)
    remaining.discard(start)
    order: list[int] = [start]
    current = start
    while remaining:
        nxt = min(remaining, key=lambda candidate: (cost(current, candidate), candidate))
        order.append(nxt)
        remaining.discard(nxt)
        current = nxt
    return tuple(order)


def total_cost(order: Sequence[int], cost: Callable[[int, int], float]) -> float:
    """Sum of pairwise travel cost along an order."""
    result = 0.0
    for left, right in zip(order, order[1:], strict=False):
        result += cost(left, right)
    return result


def two_opt_swap(order: tuple[int, ...], i: int, j: int) -> tuple[int, ...]:
    """Reverse the sub-path order[i..j] (standard 2-opt move)."""
    return order[:i] + tuple(reversed(order[i : j + 1])) + order[j + 1 :]


def two_opt_improve(
    order: tuple[int, ...],
    cost: Callable[[int, int], float],
    feasible: Callable[[tuple[int, ...]], bool],
    max_rounds: int,
) -> tuple[int, ...]:
    """2-opt local search accepting a swap only when cost decreases AND order stays feasible.

    Indices ``i`` and ``j`` are bounded to ``1..len(order)-2`` so the first and last nodes
    (fixed origin / destination when present) are never moved.
    """
    current = order
    current_cost = total_cost(current, cost)
    for _ in range(max_rounds):
        improved = False
        for i in range(1, len(current) - 1):
            for j in range(i + 1, len(current) - 1):
                candidate = two_opt_swap(current, i, j)
                if not feasible(candidate):
                    continue
                candidate_cost = total_cost(candidate, cost)
                if candidate_cost < current_cost:
                    current = candidate
                    current_cost = candidate_cost
                    improved = True
        if not improved:
            break
    return current
