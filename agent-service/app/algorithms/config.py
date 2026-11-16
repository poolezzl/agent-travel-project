"""Centralized algorithm configuration (ALG-05 / ALG-06).

Frozen on 2026-11-16 (S12 contract freeze). The algorithm version, seed, 2-opt budget,
straight-line estimate speed and deterministic tie-break are defined in exactly one place
so a single change updates the whole pipeline and the S12 report.
"""

from __future__ import annotations

# FROZEN 2026-11-16: algorithm version literal cited by reports and output annotations.
ALGORITHM_VERSION: str = "route-greedy-2opt-v0"

# FROZEN 2026-11-16: seed is retained for future randomized variants; current greedy + 2-opt
# is deterministic and does not depend on it.
ALGORITHM_SEED: int = 0

# FROZEN 2026-11-16: 2-opt stop condition / search budget.
TWO_OPT_MAX_ROUNDS: int = 10

# FROZEN 2026-11-16 (ALG-06): straight-line estimate speed, used only for `is_estimated`
# fallback legs; never presented as a road route.
STRAIGHT_LINE_SPEED_KMH: float = 40.0

# FROZEN 2026-11-16: equal-cost candidates are ordered by ascending index, implemented in
# app.algorithms.tsp via ``min(..., key=(cost, index))``.
