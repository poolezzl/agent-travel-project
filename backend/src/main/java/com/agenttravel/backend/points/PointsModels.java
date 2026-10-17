package com.agenttravel.backend.points;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class PointsModels {

    private PointsModels() {
    }

    public record QuoteRequest(@Min(1) @Max(3) int planCount, UUID taskId) {
    }

    public record QuoteResponse(UUID quoteId, String quoteReference, int planCount, long points, long expiresAtEpochSecond) {
    }

    public record ReserveRequest(@NotBlank String quoteReference, UUID taskId) {
    }

    public record SettlementRequest(@NotBlank String reservationReference) {
    }

    public record GrantRequest(@NotNull UUID userId, @Min(1) long points) {
    }

    public record PointsResponse(String businessReference, long pointsDelta, long balancePoints, boolean reused) {
    }

    public record BalanceResponse(long balancePoints) {
    }
}
