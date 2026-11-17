package com.agenttravel.backend.revision;

import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.RequestContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trips/{tripId}/revisions")
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class RevisionController {

    private final RevisionService service;

    public RevisionController(RevisionService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public RevisionModels.RevisionResult preview(@PathVariable UUID tripId,
            @Valid @RequestBody RevisionModels.RevisionCommand command) {
        return service.preview(user(), tripId, command);
    }

    @PostMapping("/confirm")
    public RevisionModels.RevisionResult confirm(@PathVariable UUID tripId,
            @Valid @RequestBody RevisionModels.RevisionCommand command,
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey) {
        if (command.confirmationId() == null || !idempotencyKey.equals(command.confirmationId())) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_INPUT", "幂等键必须与 confirmation_id 一致");
        }
        return service.confirm(user(), tripId, command);
    }

    @PostMapping("/rollback/{versionId}")
    public RevisionModels.RevisionResult rollback(@PathVariable UUID tripId, @PathVariable UUID versionId,
            @RequestHeader("If-Match") String etag) {
        return service.rollback(user(), tripId, versionId, etag);
    }

    private UUID user() {
        RequestContext.Context context = RequestContext.get();
        if (context == null || context.userId() == null) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
        }
        return context.userId();
    }
}
