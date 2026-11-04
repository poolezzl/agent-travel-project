package com.agenttravel.backend.knowledge;

import java.util.List;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.RequestContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin surface for reservation rule versions. Every operation requires the ADMIN role. */
@RestController
@Lazy
@RequestMapping("/api/v1/admin/reservation-rules")
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class ReservationRuleAdminController {

    private final KnowledgeService service;

    public ReservationRuleAdminController(KnowledgeService service) {
        this.service = service;
    }

    @PostMapping
    public KnowledgeModels.ReservationRuleView createRule(
            @Valid @RequestBody KnowledgeModels.ReservationRuleDraftRequest request) {
        return service.createRule(admin(), request);
    }

    @PutMapping("/{ruleId}")
    public KnowledgeModels.ReservationRuleView updateRule(@PathVariable UUID ruleId,
            @Valid @RequestBody KnowledgeModels.ReservationRuleDraftRequest request) {
        return service.updateRule(admin(), ruleId, request);
    }

    @GetMapping("/{ruleId}")
    public KnowledgeModels.ReservationRuleView rule(@PathVariable UUID ruleId) {
        admin();
        return service.rule(ruleId);
    }

    @GetMapping
    public List<KnowledgeModels.ReservationRuleView> rules(@RequestParam String city) {
        admin();
        return service.rules(city);
    }

    @PostMapping("/{ruleId}/submit")
    public KnowledgeModels.ReservationRuleView submitRule(@PathVariable UUID ruleId,
            @Valid @RequestBody KnowledgeModels.EntryTransitionRequest request) {
        return service.submitRule(admin(), ruleId, request.expectedVersionNo());
    }

    @PostMapping("/{ruleId}/publish")
    public KnowledgeModels.ReservationRuleView publishRule(@PathVariable UUID ruleId,
            @Valid @RequestBody KnowledgeModels.EntryTransitionRequest request) {
        return service.publishRule(admin(), ruleId, request.expectedVersionNo());
    }

    @PostMapping("/{ruleId}/disable")
    public KnowledgeModels.ReservationRuleView disableRule(@PathVariable UUID ruleId,
            @Valid @RequestBody KnowledgeModels.EntryTransitionRequest request) {
        return service.disableRule(admin(), ruleId, request.expectedVersionNo());
    }

    @PostMapping("/{ruleId}/rollback")
    public KnowledgeModels.ReservationRuleView rollbackRule(@PathVariable UUID ruleId,
            @Valid @RequestBody KnowledgeModels.EntryTransitionRequest request) {
        return service.rollbackRule(admin(), ruleId, request.expectedVersionNo());
    }

    private UUID admin() {
        RequestContext.Context context = RequestContext.get();
        if (context == null || !"ADMIN".equals(context.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "需要管理员权限");
        }
        if (context.userId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
        }
        return context.userId();
    }
}
