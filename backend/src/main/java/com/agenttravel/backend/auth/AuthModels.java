package com.agenttravel.backend.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

public final class AuthModels {

    private AuthModels() {
    }

    public record RegisterRequest(@NotBlank String login, @NotBlank String password, String role) {
    }

    public record LoginRequest(@NotBlank String login, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds,
            UUID userId, String role) {
    }

    public record CurrentUser(UUID userId, String login, String role, String status, Instant createdAt) {
    }
}
