package com.agenttravel.backend.auth;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 20;
    private static final long WINDOW_SECONDS = 60;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public boolean allow(String login) {
        long now = Instant.now().getEpochSecond();
        Window result = windows.compute(login, (key, current) -> {
            if (current == null || now - current.startedAt() >= WINDOW_SECONDS) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt(), current.attempts() + 1);
        });
        return result.attempts() <= MAX_ATTEMPTS;
    }

    private record Window(long startedAt, int attempts) {
    }
}
