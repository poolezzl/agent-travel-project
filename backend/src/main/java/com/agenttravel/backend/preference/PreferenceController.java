package com.agenttravel.backend.preference;

import java.time.LocalDate;
import java.util.List;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.RequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class PreferenceController {

    private final PreferenceService service;

    public PreferenceController(PreferenceService service) {
        this.service = service;
    }

    @GetMapping("/history/trips")
    public List<PreferenceModels.HistoricalTrip> history(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return service.listTrips(authenticated().userId(), from, to, city, days, status, q,
                Math.min(Math.max(limit, 1), 100), Math.max(offset, 0));
    }

    @PostMapping("/trips/{tripId}/reuse")
    public PreferenceModels.ReuseResult reuse(
            @PathVariable java.util.UUID tripId,
            @RequestHeader("Idempotency-Key") String key) {
        return service.reuse(authenticated().userId(), tripId, key);
    }

    @GetMapping("/trips/{tripId}/versions/compare")
    public ResponseEntity<PreferenceModels.VersionComparison> compare(
            @PathVariable java.util.UUID tripId,
            @RequestParam(required = false) java.util.UUID from,
            @RequestParam(required = false) java.util.UUID to) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.compare(authenticated().userId(), tripId, from, to));
    }

    @GetMapping("/preferences/summary")
    public ResponseEntity<PreferenceModels.PreferenceSummary> summary() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.summary(authenticated().userId()));
    }

    @DeleteMapping("/preferences/summary")
    public ResponseEntity<Void> clear(
            @RequestHeader("Idempotency-Key") String key) {
        service.clear(authenticated().userId(), key);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences/recall")
    public List<PreferenceModels.PreferenceItem> recall(
            @RequestParam(name = "top_k", required = false) Integer topK) {
        return service.recalledPreferences(authenticated().userId(), topK);
    }

    private static RequestContext.Context authenticated() {
        RequestContext.Context context = RequestContext.get();
        if (context == null || context.userId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
        }
        return context;
    }
}
