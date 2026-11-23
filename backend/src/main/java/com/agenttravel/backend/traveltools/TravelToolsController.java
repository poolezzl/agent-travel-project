package com.agenttravel.backend.traveltools;

import java.util.List;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
class TravelToolsController {
    private static final String VERSION = "/trips/{tripId}/versions/{versionId}";
    private final ExportService exports;
    private final ReminderService reminders;

    TravelToolsController(ExportService exports, ReminderService reminders) {
        this.exports = exports;
        this.reminders = reminders;
    }

    @PostMapping(VERSION + "/exports")
    ResponseEntity<ExportService.View> createExport(@PathVariable UUID tripId, @PathVariable UUID versionId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        UUID owner = owner();
        JsonNode format = body.path("format");
        if (body.size() != 1 || !format.isTextual() || !List.of("PDF", "ICS").contains(format.asText())) {
            throw invalid();
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(exports.create(owner, tripId, versionId, ExportRenderer.Format.valueOf(format.asText()), key));
    }

    @GetMapping("/exports/{exportId}")
    ResponseEntity<ExportService.View> getExport(@PathVariable UUID exportId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(exports.get(owner(), exportId));
    }

    @GetMapping("/exports/{exportId}/download")
    ResponseEntity<byte[]> download(@PathVariable UUID exportId,
            @RequestParam long expires, @RequestParam String signature) {
        ExportService.Download file = exports.download(owner(), exportId, expires, signature);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(file.format().mime))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("trip." + file.format().extension).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(file.bytes());
    }

    @PutMapping(VERSION + "/reminders")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void enableReminders(@PathVariable UUID tripId, @PathVariable UUID versionId) {
        reminders.authorize(owner(), tripId, versionId);
    }

    @DeleteMapping(VERSION + "/reminders")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancelReminders(@PathVariable UUID tripId, @PathVariable UUID versionId) {
        reminders.cancel(owner(), tripId, versionId);
    }

    @GetMapping("/notifications")
    ResponseEntity<List<ReminderService.Notice>> notifications(@RequestParam(defaultValue = "0") int page) {
        UUID owner = owner();
        if (page < 0 || page > 10000) {
            throw invalid();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reminders.notifications(owner, page));
    }

    private static UUID owner() {
        RequestContext.Context context = RequestContext.get();
        if (context == null || context.userId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
        }
        return context.userId();
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请求参数无效");
    }
}
