package com.agenttravel.backend.points;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;

@Service
@Lazy
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class PointsService {

    private final PointsRepository repository;
    private final AuditRepository audit;

    public PointsService(PointsRepository repository, AuditRepository audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional
    public PointsModels.QuoteResponse quote(UUID userId, PointsModels.QuoteRequest request, String idempotencyKey) {
        requireKey(idempotencyKey);
        if (request.planCount() < 1 || request.planCount() > 3) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "方案数量只能是 1 至 3");
        }
        if (request.taskId() != null && !repository.ownsTask(request.taskId(), userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONTEXT_NOT_FOUND", "任务不存在");
        }
        long points = switch (request.planCount()) {
            case 1 -> 10;
            case 2 -> 18;
            case 3 -> 25;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "方案数量不受支持");
        };
        String reference = "quote:" + userId + ":" + idempotencyKey;
        PointsRepository.QuoteRow existing = repository.findQuoteByReference(reference, userId);
        if (existing != null) {
            return new PointsModels.QuoteResponse(existing.id(), existing.reference(), existing.planCount(), existing.points(),
                    existing.expiresAt().getEpochSecond());
        }
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));
        UUID quoteId = UUID.randomUUID();
        repository.insertQuote(quoteId, userId, reference, request.planCount(), points, expiresAt, request.taskId());
        audit.write(userId, "POINTS_QUOTE", "POINTS_QUOTE", quoteId, "SUCCESS", null);
        return new PointsModels.QuoteResponse(quoteId, reference, request.planCount(), points, expiresAt.getEpochSecond());
    }

    @Transactional
    public PointsModels.PointsResponse reserve(UUID userId, PointsModels.ReserveRequest request, String idempotencyKey) {
        requireKey(idempotencyKey);
        String reference = "reserve:" + userId + ":" + idempotencyKey;
        PointsRepository.AccountRow account = repository.lockAccount(userId);
        PointsRepository.LedgerRow reused = repository.findLedger(reference);
        if (reused != null) {
            if (!userId.equals(reused.userId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "幂等键不属于当前用户");
            }
            return new PointsModels.PointsResponse(reference, reused.delta(), reused.balanceAfter(), true);
        }
        PointsRepository.QuoteRow quote = repository.findQuoteForUser(request.quoteReference(), userId);
        if (quote == null || !"ISSUED".equals(quote.status()) || !quote.expiresAt().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "QUOTE_INVALID", "报价不存在、已过期或已使用");
        }
        if (request.taskId() != null && (!request.taskId().equals(quote.taskId()) || !repository.ownsTask(request.taskId(), userId))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "任务不属于当前用户或与报价不一致");
        }
        if (account.balance() < quote.points()) {
            audit.write(userId, "POINTS_RESERVE", "POINTS_QUOTE", quote.id(), "DENIED", "INSUFFICIENT_POINTS");
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_POINTS", "积分余额不足");
        }
        long after = account.balance() - quote.points();
        repository.insertLedger(account.id(), userId, quote.taskId(), quote.id(), "RESERVE", -quote.points(), account.balance(), after, reference);
        repository.updateBalance(account.id(), after);
        repository.setQuoteStatus(quote.id(), "ACCEPTED");
        if (quote.taskId() != null) {
            if (repository.reserveTask(quote.taskId(), quote.id(), quote.points(), userId) != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "任务状态已变化，请重新报价");
            }
        }
        audit.write(userId, "POINTS_RESERVE", "POINTS_QUOTE", quote.id(), "SUCCESS", null);
        return new PointsModels.PointsResponse(reference, -quote.points(), after, false);
    }

    @Transactional
    public <T> java.util.Optional<T> settleRevision(UUID userId, UUID tripId, String fingerprint,
            Supplier<T> publishVersion) {
        String reference = "revision:" + userId + ":" + fingerprint;
        String reserveReference = reference + ":reserve";
        PointsRepository.AccountRow account = repository.lockAccount(userId);
        if (repository.findLedger(reserveReference) != null) {
            // A concurrent duplicate confirmation already paid for this fingerprint.
            return java.util.Optional.empty();
        }
        if (account.balance() < 2) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_POINTS", "局部重规划需要 2 积分");
        }
        long after = account.balance() - 2;
        if (repository.insertLedgerIfAbsent(account.id(), userId, null, null, "RESERVE", -2, account.balance(), after,
                reserveReference) == 0) {
            return java.util.Optional.empty();
        }
        repository.updateBalance(account.id(), after);
        T published = publishVersion.get();
        repository.insertLedgerIfAbsent(account.id(), userId, null, null, "CONFIRM", 0, after, after,
                reference + ":confirm");
        audit.write(userId, "POINTS_REVISION_CONFIRM", "TRIP", tripId, "SUCCESS", null);
        return java.util.Optional.of(published);
    }

    @Transactional
    public PointsModels.PointsResponse confirm(UUID userId, PointsModels.SettlementRequest request, String idempotencyKey) {
        requireKey(idempotencyKey);
        String reserveReference = normalizeReservation(request.reservationReference());
        PointsRepository.LedgerRow reserve = repository.findLedger(reserveReference);
        if (reserve == null || reserve.delta() >= 0 || !userId.equals(reserve.userId())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONTEXT_NOT_FOUND", "预扣记录不存在");
        }
        PointsRepository.AccountRow account = repository.lockAccount(userId);
        String settlementHash = shortHash(reserveReference);
        String reference = "confirm:" + userId + ":" + settlementHash;
        PointsRepository.LedgerRow existing = repository.findLedger(reference);
        if (existing != null) {
            return new PointsModels.PointsResponse(reference, existing.delta(), existing.balanceAfter(), true);
        }
        if (repository.findLedger("refund:" + userId + ":" + settlementHash) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", "该预扣已退款");
        }
        repository.insertLedger(account.id(), userId, null, null, "CONFIRM", 0, account.balance(), account.balance(), reference);
        audit.write(userId, "POINTS_CONFIRM", "POINTS_LEDGER", null, "SUCCESS", null);
        return new PointsModels.PointsResponse(reference, 0, reserve.balanceAfter(), false);
    }

    @Transactional
    public PointsModels.PointsResponse refund(UUID userId, PointsModels.SettlementRequest request, String idempotencyKey) {
        // A task reservation is refunded by the task state machine only. Letting the owner
        // settle it directly would leave planning_task.reserved_points disagreeing with the
        // ledger and could strand an in-flight assemble.
        if (repository.ledgerBelongsToTask(normalizeReservation(request.reservationReference()))) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT",
                    "任务预扣只能由任务状态机退款（取消、失败或确认超时）");
        }
        return doRefund(userId, request, idempotencyKey);
    }

    /** Internal refund used by the task state machine (cancel, failure, confirmation timeout). */
    @Transactional
    public PointsModels.PointsResponse refundForTask(UUID userId, PointsModels.SettlementRequest request,
            String idempotencyKey) {
        return doRefund(userId, request, idempotencyKey);
    }

    private PointsModels.PointsResponse doRefund(UUID userId, PointsModels.SettlementRequest request,
            String idempotencyKey) {
        requireKey(idempotencyKey);
        String reserveReference = normalizeReservation(request.reservationReference());
        PointsRepository.LedgerRow reserve = repository.findLedger(reserveReference);
        if (reserve == null || reserve.delta() >= 0 || !userId.equals(reserve.userId())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONTEXT_NOT_FOUND", "预扣记录不存在");
        }
        PointsRepository.AccountRow account = repository.lockAccount(userId);
        String settlementHash = shortHash(reserveReference);
        String reference = "refund:" + userId + ":" + settlementHash;
        PointsRepository.LedgerRow existing = repository.findLedger(reference);
        if (existing != null) {
            return new PointsModels.PointsResponse(reference, existing.delta(), existing.balanceAfter(), true);
        }
        if (repository.findLedger("confirm:" + userId + ":" + settlementHash) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", "该预扣已确认");
        }
        long after = account.balance() - reserve.delta();
        repository.insertLedger(account.id(), userId, null, null, "REFUND", -reserve.delta(), account.balance(), after, reference);
        repository.updateBalance(account.id(), after);
        audit.write(userId, "POINTS_REFUND", "POINTS_LEDGER", null, "SUCCESS", null);
        return new PointsModels.PointsResponse(reference, -reserve.delta(), after, false);
    }

    @Transactional
    public PointsModels.PointsResponse grant(UUID actorId, PointsModels.GrantRequest request, String idempotencyKey) {
        requireKey(idempotencyKey);
        String reference = "grant:manual:" + actorId + ":" + idempotencyKey;
        PointsRepository.AccountRow actorAccount = repository.lockAccount(actorId);
        PointsRepository.AccountRow targetAccount = actorId.equals(request.userId())
                ? actorAccount
                : repository.lockAccount(request.userId());
        PointsRepository.LedgerRow existing = repository.findLedger(reference);
        if (existing != null) {
            if (!request.userId().equals(existing.userId())) {
                throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "幂等键已用于其他赠送目标");
            }
            return new PointsModels.PointsResponse(reference, existing.delta(), existing.balanceAfter(), true);
        }
        long after = Math.addExact(targetAccount.balance(), request.points());
        repository.insertLedger(targetAccount.id(), request.userId(), null, null, "GRANT", request.points(),
                targetAccount.balance(), after, reference);
        repository.updateBalance(targetAccount.id(), after);
        audit.write(actorId, "POINTS_GRANT", "POINTS_ACCOUNT", request.userId(), "SUCCESS", null);
        return new PointsModels.PointsResponse(reference, request.points(), after, false);
    }

    public PointsModels.BalanceResponse balance(UUID userId) {
        return new PointsModels.BalanceResponse(repository.balance(userId));
    }

    private String normalizeReservation(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "预扣引用不能为空");
        }
        return value.startsWith("reserve:") ? value : "reserve:" + value;
    }

    private void requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "幂等键不合法");
        }
    }

    private String shortHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
