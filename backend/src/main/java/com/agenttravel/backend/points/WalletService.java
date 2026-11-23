package com.agenttravel.backend.points;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class WalletService {

    private static final List<WalletModels.TopUpPackage> PACKAGES = List.of(
            new WalletModels.TopUpPackage("small", 50), new WalletModels.TopUpPackage("standard", 100),
            new WalletModels.TopUpPackage("large", 200));
    private static final int PAGE_SIZE = 20;

    private final PointsRepository points;
    private final WalletRepository wallet;
    private final AuditRepository audit;

    public WalletService(PointsRepository points, WalletRepository wallet, AuditRepository audit) {
        this.points = points;
        this.wallet = wallet;
        this.audit = audit;
    }

    public List<WalletModels.TopUpPackage> packages() {
        return PACKAGES;
    }

    @Transactional
    public WalletModels.QuoteResponse topUpQuote(UUID actor, WalletModels.TopUpRequest request, String key) {
        long delta = PACKAGES.stream().filter(item -> item.code().equals(request.packageCode()))
                .findFirst().orElseThrow(() -> invalid("请选择有效的充值档位")).points();
        return issueQuote(actor, actor, request.packageCode(), delta, key);
    }

    @Transactional
    public WalletModels.QuoteResponse adjustmentQuote(UUID actor, WalletModels.AdjustmentRequest request, String key) {
        int delta;
        try {
            delta = request.pointsDelta().intValueExact();
        } catch (ArithmeticException exception) {
            throw invalid("调整积分必须为允许范围内的整数");
        }
        if (delta == 0) {
            throw invalid("调整积分不能为零");
        }
        return issueQuote(actor, request.userId(), null, delta, key);
    }

    private WalletModels.QuoteResponse issueQuote(UUID actor, UUID user, String packageCode, long delta, String key) {
        String keyHash = hashKey(key);
        points.lockAccount(actor);
        WalletRepository.QuoteRow prior = wallet.findQuote(actor, keyHash);
        if (prior != null) {
            if (!prior.userId().equals(user) || !Objects.equals(prior.packageCode(), packageCode)
                    || (packageCode == null && prior.delta() != delta)) {
                throw conflict("DUPLICATE_REQUEST", "该去重键已用于其他报价");
            }
            return prior.response();
        }

        WalletRepository.AccountSnapshot account = wallet.accountSnapshot(user);
        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONTEXT_NOT_FOUND", "积分账户不存在或不可用");
        }
        var quote = new WalletRepository.QuoteRow(UUID.randomUUID(), user, packageCode, delta,
                packageCode == null ? account.version() : null, newBalance(account.balance(), delta),
                Instant.now().plus(Duration.ofMinutes(5)), null);
        wallet.insertQuote(actor, keyHash, quote);
        return quote.response();
    }

    @Transactional
    public PointsModels.PointsResponse confirm(UUID actor, boolean admin, UUID quoteId, String key) {
        String reference = "wallet:" + actor + ":" + hashKey(key);
        WalletRepository.QuoteRow quote = wallet.lockQuote(actor, quoteId);
        if (quote == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONTEXT_NOT_FOUND", "报价不存在");
        }
        boolean adjustment = quote.packageCode() == null;
        if (adjustment && !admin) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "需要管理员权限");
        }
        if (quote.appliedReference() != null) {
            if (!quote.appliedReference().equals(reference)) {
                throw conflict("QUOTE_INVALID", "报价已使用");
            }
            PointsRepository.LedgerRow entry = points.findLedger(reference);
            if (entry == null) {
                throw new IllegalStateException("已确认报价缺少积分流水");
            }
            return new PointsModels.PointsResponse(reference, entry.delta(), entry.balanceAfter(), true);
        }
        if (!quote.expiresAt().isAfter(Instant.now())) {
            throw conflict("QUOTE_INVALID", "报价已过期，请重新报价");
        }

        PointsRepository.AccountRow account = points.lockAccount(quote.userId());
        if (points.findLedger(reference) != null) {
            throw conflict("DUPLICATE_REQUEST", "该去重键已用于其他入账");
        }
        long after = newBalance(account.balance(), quote.delta());
        if (!wallet.updateActiveBalance(account.id(), after, quote.version())) {
            throw conflict("CONCURRENCY_CONFLICT", "目标账户已变化，请重新报价");
        }
        String type = adjustment ? "ADMIN_ADJUST" : "TOP_UP";
        try {
            points.insertLedger(account.id(), quote.userId(), null, null, type, quote.delta(),
                    account.balance(), after, reference);
        } catch (DuplicateKeyException exception) {
            throw conflict("DUPLICATE_REQUEST", "该去重键已用于其他入账");
        }
        wallet.markApplied(quote.id(), reference);
        audit.write(actor, "POINTS_" + type, "POINTS_ACCOUNT", account.id(), "SUCCESS", null);
        return new PointsModels.PointsResponse(reference, quote.delta(), after, false);
    }

    public WalletModels.LedgerPage ledger(UUID user, int offset) {
        if (offset < 0 || offset > Integer.MAX_VALUE - PAGE_SIZE) {
            throw invalid("流水分页位置不正确");
        }
        List<WalletModels.LedgerEntry> entries = wallet.ledger(user, offset, PAGE_SIZE + 1);
        if (entries.size() <= PAGE_SIZE) {
            return new WalletModels.LedgerPage(entries, null);
        }
        return new WalletModels.LedgerPage(entries.subList(0, PAGE_SIZE), offset + PAGE_SIZE);
    }

    private static long newBalance(long balance, long delta) {
        if (delta < 0 && balance < -delta) {
            throw conflict("INSUFFICIENT_POINTS", "积分余额不足");
        }
        if (delta > 0 && balance > Long.MAX_VALUE - delta) {
            throw conflict("INVALID_INPUT", "调整后余额超出允许范围");
        }
        return balance + delta;
    }

    private static String hashKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw invalid("去重键不合法");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }
}
