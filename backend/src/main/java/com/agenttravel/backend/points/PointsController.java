package com.agenttravel.backend.points;

import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.RequestContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Lazy;

@RestController
@Lazy
@RequestMapping("/api/v1/points")
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class PointsController {

    private final PointsService service;

    public PointsController(PointsService service) {
        this.service = service;
    }

    @PostMapping("/quotes")
    public PointsModels.QuoteResponse quote(@Valid @RequestBody PointsModels.QuoteRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.quote(user(), request, idempotencyKey);
    }

    @PostMapping("/reserve")
    public PointsModels.PointsResponse reserve(@Valid @RequestBody PointsModels.ReserveRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.reserve(user(), request, idempotencyKey);
    }

    @PostMapping("/confirm")
    public PointsModels.PointsResponse confirm(@Valid @RequestBody PointsModels.SettlementRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.confirm(user(), request, idempotencyKey);
    }

    @PostMapping("/refund")
    public PointsModels.PointsResponse refund(@Valid @RequestBody PointsModels.SettlementRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.refund(user(), request, idempotencyKey);
    }

    @PostMapping("/grant")
    public PointsModels.PointsResponse grant(@Valid @RequestBody PointsModels.GrantRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireAdmin();
        return service.grant(user(), request, idempotencyKey);
    }

    @GetMapping("/balance")
    public PointsModels.BalanceResponse balance() {
        return service.balance(user());
    }

    private UUID user() {
        RequestContext.Context context = RequestContext.get();
        if (context == null || context.userId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
        }
        return context.userId();
    }

    private void requireAdmin() {
        RequestContext.Context context = RequestContext.get();
        if (context == null || !"ADMIN".equals(context.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "需要管理员权限");
        }
    }
}
