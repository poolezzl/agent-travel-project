package com.agenttravel.backend.revision;

import java.util.List;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.RequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trip version history endpoints (part of the S24 history/version-evolution scope).
 *
 * <p>Responses are private data, so they are always served with {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/versions")
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TripVersionController {

    private final TripVersionService service;

    public TripVersionController(TripVersionService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<TripVersionModels.VersionSummary>> list(@PathVariable UUID tripId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        Context context = user();
        return noStore(service.list(context.userId(), context.role(), tripId, limit, offset));
    }

    @GetMapping("/{versionId}")
    public ResponseEntity<TripVersionModels.VersionDetail> get(@PathVariable UUID tripId,
            @PathVariable UUID versionId) {
        Context context = user();
        return noStore(service.get(context.userId(), context.role(), tripId, versionId));
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private Context user() {
        RequestContext.Context context = RequestContext.get();
        if (context == null || context.userId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
        }
        return new Context(context.userId(), context.role());
    }

    private record Context(UUID userId, String role) {
    }
}
