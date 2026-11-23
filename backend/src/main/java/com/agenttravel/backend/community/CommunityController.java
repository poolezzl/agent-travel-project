package com.agenttravel.backend.community;

import static com.agenttravel.backend.community.CommunityModels.*;

import java.util.List;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(CommunityController.ROOT)
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
class CommunityController {
    static final String ROOT = "/api/v1/community";
    private static final String KEY = "Idempotency-Key";
    private final CommunityService service;

    CommunityController(CommunityService service) {
        this.service = service;
    }

    @PostMapping(value = "/posts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Id publish(@RequestHeader(KEY) String key, @Valid @RequestPart("post") Publish post,
            @RequestPart(value = "images", required = false) List<MultipartFile> images) {
        return service.publish(actor(), key, post, images == null ? List.of() : images);
    }

    @GetMapping("/posts")
    List<Post> feed(@RequestParam(defaultValue = "LATEST") Sort sort,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Size(max = 128) String city,
            @RequestParam(required = false) Theme theme,
            @RequestParam(required = false) @Min(1) @Max(30) Integer days,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return service.posts(new Filter(city, theme, days, null, limit, offset), sort, q, false);
    }

    @GetMapping("/rankings")
    List<Post> rankings(@RequestParam(required = false) @Size(max = 128) String city,
            @RequestParam(required = false) Theme theme,
            @RequestParam(required = false) @Min(1) @Max(30) Integer days,
            @RequestParam(required = false) Duration duration,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return service.posts(new Filter(city, theme, days, duration, limit, offset), Sort.LATEST, null, true);
    }

    @GetMapping("/posts/{postId}")
    Post post(@PathVariable UUID postId) {
        return service.post(postId);
    }

    @GetMapping(value = "/posts/{postId}/images/{objectId}", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> image(@PathVariable UUID postId, @PathVariable UUID objectId,
            @RequestParam long expires, @RequestParam String signature) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .body(service.image(postId, objectId, expires, signature));
    }

    @GetMapping("/posts/{postId}/comments")
    List<Comment> comments(@PathVariable UUID postId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return service.comments(postId, limit, offset);
    }

    @PostMapping("/posts/{postId}/comments")
    Id comment(@PathVariable UUID postId, @RequestHeader(KEY) String key, @Valid @RequestBody CommentRequest request) {
        return service.comment(actor(), postId, key, request);
    }

    @PutMapping("/posts/{postId}/reactions/{reaction}")
    Id react(@PathVariable UUID postId, @PathVariable Reaction reaction, @RequestHeader(KEY) String key) {
        return service.react(actor(), postId, key, reaction, true);
    }

    @DeleteMapping("/posts/{postId}/reactions/{reaction}")
    Id unreact(@PathVariable UUID postId, @PathVariable Reaction reaction, @RequestHeader(KEY) String key) {
        return service.react(actor(), postId, key, reaction, false);
    }

    @PostMapping("/posts/{postId}/reports")
    Id report(@PathVariable UUID postId, @RequestHeader(KEY) String key, @Valid @RequestBody Report request) {
        return service.report(actor(), postId, null, key, request);
    }

    @PostMapping("/posts/{postId}/comments/{commentId}/reports")
    Id reportComment(@PathVariable UUID postId, @PathVariable UUID commentId,
            @RequestHeader(KEY) String key, @Valid @RequestBody Report request) {
        return service.report(actor(), postId, commentId, key, request);
    }

    @PostMapping("/posts/{postId}/reuse")
    Id reuse(@PathVariable UUID postId, @RequestHeader(KEY) String key, @Valid @RequestBody Reuse request) {
        return service.reuse(actor(), postId, key, request);
    }

    @GetMapping("/drafts/{draftId}")
    Draft draft(@PathVariable UUID draftId) {
        return service.draft(actor(), draftId);
    }

    @DeleteMapping("/posts/{postId}")
    Id withdraw(@PathVariable UUID postId, @RequestHeader(KEY) String key, @RequestHeader("If-Match") long version) {
        return service.review(actor(), postId, key, version, ReviewAction.WITHDRAW, false);
    }

    @GetMapping("/moderation")
    List<ReviewItem> queue(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        admin();
        return service.queue(limit, offset);
    }

    @PostMapping("/moderation/{postId}")
    Id review(@PathVariable UUID postId, @RequestHeader(KEY) String key, @RequestHeader("If-Match") long version,
            @Valid @RequestBody Review command) {
        return service.review(admin(), postId, key, version, command.action(), true);
    }

    @PostMapping("/moderation/{postId}/cases/{caseId}")
    Id resolve(@PathVariable UUID postId, @PathVariable UUID caseId, @RequestHeader(KEY) String key,
            @RequestHeader("If-Match") long version, @Valid @RequestBody Resolution command) {
        return service.resolve(admin(), postId, caseId, key, version, command.action());
    }

    private UUID actor() {
        RequestContext.Context context = RequestContext.get();
        if (context == null || context.userId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
        }
        return context.userId();
    }

    private UUID admin() {
        UUID actor = actor();
        if (!"ADMIN".equals(RequestContext.get().role())) {
            throw CommunityService.forbidden();
        }
        return actor;
    }
}
