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

/** Admin surface for the S15 knowledge base. Every operation requires the ADMIN role. */
@RestController
@Lazy
@RequestMapping("/api/v1/admin/knowledge")
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class KnowledgeAdminController {

    private final KnowledgeService service;

    public KnowledgeAdminController(KnowledgeService service) {
        this.service = service;
    }

    @PostMapping("/sources")
    public KnowledgeModels.SourceView registerSource(
            @Valid @RequestBody KnowledgeModels.SourceRegistrationRequest request) {
        return service.registerSource(admin(), request);
    }

    @GetMapping("/sources/{sourceSnapshotId}")
    public KnowledgeModels.SourceView source(@PathVariable UUID sourceSnapshotId) {
        admin();
        return service.source(sourceSnapshotId);
    }

    @PostMapping("/pois")
    public KnowledgeModels.PoiView createPoi(@Valid @RequestBody KnowledgeModels.PoiDraftRequest request) {
        return service.createPoi(admin(), request);
    }

    @PostMapping("/pois/{poiId}/aliases")
    public KnowledgeModels.AliasView addAlias(@PathVariable UUID poiId,
            @Valid @RequestBody KnowledgeModels.AliasRequest request) {
        return service.addAlias(admin(), poiId, request);
    }

    @GetMapping("/pois/conflicts")
    public List<KnowledgeModels.PoiConflictView> conflicts() {
        admin();
        return service.listPoiConflicts();
    }

    @PostMapping("/entries")
    public KnowledgeModels.KnowledgeView createEntry(
            @Valid @RequestBody KnowledgeModels.EntryDraftRequest request) {
        return service.createEntry(admin(), request);
    }

    @PutMapping("/entries/{entryId}")
    public KnowledgeModels.KnowledgeView updateEntry(@PathVariable UUID entryId,
            @Valid @RequestBody KnowledgeModels.EntryDraftRequest request) {
        return service.updateEntry(admin(), entryId, request);
    }

    @GetMapping("/entries/{entryId}")
    public KnowledgeModels.KnowledgeView entry(@PathVariable UUID entryId) {
        admin();
        return service.entry(entryId);
    }

    @GetMapping("/entries")
    public List<KnowledgeModels.KnowledgeView> entries(@RequestParam String city) {
        admin();
        return service.entries(city);
    }

    @GetMapping("/entries/{entryId}/chunks")
    public List<KnowledgeModels.ChunkView> chunks(@PathVariable UUID entryId) {
        admin();
        return service.chunks(entryId);
    }

    @PostMapping("/entries/{entryId}/submit")
    public KnowledgeModels.KnowledgeView submitEntry(@PathVariable UUID entryId,
            @Valid @RequestBody KnowledgeModels.EntryTransitionRequest request) {
        return service.submitEntry(admin(), entryId, request.expectedVersionNo());
    }

    @PostMapping("/entries/{entryId}/publish")
    public KnowledgeModels.KnowledgeView publishEntry(@PathVariable UUID entryId,
            @Valid @RequestBody KnowledgeModels.EntryTransitionRequest request) {
        return service.publishEntry(admin(), entryId, request.expectedVersionNo());
    }

    @PostMapping("/entries/{entryId}/disable")
    public KnowledgeModels.KnowledgeView disableEntry(@PathVariable UUID entryId,
            @Valid @RequestBody KnowledgeModels.EntryTransitionRequest request) {
        return service.disableEntry(admin(), entryId, request.expectedVersionNo());
    }

    @PostMapping("/entries/{entryId}/rollback")
    public KnowledgeModels.KnowledgeView rollbackEntry(@PathVariable UUID entryId,
            @Valid @RequestBody KnowledgeModels.EntryTransitionRequest request) {
        return service.rollbackEntry(admin(), entryId, request.expectedVersionNo());
    }

    @PostMapping("/imports/preview")
    public KnowledgeModels.ImportPreviewResponse previewImport(
            @Valid @RequestBody KnowledgeModels.ImportPreviewRequest request) {
        return service.previewImport(admin(), request);
    }

    @PostMapping("/imports/commit")
    public KnowledgeModels.ImportCommitResponse commitImport(
            @Valid @RequestBody KnowledgeModels.ImportPreviewRequest request) {
        return service.commitImport(admin(), request);
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
