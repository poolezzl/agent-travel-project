package com.agenttravel.backend.points;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class WalletModels {

    private WalletModels() {
    }

    public record TopUpPackage(String code, long points) {
    }

    public record TopUpRequest(@NotBlank @Size(max = 32) String packageCode) {
    }

    public record AdjustmentRequest(@NotNull UUID userId, @NotNull BigDecimal pointsDelta) {
    }

    public record ConfirmRequest(@NotNull UUID quoteId) {
    }

    public record QuoteResponse(UUID quoteId, UUID userId, long pointsDelta, long balanceAfter, Instant expiresAt) {
    }

    public record LedgerEntry(UUID id, UUID userId, UUID taskId, String entryType, long pointsDelta, long balanceAfter,
            Instant createdAt) {
    }

    public record LedgerPage(List<LedgerEntry> entries, Integer nextOffset) {
    }
}
