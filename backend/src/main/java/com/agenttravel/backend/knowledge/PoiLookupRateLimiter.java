package com.agenttravel.backend.knowledge;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-process fixed-window limiter for the internal POI-by-ID lookup.
 *
 * <p>The endpoint is internal-only, so a single shared window is enough to stop a runaway agent
 * loop without adding a Redis dependency to a read path. The limit is configurable through
 * {@code app.internal.poi-lookup.max-per-second} / {@code INTERNAL_POI_LOOKUP_MAX_PER_SECOND}.
 */
@Component
public class PoiLookupRateLimiter {

    private static final int DEFAULT_MAX_PER_SECOND = 20;
    private final int maxPerSecond;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public PoiLookupRateLimiter(
            @Value("${app.internal.poi-lookup.max-per-second:"
                    + "${INTERNAL_POI_LOOKUP_MAX_PER_SECOND:20}}") int maxPerSecond) {
        this.maxPerSecond = maxPerSecond < 1 ? DEFAULT_MAX_PER_SECOND : maxPerSecond;
    }

    public boolean allow(String key) {
        long now = Instant.now().getEpochSecond();
        Window result = windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt() >= 1) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt(), current.attempts() + 1);
        });
        return result.attempts() <= maxPerSecond;
    }

    private record Window(long startedAt, int attempts) {
    }
}
