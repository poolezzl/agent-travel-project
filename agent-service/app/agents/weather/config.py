"""Centralized weather-agent configuration (ALG-07).

Concrete thresholds are PENDING-R2/R3 and kept in exactly one place so a single change
updates the pipeline and the S13 report. The supplier timezone is never hard-coded here — it
is always injected from the adapter, which must cover DST boundaries.
"""

from __future__ import annotations

# PENDING-R2/R3: sensitive-activity risk thresholds (real rules come from S17 / R3).
HEAVY_RAIN_PRECIP_PROB: float = 0.5
EXTREME_TEMP_MIN_C: float = 0.0
EXTREME_TEMP_MAX_C: float = 35.0
