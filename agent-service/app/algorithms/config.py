"""Centralized algorithm configuration (ALG-05 / ALG-06).

Concrete seed value, algorithm-version format, tie-break rule and search budget are all
PENDING-R2 (see docs/多智能体协作文旅规划平台-算法设计说明书.md §4.5). They are defined in
exactly one place so a single change updates the whole pipeline and the S12 report.
"""

from __future__ import annotations

# PENDING-R2: algorithm version format not frozen. Kept as a stable string literal so the
# report and any output annotation can cite it.
ALGORITHM_VERSION: str = "route-greedy-2opt-v0"

# PENDING-R2: concrete seed value not frozen. Greedy + 2-opt with deterministic tie-break is
# fully deterministic; the seed is retained for future randomized variants and fixed here
# rather than scattered across the codebase.
ALGORITHM_SEED: int = 0

# PENDING-R2: 2-opt stop condition / search budget not frozen.
TWO_OPT_MAX_ROUNDS: int = 10

# Straight-line estimation derives duration_min from haversine distance at a fixed average
# speed. PENDING-R2/R5 (ALG-06): formula parameters are not frozen.
STRAIGHT_LINE_SPEED_KMH: float = 40.0

# Deterministic tie-break (PENDING-R2): equal-cost candidates are ordered by ascending index.
# This is implemented in app.algorithms.tsp via ``min(..., key=(cost, index))``.
