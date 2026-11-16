"""Centralized weather-agent configuration (ALG-07).

Thresholds frozen on 2026-11-16 (S13 contract freeze) and kept in exactly one place so a
single change updates the pipeline and the S13 report. The supplier timezone is never
hard-coded here — it is always injected from the adapter, which must cover DST boundaries.
"""

from __future__ import annotations

# FROZEN 2026-11-16: sensitive-activity risk thresholds.
HEAVY_RAIN_PRECIP_PROB: float = 0.5
EXTREME_TEMP_MIN_C: float = 0.0
EXTREME_TEMP_MAX_C: float = 35.0
