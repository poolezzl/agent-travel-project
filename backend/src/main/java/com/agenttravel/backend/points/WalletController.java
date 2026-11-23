package com.agenttravel.backend.points;

import java.util.List;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.RequestContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/points")
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class WalletController {

    private final WalletService service;

    public WalletController(WalletService service) {
        this.service = service;
    }

    @GetMapping("/packages")
    public List<WalletModels.TopUpPackage> packages() {
        authenticated();
        return service.packages();
    }

    @GetMapping("/ledger")
    public WalletModels.LedgerPage ledger(@RequestParam(defaultValue = "0") int offset) {
        return service.ledger(authenticated().userId(), offset);
    }

    @GetMapping("/admin/ledger")
    public WalletModels.LedgerPage adminLedger(@RequestParam(required = false) UUID userId,
            @RequestParam(defaultValue = "0") int offset) {
        admin();
        return service.ledger(userId, offset);
    }

    @PostMapping("/top-up/quotes")
    public WalletModels.QuoteResponse topUpQuote(@Valid @RequestBody WalletModels.TopUpRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        return service.topUpQuote(authenticated().userId(), request, key);
    }

    @PostMapping("/admin/adjustments/quotes")
    public WalletModels.QuoteResponse adjustmentQuote(@Valid @RequestBody WalletModels.AdjustmentRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        return service.adjustmentQuote(admin().userId(), request, key);
    }

    @PostMapping("/wallet/confirm")
    public PointsModels.PointsResponse confirm(@Valid @RequestBody WalletModels.ConfirmRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        RequestContext.Context context = authenticated();
        return service.confirm(context.userId(), "ADMIN".equals(context.role()), request.quoteId(), key);
    }

    private static RequestContext.Context authenticated() {
        RequestContext.Context context = RequestContext.get();
        if (context == null || context.userId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
        }
        return context;
    }

    private static RequestContext.Context admin() {
        RequestContext.Context context = authenticated();
        if (!"ADMIN".equals(context.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "需要管理员权限");
        }
        return context;
    }
}
