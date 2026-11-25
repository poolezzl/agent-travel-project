package com.agenttravel.backend.knowledge;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import com.agenttravel.backend.knowledge.KnowledgeModels.AliasRequest;
import com.agenttravel.backend.knowledge.KnowledgeModels.AliasView;
import com.agenttravel.backend.knowledge.KnowledgeModels.AttractionCard;
import com.agenttravel.backend.knowledge.KnowledgeModels.AttractionQueryRequest;
import com.agenttravel.backend.knowledge.KnowledgeModels.AttractionQueryResponse;
import com.agenttravel.backend.knowledge.KnowledgeModels.ChunkRequest;
import com.agenttravel.backend.knowledge.KnowledgeModels.ChunkView;
import com.agenttravel.backend.knowledge.KnowledgeModels.EntryDraftRequest;
import com.agenttravel.backend.knowledge.KnowledgeModels.ImportCommitResponse;
import com.agenttravel.backend.knowledge.KnowledgeModels.ImportError;
import com.agenttravel.backend.knowledge.KnowledgeModels.ImportPreviewRequest;
import com.agenttravel.backend.knowledge.KnowledgeModels.ImportPreviewResponse;
import com.agenttravel.backend.knowledge.KnowledgeModels.ImportRow;
import com.agenttravel.backend.knowledge.KnowledgeModels.KnowledgeView;
import com.agenttravel.backend.knowledge.KnowledgeModels.PoiConflictResolveRequest;
import com.agenttravel.backend.knowledge.KnowledgeModels.PoiConflictView;
import com.agenttravel.backend.knowledge.KnowledgeModels.PoiDraftRequest;
import com.agenttravel.backend.knowledge.KnowledgeModels.PoiLookupResponse;
import com.agenttravel.backend.knowledge.KnowledgeModels.PoiView;
import com.agenttravel.backend.knowledge.KnowledgeModels.QueryPoint;
import com.agenttravel.backend.knowledge.KnowledgeModels.ReservationItem;
import com.agenttravel.backend.knowledge.KnowledgeModels.ReservationQueryRequest;
import com.agenttravel.backend.knowledge.KnowledgeModels.ReservationQueryResponse;
import com.agenttravel.backend.knowledge.KnowledgeModels.ReservationRuleDraftRequest;
import com.agenttravel.backend.knowledge.KnowledgeModels.ReservationRuleView;
import com.agenttravel.backend.knowledge.KnowledgeModels.SourceMetadata;
import com.agenttravel.backend.knowledge.KnowledgeModels.SourceRegistrationRequest;
import com.agenttravel.backend.knowledge.KnowledgeModels.SourceView;
import com.agenttravel.backend.knowledge.KnowledgeRepository.ChunkDraft;
import com.agenttravel.backend.knowledge.KnowledgeRepository.ChunkRow;
import com.agenttravel.backend.knowledge.KnowledgeRepository.ImportBatchDraft;
import com.agenttravel.backend.knowledge.KnowledgeRepository.KnowledgeDraft;
import com.agenttravel.backend.knowledge.KnowledgeRepository.KnowledgeRow;
import com.agenttravel.backend.knowledge.KnowledgeRepository.PoiConflictDraft;
import com.agenttravel.backend.knowledge.KnowledgeRepository.PoiConflictRow;
import com.agenttravel.backend.knowledge.KnowledgeRepository.PoiDraft;
import com.agenttravel.backend.knowledge.KnowledgeRepository.PoiRow;
import com.agenttravel.backend.knowledge.KnowledgeRepository.PoiSnapshotRow;
import com.agenttravel.backend.knowledge.KnowledgeRepository.RuleDraft;
import com.agenttravel.backend.knowledge.KnowledgeRepository.RuleRow;
import com.agenttravel.backend.knowledge.KnowledgeRepository.SourceRegistration;
import com.agenttravel.backend.knowledge.KnowledgeRepository.SourceRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S15 knowledge operations: source snapshots, POI drafts, review lifecycle, import and the
 * read-only internal queries consumed by the Python agents.
 *
 * <p>All writes are audited. Facts are never auto-merged: ambiguous POIs only create entries in
 * {@code poi_merge_candidate} for a human operator.
 */
@Service
@Lazy
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class KnowledgeService {

    private static final Set<String> FACT_TYPES = Set.of("opening_hours", "highlights", "avoid_tips",
            "suggested_duration", "accessibility", "best_time", "popularity");
    private static final Set<String> SOURCE_TYPES = Set.of("MAP", "WEATHER", "KNOWLEDGE_BASE",
            "RESERVATION_RULE", "POI", "USER_INPUT", "SYSTEM");
    private static final Set<String> IMPORT_TYPES = Set.of("ATTRACTION_KNOWLEDGE", "POI",
            "RESERVATION_RULE");
    private static final double COORDINATE_TOLERANCE = 0.001d;

    private final KnowledgeRepository repository;
    private final AuditRepository audit;
    private final ObjectMapper objectMapper;

    public KnowledgeService(KnowledgeRepository repository, AuditRepository audit, ObjectMapper objectMapper) {
        this.repository = repository;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- sources

    @Transactional
    public SourceView registerSource(UUID actor, SourceRegistrationRequest request) {
        if (!SOURCE_TYPES.contains(request.sourceType())) {
            throw badRequest("SOURCE_TYPE_INVALID", "来源类型不受支持");
        }
        SourceRow row = repository.registerSource(new SourceRegistration(request.sourceType(),
                request.providerName(), request.externalRecordId(), request.sourceUrl(), request.licenseType(),
                request.licenseReference(), request.permittedUse(), Boolean.TRUE.equals(request.attributionRequired()),
                request.validUntil(), request.summary(),
                request.summary() == null ? Map.of() : request.summary(), Instant.now()));
        audit.write(actor, "KNOWLEDGE_SOURCE_REGISTER", "SOURCE_SNAPSHOT", row.sourceSnapshotId(), "SUCCESS", null);
        return sourceView(row);
    }

    public SourceView source(UUID sourceSnapshotId) {
        SourceRow row = repository.findSourceSnapshot(sourceSnapshotId);
        if (row == null) {
            throw notFound("SOURCE_NOT_FOUND", "来源快照不存在");
        }
        return sourceView(row);
    }

    // -------------------------------------------------------------------- POI

    @Transactional
    public PoiView createPoi(UUID actor, PoiDraftRequest request) {
        UUID cityId = requireCity(request.city());
        if (repository.findSourceSnapshot(request.sourceSnapshotId()) == null) {
            throw notFound("SOURCE_NOT_FOUND", "POI 来源快照不存在");
        }
        String coordinateSystem = blank(request.coordinateSystem()) ? "WGS84" : request.coordinateSystem();
        PoiRow poi = repository.insertPoi(new PoiDraft(cityId, request.externalPoiId(), request.name(),
                request.category(), request.district(), request.longitude(), request.latitude(),
                coordinateSystem, request.mergeGroupId(), request.sourceSnapshotId()));

        List<UUID> conflicts = new ArrayList<>();
        for (PoiRow sameName : repository.findPoisByName(cityId, request.name())) {
            if (!sameName.poiId().equals(poi.poiId())) {
                conflicts.add(repository.insertPoiConflict(new PoiConflictDraft(cityId, poi.poiId(),
                        sameName.poiId(), "NAME", Map.of("name", request.name()))));
            }
        }
        for (PoiRow near : repository.findPoisNear(cityId, request.latitude(), request.longitude(),
                COORDINATE_TOLERANCE)) {
            if (!near.poiId().equals(poi.poiId())) {
                conflicts.add(repository.insertPoiConflict(new PoiConflictDraft(cityId, poi.poiId(),
                        near.poiId(), "COORDINATE", Map.of("latitude", request.latitude(), "longitude",
                                request.longitude()))));
            }
        }
        if (request.aliases() != null) {
            for (String alias : new LinkedHashSet<>(request.aliases())) {
                repository.insertAlias(poi.poiId(), alias, "MANUAL");
                conflicts.addAll(recordAliasConflicts(cityId, poi.poiId(), alias));
            }
        }
        audit.write(actor, "KNOWLEDGE_POI_CREATE", "POI", poi.poiId(), "SUCCESS", null);
        return poiView(poi, conflicts);
    }

    @Transactional
    public AliasView addAlias(UUID actor, UUID poiId, AliasRequest request) {
        PoiRow poi = requirePoi(poiId);
        String aliasType = blank(request.aliasType()) ? "MANUAL" : request.aliasType();
        UUID aliasId = repository.insertAlias(poiId, request.alias(), aliasType);
        List<UUID> conflicts = recordAliasConflicts(poi.cityId(), poiId, request.alias());
        audit.write(actor, "KNOWLEDGE_ALIAS_CREATE", "POI_ALIAS", aliasId, "SUCCESS", null);
        return new AliasView(aliasId, poiId, request.alias(), aliasType, "ACTIVE", conflicts);
    }

    public List<PoiConflictView> listPoiConflicts() {
        List<PoiConflictView> views = new ArrayList<>();
        for (PoiConflictRow row : repository.listOpenPoiConflicts()) {
            views.add(new PoiConflictView(row.candidateId(), row.cityCode(), row.candidatePoiId(),
                    row.candidateName(), row.existingPoiId(), row.existingName(), row.conflictType(),
                    row.status(), row.createdAt()));
        }
        return views;
    }

    @Transactional
    public PoiConflictView resolvePoiConflict(UUID actor, UUID conflictId, PoiConflictResolveRequest request) {
        PoiConflictRow conflict = repository.findPoiConflict(conflictId);
        if (conflict == null) {
            throw notFound("CONFLICT_NOT_FOUND", "冲突记录不存在");
        }
        if (!"OPEN".equals(conflict.status())) {
            throw conflict("CONFLICT_ALREADY_RESOLVED", "冲突记录已处理");
        }
        if (request.decision() == null || blank(request.decision())) {
            throw badRequest("DECISION_INVALID", "decision 只能是 MERGE 或 REJECT");
        }
        String decision = request.decision().trim().toUpperCase(Locale.ROOT);
        switch (decision) {
            case "MERGE" -> {
                PoiRow candidate = requirePoi(conflict.candidatePoiId());
                PoiRow existing = requirePoi(conflict.existingPoiId());
                UUID mergeGroupId = existing.mergeGroupId() != null ? existing.mergeGroupId()
                        : candidate.mergeGroupId() != null ? candidate.mergeGroupId() : UUID.randomUUID();
                if (repository.setPoiMergeGroup(candidate.poiId(), mergeGroupId) != 1
                        || repository.setPoiMergeGroup(existing.poiId(), mergeGroupId) != 1) {
                    throw notFound("POI_NOT_FOUND", "冲突关联的 POI 不存在");
                }
                if (repository.resolvePoiConflict(conflictId, "MERGED", actor, Instant.now()) != 1) {
                    throw conflict("CONCURRENCY_CONFLICT", "冲突记录已变化");
                }
                audit.write(actor, "KNOWLEDGE_POI_MERGE", "POI_MERGE_CANDIDATE", conflictId, "SUCCESS", null);
            }
            case "REJECT" -> {
                if (repository.resolvePoiConflict(conflictId, "REJECTED", actor, Instant.now()) != 1) {
                    throw conflict("CONCURRENCY_CONFLICT", "冲突记录已变化");
                }
                audit.write(actor, "KNOWLEDGE_POI_CONFLICT_REJECT", "POI_MERGE_CANDIDATE", conflictId,
                        "SUCCESS", null);
            }
            default -> throw badRequest("DECISION_INVALID", "decision 只能是 MERGE 或 REJECT");
        }
        PoiConflictRow resolved = repository.findPoiConflict(conflictId);
        return new PoiConflictView(resolved.candidateId(), resolved.cityCode(), resolved.candidatePoiId(),
                resolved.candidateName(), resolved.existingPoiId(), resolved.existingName(),
                resolved.conflictType(), resolved.status(), resolved.createdAt());
    }

    private List<UUID> recordAliasConflicts(UUID cityId, UUID candidatePoiId, String alias) {
        List<UUID> conflicts = new ArrayList<>();
        for (PoiRow existing : repository.findPoisByAlias(cityId, alias)) {
            if (!existing.poiId().equals(candidatePoiId)) {
                conflicts.add(repository.insertPoiConflict(new PoiConflictDraft(cityId, candidatePoiId,
                        existing.poiId(), "ALIAS", Map.of("alias", alias))));
            }
        }
        return conflicts;
    }

    // -------------------------------------------------------------- knowledge

    @Transactional
    public KnowledgeView createEntry(UUID actor, EntryDraftRequest request) {
        KnowledgeDraft draft = entryDraft(actor, request, null);
        KnowledgeRow row = repository.insertEntry(draft);
        audit.write(actor, "KNOWLEDGE_ENTRY_CREATE", "KNOWLEDGE_ENTRY", row.knowledgeId(), "SUCCESS", null);
        return knowledgeView(row);
    }

    @Transactional
    public KnowledgeView updateEntry(UUID actor, UUID entryId, EntryDraftRequest request) {
        KnowledgeRow current = requireEntry(entryId);
        if (current.status() != ReviewStatus.DRAFT) {
            throw conflict("INVALID_STATE_TRANSITION", "只有 DRAFT 版本可以编辑");
        }
        KnowledgeDraft draft = entryDraft(actor, request, current);
        if (repository.updateEntryDraft(entryId, draft) != 1) {
            throw conflict("CONCURRENCY_CONFLICT", "知识条目已被其他操作修改");
        }
        KnowledgeRow updated = requireEntry(entryId);
        audit.write(actor, "KNOWLEDGE_ENTRY_UPDATE", "KNOWLEDGE_ENTRY", entryId, "SUCCESS", null);
        return knowledgeView(updated);
    }

    public KnowledgeView entry(UUID entryId) {
        return knowledgeView(requireEntry(entryId));
    }

    public List<KnowledgeView> entries(String city) {
        return repository.findEntriesByCity(city).stream().map(this::knowledgeView).toList();
    }

    public List<ChunkView> chunks(UUID entryId) {
        requireEntry(entryId);
        return repository.findChunks(entryId).stream()
                .map(chunk -> new ChunkView(chunk.chunkId(), chunk.chunkNo(), chunk.contentText(),
                        chunk.vectorRecordId()))
                .toList();
    }

    @Transactional
    public KnowledgeView submitEntry(UUID actor, UUID entryId, Integer expectedVersionNo) {
        return transitionEntry(actor, entryId, ReviewStatus.REVIEW, expectedVersionNo);
    }

    @Transactional
    public KnowledgeView publishEntry(UUID actor, UUID entryId, Integer expectedVersionNo) {
        return transitionEntry(actor, entryId, ReviewStatus.PUBLISHED, expectedVersionNo);
    }

    @Transactional
    public KnowledgeView disableEntry(UUID actor, UUID entryId, Integer expectedVersionNo) {
        return transitionEntry(actor, entryId, ReviewStatus.DISABLED, expectedVersionNo);
    }

    private KnowledgeView transitionEntry(UUID actor, UUID entryId, ReviewStatus next, Integer expectedVersionNo) {
        KnowledgeRow current = requireEntry(entryId);
        requireExpectedVersion(current.versionNo(), expectedVersionNo);
        ReviewStatus.requireTransition(current.status(), next);
        int updated = repository.transitionEntry(entryId, current.status(), next, Instant.now(), actor);
        if (updated != 1) {
            throw conflict("CONCURRENCY_CONFLICT", "知识条目状态已变化");
        }
        KnowledgeRow row = requireEntry(entryId);
        audit.write(actor, "KNOWLEDGE_" + next.name(), "KNOWLEDGE_ENTRY", entryId, "SUCCESS", null);
        return knowledgeView(row);
    }

    @Transactional
    public KnowledgeView rollbackEntry(UUID actor, UUID entryId, Integer expectedVersionNo) {
        KnowledgeRow source = requireEntry(entryId);
        requireExpectedVersion(source.versionNo(), expectedVersionNo);
        if (source.status() != ReviewStatus.PUBLISHED) {
            throw conflict("INVALID_STATE_TRANSITION", "只有 PUBLISHED 版本可以回滚");
        }
        int rolledBack = repository.transitionEntry(entryId, ReviewStatus.PUBLISHED, ReviewStatus.ROLLED_BACK,
                Instant.now(), actor);
        if (rolledBack != 1) {
            throw conflict("CONCURRENCY_CONFLICT", "知识条目状态已变化");
        }
        KnowledgeRow created = repository.insertEntry(new KnowledgeDraft(source.entryKey(), source.poiId(),
                source.cityId(), entryId, repository.nextEntryVersion(source.entryKey()), source.title(),
                source.factType(), source.factValueJson(), source.effectiveFrom(), source.effectiveUntil(),
                source.lastVerifiedAt(), source.sourceSnapshotId(), actor, chunkDrafts(entryId)));
        audit.write(actor, "KNOWLEDGE_ROLLBACK", "KNOWLEDGE_ENTRY", created.knowledgeId(), "SUCCESS", null);
        return knowledgeView(created);
    }

    private KnowledgeDraft entryDraft(UUID actor, EntryDraftRequest request, KnowledgeRow existing) {
        PoiRow poi = requirePoi(request.poiId());
        UUID cityId = requireCity(request.city());
        if (!poi.cityId().equals(cityId)) {
            throw badRequest("CITY_MISMATCH", "POI 与知识条目城市不一致");
        }
        if (!FACT_TYPES.contains(request.factType())) {
            throw badRequest("FACT_TYPE_INVALID", "知识事实类型不受支持");
        }
        if (request.effectiveUntil() != null && request.effectiveFrom() != null
                && request.effectiveUntil().isBefore(request.effectiveFrom())) {
            throw badRequest("INVALID_VALIDITY", "生效结束时间不能早于开始时间");
        }
        if (repository.findSourceSnapshot(request.sourceSnapshotId()) == null) {
            throw notFound("SOURCE_NOT_FOUND", "知识来源快照不存在");
        }
        if (request.parentEntryId() != null && repository.findEntry(request.parentEntryId()) == null) {
            throw notFound("PARENT_NOT_FOUND", "父知识条目不存在");
        }
        UUID parentEntryId = request.parentEntryId() != null ? request.parentEntryId()
                : existing == null ? null : existing.parentEntryId();
        String entryKey = existing != null ? existing.entryKey()
                : blank(request.entryKey()) ? "kb-" + UUID.randomUUID() : request.entryKey();
        int versionNo = existing != null ? existing.versionNo() : repository.nextEntryVersion(entryKey);
        List<ChunkDraft> chunks = new ArrayList<>();
        int chunkNo = 1;
        for (ChunkRequest chunk : request.chunks()) {
            chunks.add(new ChunkDraft(chunkNo++, chunk.text(), chunk.vectorRecordId()));
        }
        return new KnowledgeDraft(entryKey, poi.poiId(), cityId, parentEntryId, versionNo,
                request.title(), request.factType(), json(request.factValue()), request.effectiveFrom(),
                request.effectiveUntil(), request.lastVerifiedAt(), request.sourceSnapshotId(), actor, chunks);
    }

    private List<ChunkDraft> chunkDrafts(UUID entryId) {
        List<ChunkDraft> chunks = new ArrayList<>();
        for (ChunkRow chunk : repository.findChunks(entryId)) {
            chunks.add(new ChunkDraft(chunk.chunkNo(), chunk.contentText(), chunk.vectorRecordId()));
        }
        return chunks;
    }

    private KnowledgeView knowledgeView(KnowledgeRow row) {
        SourceView source = sourceView(repository.findSourceSnapshot(row.sourceSnapshotId()));
        List<ChunkView> chunks = repository.findChunks(row.knowledgeId()).stream()
                .map(chunk -> new ChunkView(chunk.chunkId(), chunk.chunkNo(), chunk.contentText(),
                        chunk.vectorRecordId()))
                .toList();
        return new KnowledgeView(row.knowledgeId(), row.entryKey(), row.poiId(), row.poiName(), row.cityCode(),
                row.parentEntryId(), row.versionNo(), row.title(), row.factType(), row.factValueJson(),
                row.status(), row.effectiveFrom(), row.effectiveUntil(), row.lastVerifiedAt(),
                row.publishedAt(), row.reviewedAt(), row.reviewedBy(), row.disabledAt(),
                row.sourceSnapshotId(), source, chunks);
    }

    private PoiView poiView(PoiRow poi, List<UUID> conflicts) {
        return new PoiView(poi.poiId(), poi.cityCode(), poi.externalPoiId(), poi.name(), poi.category(),
                poi.district(), poi.longitude(), poi.latitude(), poi.coordinateSystem(), poi.status(),
                poi.mergeGroupId(), repository.findAliases(poi.poiId()), conflicts);
    }

    // -------------------------------------------------------- reservation rule

    @Transactional
    public ReservationRuleView createRule(UUID actor, ReservationRuleDraftRequest request) {
        PoiRow poi = requirePoi(request.poiId());
        String ruleKey = blank(request.ruleKey()) ? "rule-" + UUID.randomUUID() : request.ruleKey();
        RuleRow row = repository.insertRule(ruleDraft(ruleKey, poi.poiId(), null,
                repository.nextRuleVersion(ruleKey), request));
        audit.write(actor, "RESERVATION_RULE_CREATE", "RESERVATION_RULE", row.policyId(), "SUCCESS", null);
        return ruleView(row);
    }

    @Transactional
    public ReservationRuleView updateRule(UUID actor, UUID ruleId, ReservationRuleDraftRequest request) {
        RuleRow current = requireRule(ruleId);
        if (current.status() != ReviewStatus.DRAFT) {
            throw conflict("INVALID_STATE_TRANSITION", "只有 DRAFT 版本可以编辑");
        }
        if (repository.updateRuleDraft(ruleId, ruleDraft(current.ruleKey(), current.poiId(), current.parentRuleId(),
                current.versionNo(), request)) != 1) {
            throw conflict("CONCURRENCY_CONFLICT", "预约规则已被其他操作修改");
        }
        RuleRow updated = requireRule(ruleId);
        audit.write(actor, "RESERVATION_RULE_UPDATE", "RESERVATION_RULE", ruleId, "SUCCESS", null);
        return ruleView(updated);
    }

    public ReservationRuleView rule(UUID ruleId) {
        return ruleView(requireRule(ruleId));
    }

    public List<ReservationRuleView> rules(String city) {
        return repository.findRulesByCity(city).stream().map(this::ruleView).toList();
    }

    @Transactional
    public ReservationRuleView submitRule(UUID actor, UUID ruleId, Integer expectedVersionNo) {
        return transitionRule(actor, ruleId, ReviewStatus.REVIEW, expectedVersionNo);
    }

    @Transactional
    public ReservationRuleView publishRule(UUID actor, UUID ruleId, Integer expectedVersionNo) {
        return transitionRule(actor, ruleId, ReviewStatus.PUBLISHED, expectedVersionNo);
    }

    @Transactional
    public ReservationRuleView disableRule(UUID actor, UUID ruleId, Integer expectedVersionNo) {
        return transitionRule(actor, ruleId, ReviewStatus.DISABLED, expectedVersionNo);
    }

    private ReservationRuleView transitionRule(UUID actor, UUID ruleId, ReviewStatus next, Integer expectedVersionNo) {
        RuleRow current = requireRule(ruleId);
        requireExpectedVersion(current.versionNo(), expectedVersionNo);
        ReviewStatus.requireTransition(current.status(), next);
        if (repository.transitionRule(ruleId, current.status(), next, Instant.now(), actor) != 1) {
            throw conflict("CONCURRENCY_CONFLICT", "预约规则状态已变化");
        }
        RuleRow row = requireRule(ruleId);
        audit.write(actor, "RESERVATION_RULE_" + next.name(), "RESERVATION_RULE", ruleId, "SUCCESS", null);
        return ruleView(row);
    }

    @Transactional
    public ReservationRuleView rollbackRule(UUID actor, UUID ruleId, Integer expectedVersionNo) {
        RuleRow source = requireRule(ruleId);
        requireExpectedVersion(source.versionNo(), expectedVersionNo);
        if (source.status() != ReviewStatus.PUBLISHED) {
            throw conflict("INVALID_STATE_TRANSITION", "只有 PUBLISHED 版本可以回滚");
        }
        if (repository.transitionRule(ruleId, ReviewStatus.PUBLISHED, ReviewStatus.ROLLED_BACK, Instant.now(),
                actor) != 1) {
            throw conflict("CONCURRENCY_CONFLICT", "预约规则状态已变化");
        }
        int versionNo = repository.nextRuleVersion(source.ruleKey());
        RuleRow created = repository.insertRule(new RuleDraft(source.ruleKey(), source.poiId(), ruleId, versionNo,
                source.ticketType(), source.audience(), source.channel(), source.reservationRequired(),
                source.releaseTime(), source.releaseTimezone(), source.advanceDays(), source.currency(),
                source.amountMinor(), source.freeCondition(), source.effectiveFrom(), source.effectiveUntil(),
                source.holidayException(), source.lastVerifiedAt(), source.staleAfterDays(),
                source.sourceSnapshotId()));
        audit.write(actor, "RESERVATION_RULE_ROLLBACK", "RESERVATION_RULE", created.policyId(), "SUCCESS", null);
        return ruleView(created);
    }

    private RuleDraft ruleDraft(String ruleKey, UUID poiId, UUID parentRuleId, int versionNo,
            ReservationRuleDraftRequest request) {
        if (request.effectiveFrom() == null || request.lastVerifiedAt() == null) {
            throw badRequest("INVALID_VALIDITY", "生效时间和核验时间不能为空");
        }
        if (request.effectiveUntil() != null && request.effectiveUntil().isBefore(request.effectiveFrom())) {
            throw badRequest("INVALID_VALIDITY", "生效结束时间不能早于开始时间");
        }
        if ((request.currency() == null) != (request.amountMinor() == null)) {
            throw badRequest("PRICE_INCOMPLETE", "币种和价格必须同时提供或同时为空");
        }
        LocalTime releaseTime = null;
        if (!blank(request.releaseTime())) {
            try {
                releaseTime = LocalTime.parse(request.releaseTime());
            } catch (RuntimeException exception) {
                throw badRequest("RELEASE_TIME_INVALID", "放行时间格式不合法");
            }
        }
        int staleAfterDays = request.staleAfterDays() == null ? 30 : request.staleAfterDays();
        if (staleAfterDays <= 0) {
            throw badRequest("STALE_WINDOW_INVALID", "过期天数必须大于 0");
        }
        return new RuleDraft(ruleKey, poiId, parentRuleId, versionNo, request.ticketType(), request.audience(),
                request.channel(), Boolean.TRUE.equals(request.reservationRequired()), releaseTime,
                blank(request.releaseTimezone()) ? "Asia/Shanghai" : request.releaseTimezone(),
                request.advanceDays(), request.currency() == null ? null : request.currency().toUpperCase(),
                request.amountMinor(), request.freeCondition(), request.effectiveFrom(), request.effectiveUntil(),
                request.holidayException(), request.lastVerifiedAt(), staleAfterDays, request.sourceSnapshotId());
    }

    private ReservationRuleView ruleView(RuleRow row) {
        boolean stale = row.lastVerifiedAt() != null && row.lastVerifiedAt()
                .plus(Duration.ofDays(row.staleAfterDays())).isBefore(Instant.now());
        return new ReservationRuleView(row.policyId(), row.ruleKey(), row.poiId(), row.poiName(), row.cityCode(),
                row.parentRuleId(), row.versionNo(), row.ticketType(), row.audience(), row.channel(),
                row.reservationRequired(), row.releaseTime(), row.releaseTimezone(), row.advanceDays(),
                row.currency(), row.amountMinor(), row.freeCondition(), row.effectiveFrom(), row.effectiveUntil(),
                row.holidayException(), row.lastVerifiedAt(), row.staleAfterDays(), stale, row.status(),
                row.publishedAt(), row.reviewedAt(), row.reviewedBy(), row.sourceSnapshotId(),
                sourceView(repository.findSourceSnapshot(row.sourceSnapshotId())));
    }

    // ---------------------------------------------------------------- imports

    public ImportPreviewResponse previewImport(UUID actor, ImportPreviewRequest request) {
        requireImportType(request.importType());
        List<ImportError> errors = validateRows(request.importType(), request.rows());
        Set<Integer> invalidRows = new LinkedHashSet<>();
        for (ImportError error : errors) {
            invalidRows.add(error.rowNo());
        }
        int valid = request.rows().size() - invalidRows.size();
        audit.write(actor, "KNOWLEDGE_IMPORT_PREVIEW", "IMPORT_BATCH", null, errors.isEmpty() ? "SUCCESS" : "REJECTED",
                errors.isEmpty() ? null : "IMPORT_VALIDATION_FAILED");
        return new ImportPreviewResponse(request.rows().size(), valid, errors.size(), errors);
    }

    @Transactional
    public ImportCommitResponse commitImport(UUID actor, ImportPreviewRequest request) {
        String importType = requireImportType(request.importType());
        List<ImportError> errors = validateRows(importType, request.rows());
        String errorsJson = errors.isEmpty() ? null : json(errors);
        if (!errors.isEmpty()) {
            UUID batchId = repository.insertImportBatch(new ImportBatchDraft("import-" + UUID.randomUUID(), actor,
                    importType, "FAILED", request.rows().size(), errors.size(), errorsJson, null, Instant.now()));
            audit.write(actor, "KNOWLEDGE_IMPORT_COMMIT", "IMPORT_BATCH", batchId, "REJECTED",
                    "IMPORT_VALIDATION_FAILED");
            return new ImportCommitResponse(batchId, 0, List.of(), List.of(), errors);
        }
        List<UUID> knowledgeIds = new ArrayList<>();
        List<UUID> createdIds = new ArrayList<>();
        if ("ATTRACTION_KNOWLEDGE".equals(importType)) {
            for (ImportRow row : request.rows()) {
                PoiRow poi = resolveImportPoi(row);
                UUID cityId = requireCity(row.city());
                String entryKey = blank(row.entryKey()) ? "kb-" + UUID.randomUUID() : row.entryKey();
                List<ChunkDraft> chunks = new ArrayList<>();
                for (int index = 0; index < row.chunks().size(); index++) {
                    chunks.add(new ChunkDraft(index + 1, row.chunks().get(index), null));
                }
                KnowledgeRow created = repository.insertEntry(new KnowledgeDraft(entryKey, poi.poiId(), cityId,
                        null, repository.nextEntryVersion(entryKey), row.title(), row.factType(),
                        json(row.factValue()), row.effectiveFrom(), row.effectiveUntil(), row.lastVerifiedAt(),
                        row.sourceSnapshotId(), actor, chunks));
                knowledgeIds.add(created.knowledgeId());
                createdIds.add(created.knowledgeId());
            }
        } else if ("POI".equals(importType)) {
            for (ImportRow row : request.rows()) {
                String externalPoiId = blank(row.externalPoiId())
                        ? "import-" + UUID.randomUUID()
                        : row.externalPoiId();
                PoiView created = createPoi(actor, new PoiDraftRequest(row.city(), externalPoiId, row.poiName(),
                        row.poiCategory(), row.district(), row.longitude(), row.latitude(),
                        row.coordinateSystem(), row.sourceSnapshotId(), null, row.aliases()));
                createdIds.add(created.poiId());
            }
        } else if ("RESERVATION_RULE".equals(importType)) {
            for (ImportRow row : request.rows()) {
                PoiRow poi = resolveImportPoi(row);
                if (poi == null) {
                    throw badRequest("POI_NOT_FOUND", "无法唯一确定 POI");
                }
                ReservationRuleView created = createRule(actor, new ReservationRuleDraftRequest(poi.poiId(),
                        row.ruleKey(), row.ticketType(), row.audience(), row.channel(), row.reservationRequired(),
                        row.releaseTime(), row.releaseTimezone(), row.advanceDays(), row.currency(),
                        row.amountMinor(), row.freeCondition(), row.effectiveFrom(), row.effectiveUntil(),
                        row.holidayException(), row.lastVerifiedAt(), row.staleAfterDays(),
                        row.sourceSnapshotId()));
                createdIds.add(created.policyId());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledge_ids", knowledgeIds);
        result.put("created_ids", createdIds);
        UUID batchId = repository.insertImportBatch(new ImportBatchDraft("import-" + UUID.randomUUID(), actor,
                importType, "COMMITTED", request.rows().size(), 0, null, json(result), Instant.now()));
        audit.write(actor, "KNOWLEDGE_IMPORT_COMMIT", "IMPORT_BATCH", batchId, "SUCCESS", null);
        return new ImportCommitResponse(batchId, createdIds.size(), knowledgeIds, createdIds, List.of());
    }

    private String requireImportType(String importType) {
        if (!IMPORT_TYPES.contains(importType)) {
            throw badRequest("IMPORT_TYPE_INVALID", "导入类型不受支持");
        }
        return importType;
    }

    private List<ImportError> validateRows(String importType, List<ImportRow> rows) {
        List<ImportError> errors = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            ImportRow row = rows.get(index);
            int rowNo = row.rowNo() == null ? index + 1 : row.rowNo();
            if (blank(row.city())) {
                errors.add(new ImportError(rowNo, "CITY_REQUIRED", "城市不能为空"));
            } else if (repository.findCityId(row.city()) == null) {
                errors.add(new ImportError(rowNo, "CITY_NOT_FOUND", "城市不存在"));
            }
            if ("ATTRACTION_KNOWLEDGE".equals(importType)) {
                if (blank(row.title())) {
                    errors.add(new ImportError(rowNo, "TITLE_REQUIRED", "标题不能为空"));
                }
                if (row.factType() == null || !FACT_TYPES.contains(row.factType())) {
                    errors.add(new ImportError(rowNo, "FACT_TYPE_INVALID", "事实类型不合法"));
                }
                if (row.factValue() == null) {
                    errors.add(new ImportError(rowNo, "FACT_VALUE_REQUIRED", "事实内容不能为空"));
                }
                if (row.lastVerifiedAt() == null) {
                    errors.add(new ImportError(rowNo, "LAST_VERIFIED_REQUIRED", "核验时间不能为空"));
                }
                if (row.sourceSnapshotId() == null
                        || repository.findSourceSnapshot(row.sourceSnapshotId()) == null) {
                    errors.add(new ImportError(rowNo, "SOURCE_NOT_FOUND", "来源快照不存在"));
                }
                if (row.chunks() == null || row.chunks().isEmpty()) {
                    errors.add(new ImportError(rowNo, "CHUNKS_REQUIRED", "至少需要一个知识 chunk"));
                }
                if (row.effectiveFrom() != null && row.effectiveUntil() != null
                        && row.effectiveUntil().isBefore(row.effectiveFrom())) {
                    errors.add(new ImportError(rowNo, "INVALID_VALIDITY", "生效结束时间早于开始时间"));
                }
                PoiRow poi = resolveImportPoi(row);
                if (poi == null) {
                    errors.add(new ImportError(rowNo, "POI_NOT_FOUND", "无法唯一确定 POI"));
                } else {
                    UUID cityId = repository.findCityId(row.city());
                    if (cityId != null && !cityId.equals(poi.cityId())) {
                        errors.add(new ImportError(rowNo, "CITY_MISMATCH", "POI 与导入行城市不一致"));
                    }
                }
            } else if ("POI".equals(importType)) {
                if (blank(row.poiName())) {
                    errors.add(new ImportError(rowNo, "POI_NAME_REQUIRED", "POI 名称不能为空"));
                }
                if (row.longitude() == null || row.longitude() < -180 || row.longitude() > 180) {
                    errors.add(new ImportError(rowNo, "LONGITUDE_INVALID", "经度必须在 -180 到 180 之间"));
                }
                if (row.latitude() == null || row.latitude() < -90 || row.latitude() > 90) {
                    errors.add(new ImportError(rowNo, "LATITUDE_INVALID", "纬度必须在 -90 到 90 之间"));
                }
                if (row.sourceSnapshotId() == null
                        || repository.findSourceSnapshot(row.sourceSnapshotId()) == null) {
                    errors.add(new ImportError(rowNo, "SOURCE_NOT_FOUND", "来源快照不存在"));
                }
                if (row.aliases() != null
                        && row.aliases().stream().anyMatch(alias -> alias == null || alias.isBlank())) {
                    errors.add(new ImportError(rowNo, "ALIAS_INVALID", "别名不能为空"));
                }
            } else if ("RESERVATION_RULE".equals(importType)) {
                if (blank(row.ticketType())) {
                    errors.add(new ImportError(rowNo, "TICKET_TYPE_REQUIRED", "票种不能为空"));
                }
                if (row.effectiveFrom() == null) {
                    errors.add(new ImportError(rowNo, "EFFECTIVE_FROM_REQUIRED", "生效开始时间不能为空"));
                }
                if (row.lastVerifiedAt() == null) {
                    errors.add(new ImportError(rowNo, "LAST_VERIFIED_REQUIRED", "核验时间不能为空"));
                }
                if (blank(row.releaseTimezone())) {
                    errors.add(new ImportError(rowNo, "TIMEZONE_REQUIRED", "放票时区不能为空"));
                }
                if (row.effectiveFrom() != null && row.effectiveUntil() != null
                        && row.effectiveUntil().isBefore(row.effectiveFrom())) {
                    errors.add(new ImportError(rowNo, "INVALID_VALIDITY", "生效结束时间早于开始时间"));
                }
                if ((row.currency() == null) != (row.amountMinor() == null)) {
                    errors.add(new ImportError(rowNo, "PRICE_INCOMPLETE", "币种和价格必须同时提供或同时为空"));
                }
                if (!blank(row.releaseTime())) {
                    try {
                        LocalTime.parse(row.releaseTime());
                    } catch (RuntimeException exception) {
                        errors.add(new ImportError(rowNo, "RELEASE_TIME_INVALID", "放票时间格式不合法"));
                    }
                }
                if (row.staleAfterDays() != null && row.staleAfterDays() <= 0) {
                    errors.add(new ImportError(rowNo, "STALE_WINDOW_INVALID", "过期天数必须大于 0"));
                }
                if (row.sourceSnapshotId() == null
                        || repository.findSourceSnapshot(row.sourceSnapshotId()) == null) {
                    errors.add(new ImportError(rowNo, "SOURCE_NOT_FOUND", "来源快照不存在"));
                }
                PoiRow poi = resolveImportPoi(row);
                if (poi == null) {
                    errors.add(new ImportError(rowNo, "POI_NOT_FOUND", "无法唯一确定 POI"));
                } else {
                    UUID cityId = repository.findCityId(row.city());
                    if (cityId != null && !cityId.equals(poi.cityId())) {
                        errors.add(new ImportError(rowNo, "CITY_MISMATCH", "POI 与导入行城市不一致"));
                    }
                }
            }
        }
        return errors;
    }

    private PoiRow resolveImportPoi(ImportRow row) {
        if (row.poiId() != null) {
            return repository.findPoi(row.poiId());
        }
        if (blank(row.city()) || blank(row.poiName())) {
            return null;
        }
        UUID cityId = repository.findCityId(row.city());
        if (cityId == null) {
            return null;
        }
        List<PoiRow> matches = repository.findPoisByName(cityId, row.poiName());
        return matches.size() == 1 ? matches.get(0) : null;
    }

    // ---------------------------------------------------------------- queries

    /**
     * Internal POI-by-ID lookup for the Python route planner (contracts/s12/poi-lookup.md).
     *
     * <p>Only an existing POI belonging to a registered city, backed by a current POI snapshot
     * with coordinates and a source snapshot, is authoritative. Missing/unowned records map to
     * 404 so cross-city existence is not disclosed. Category stays the literal {@code unknown}
     * when the source has none; {@code data_at} is the snapshot time and is never replaced with
     * the response time.
     */
    @Transactional(readOnly = true)
    public PoiLookupResponse lookupPoi(String poiId, String city) {
        UUID id = parsePoiId(poiId);
        PoiRow poi = repository.findPoi(id);
        if (poi == null || blank(poi.cityCode())) {
            throw poiLookupNotFound();
        }
        if (!blank(city)) {
            UUID cityId = repository.findCityId(city);
            if (cityId == null || !cityId.equals(poi.cityId())) {
                throw poiLookupNotFound();
            }
        }
        if ("DISABLED".equals(poi.status()) || poi.currentSnapshotId() == null
                || poi.latitude() == null || poi.longitude() == null
                || blank(poi.coordinateSystem())) {
            throw poiLookupNotFound();
        }
        PoiSnapshotRow snapshot = repository.findPoiSnapshot(poi.currentSnapshotId());
        if (snapshot == null || snapshot.sourceSnapshotId() == null) {
            throw poiLookupNotFound();
        }
        SourceRow source = repository.findSourceSnapshot(snapshot.sourceSnapshotId());
        if (source == null) {
            throw poiLookupNotFound();
        }
        Instant now = Instant.now();
        String freshness = source.validUntil() != null && !now.isBefore(source.validUntil())
                ? "stale" : normalizeFreshness(source.freshnessStatus());
        SourceMetadata metadata = new SourceMetadata(
                source.sourceType().toLowerCase(Locale.ROOT), source.sourceRecordId(),
                source.retrievedAt(), source.validUntil(), freshness, false);
        String category = blank(poi.category()) ? "unknown" : poi.category();
        return new PoiLookupResponse(poi.poiId(), poi.cityCode(), poi.name(), category,
                poi.latitude(), poi.longitude(), poi.coordinateSystem(), snapshot.createdAt(),
                List.of(metadata), now);
    }

    private UUID parsePoiId(String poiId) {
        try {
            return UUID.fromString(poiId);
        } catch (RuntimeException exception) {
            throw badRequest("POI_ID_INVALID", "POI ID 不合法");
        }
    }

    private static String normalizeFreshness(String value) {
        if (blank(value)) {
            return "unknown";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return Set.of("fresh", "stale", "unknown").contains(lower) ? lower : "unknown";
    }

    private static ApiException poiLookupNotFound() {
        return notFound("POI_NOT_FOUND", "POI 不存在或缺少权威坐标");
    }

    public AttractionQueryResponse queryAttractions(AttractionQueryRequest request) {
        Instant now = Instant.now();
        int limit = request.limit() == null ? 10 : request.limit();
        List<AttractionCard> cards = new ArrayList<>();
        List<SourceMetadata> metadata = new ArrayList<>();
        Set<UUID> sourceIds = new LinkedHashSet<>();
        UUID cityId = repository.findCityId(request.city());
        if (cityId != null) {
            for (KnowledgeRow row : repository.findEntriesByCity(request.city())) {
                if (cards.size() >= limit || !row.status().retrievable() || !cityId.equals(row.cityId())) {
                    continue;
                }
                if (!inWindow(row.effectiveFrom(), row.effectiveUntil(), now)) {
                    continue;
                }
                QueryPoint point = matchPoint(request.points(), row.poiId(), row.poiName());
                SourceRow source = repository.findSourceSnapshot(row.sourceSnapshotId());
                List<ChunkRow> chunks = repository.findChunks(row.knowledgeId());
                if (point == null || source == null || sourceExpired(source, now) || chunks.isEmpty()) {
                    continue;
                }
                SourceMetadata sourceMetadata = sourceMetadata(source);
                if (sourceIds.add(source.sourceSnapshotId())) {
                    metadata.add(sourceMetadata);
                }
                cards.add(new AttractionCard(point.pointId(), row.poiId(), row.knowledgeId(),
                        chunks.stream().map(ChunkRow::chunkId).toList(), row.title(), row.factType(),
                        jsonValue(row.factValueJson()), source.retrievedAt(), source.validUntil(),
                        source.freshnessStatus(), source.providerName(), sourceMetadata));
            }
        }
        return new AttractionQueryResponse(request.city(), cards, metadata, now);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportPublishedCatalog(String city) {
        requireCity(city);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("catalog_version", "java-" + Instant.now().toEpochMilli());
        payload.put("generated_at", Instant.now().toString());
        List<Map<String, Object>> pois = new ArrayList<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        List<Map<String, Object>> rules = new ArrayList<>();
        Set<UUID> seenPois = new LinkedHashSet<>();
        for (KnowledgeRow row : repository.findEntriesByCity(city)) {
            if (!row.status().retrievable()) {
                continue;
            }
            SourceRow source = repository.findSourceSnapshot(row.sourceSnapshotId());
            PoiRow poi = repository.findPoi(row.poiId());
            if (source == null || poi == null) {
                continue;
            }
            if (seenPois.add(poi.poiId())) {
                pois.add(poiMap(poi, city));
            }
            List<Map<String, Object>> chunks = new ArrayList<>();
            for (ChunkRow chunk : repository.findChunks(row.knowledgeId())) {
                Map<String, Object> chunkMap = new LinkedHashMap<>();
                chunkMap.put("chunk_id", chunk.chunkId().toString());
                chunkMap.put("fact_type", row.factType());
                chunkMap.put("text", chunk.contentText());
                chunks.add(chunkMap);
            }
            if (chunks.isEmpty()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("knowledge_id", row.knowledgeId().toString());
            entry.put("poi_id", row.poiId().toString());
            entry.put("city_id", city);
            entry.put("title", row.title());
            entry.put("status", row.status().name());
            entry.put("fact_type", row.factType());
            entry.put("effective_from", instantString(row.effectiveFrom()));
            entry.put("effective_until", instantString(row.effectiveUntil()));
            entry.put("last_verified_at", instantString(row.lastVerifiedAt()));
            entry.put("source", sourceMap(source));
            entry.put("chunks", chunks);
            entries.add(entry);
        }
        for (RuleRow row : repository.findRulesByCity(city)) {
            if (!row.status().retrievable()) {
                continue;
            }
            SourceRow source = repository.findSourceSnapshot(row.sourceSnapshotId());
            PoiRow poi = repository.findPoi(row.poiId());
            if (source == null || poi == null) {
                continue;
            }
            if (seenPois.add(poi.poiId())) {
                pois.add(poiMap(poi, city));
            }
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("policy_id", row.policyId().toString());
            rule.put("poi_id", row.poiId().toString());
            rule.put("city_id", city);
            rule.put("status", row.status().name());
            rule.put("ticket_type", row.ticketType());
            rule.put("audience", row.audience());
            rule.put("reservation_required", row.reservationRequired());
            rule.put("channel", row.channel());
            rule.put("release_time", row.releaseTime() == null ? null : row.releaseTime().toString());
            rule.put("release_timezone", row.releaseTimezone());
            rule.put("advance_days", row.advanceDays());
            rule.put("currency", row.currency());
            rule.put("amount_minor", row.amountMinor());
            rule.put("free_condition", row.freeCondition());
            rule.put("effective_from", instantString(row.effectiveFrom()));
            rule.put("effective_until", instantString(row.effectiveUntil()));
            rule.put("holiday_exception", row.holidayException());
            rule.put("last_verified_at", instantString(row.lastVerifiedAt()));
            rule.put("stale_after_days", row.staleAfterDays());
            rule.put("version", row.versionNo());
            rule.put("source", sourceMap(source));
            rules.add(rule);
        }
        payload.put("pois", pois);
        payload.put("entries", entries);
        payload.put("rules", rules);
        return payload;
    }

    private Map<String, Object> poiMap(PoiRow poi, String city) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("poi_id", poi.poiId().toString());
        value.put("city_id", city);
        value.put("name", poi.name());
        value.put("aliases", repository.findAliases(poi.poiId()));
        value.put("district", poi.district());
        value.put("category", poi.category());
        value.put("latitude", poi.latitude());
        value.put("longitude", poi.longitude());
        value.put("coordinate_system", poi.coordinateSystem());
        return value;
    }

    private Map<String, Object> sourceMap(SourceRow source) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source_type", source.sourceType().toLowerCase(Locale.ROOT));
        value.put("source_record_id", source.sourceRecordId().toString());
        value.put("retrieved_at", instantString(source.retrievedAt()));
        value.put("valid_until", instantString(source.validUntil()));
        value.put("freshness_status", source.freshnessStatus());
        return value;
    }

    private String instantString(Instant value) {
        return value == null ? null : value.toString();
    }

    public ReservationQueryResponse queryReservations(ReservationQueryRequest request) {
        Instant now = Instant.now();
        int limit = request.limit() == null ? 10 : request.limit();
        List<ReservationItem> items = new ArrayList<>();
        List<SourceMetadata> metadata = new ArrayList<>();
        Set<UUID> sourceIds = new LinkedHashSet<>();
        LocalDate date = request.date();
        UUID cityId = repository.findCityId(request.city());
        if (cityId != null) {
            for (RuleRow row : repository.findRulesByCity(request.city())) {
                if (items.size() >= limit || !row.status().retrievable()
                        || !effectiveOn(row.effectiveFrom(), row.effectiveUntil(), date)) {
                    continue;
                }
                QueryPoint point = matchPoint(request.points(), row.poiId(), row.poiName());
                SourceRow source = repository.findSourceSnapshot(row.sourceSnapshotId());
                if (point == null || source == null || sourceExpired(source, now)) {
                    continue;
                }
                if (sourceIds.add(source.sourceSnapshotId())) {
                    metadata.add(sourceMetadata(source));
                }
                boolean stale = row.lastVerifiedAt() != null && row.lastVerifiedAt()
                        .plus(Duration.ofDays(row.staleAfterDays())).isBefore(now);
                items.add(new ReservationItem(point.pointId(), row.policyId(), row.ticketType(), row.currency(),
                        row.amountMinor(), row.effectiveFrom(), row.effectiveUntil(), row.releaseTimezone(),
                        row.lastVerifiedAt(), stale));
            }
        }
        return new ReservationQueryResponse(request.city(), date, items, metadata, now);
    }

    private QueryPoint matchPoint(List<QueryPoint> points, UUID poiId, String name) {
        for (QueryPoint point : points) {
            if (poiId != null && poiId.equals(point.poiId())) {
                return point;
            }
            if (!blank(point.name()) && point.name().equals(name)) {
                return point;
            }
        }
        return null;
    }

    private boolean inWindow(Instant from, Instant until, Instant now) {
        return (from == null || !from.isAfter(now)) && (until == null || until.isAfter(now));
    }

    private boolean effectiveOn(Instant from, Instant until, LocalDate date) {
        LocalDate start = from == null ? null : from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate end = until == null ? null : until.atZone(ZoneOffset.UTC).toLocalDate();
        return (start == null || !start.isAfter(date)) && (end == null || !end.isBefore(date));
    }

    private boolean sourceExpired(SourceRow source, Instant now) {
        return source.validUntil() != null && !source.validUntil().isAfter(now);
    }

    private SourceMetadata sourceMetadata(SourceRow source) {
        return new SourceMetadata(source.sourceType(), source.sourceRecordId(), source.retrievedAt(),
                source.validUntil(), source.freshnessStatus(), false);
    }

    private SourceView sourceView(SourceRow row) {
        if (row == null) {
            return null;
        }
        return new SourceView(row.sourceRecordId(), row.sourceSnapshotId(), row.sourceType(), row.providerName(),
                row.externalRecordId(), row.sourceUrl(), row.licenseType(), row.licenseReference(),
                row.permittedUse(), row.attributionRequired(), row.retrievedAt(), row.validUntil(),
                row.freshnessStatus(), row.sourceStatus());
    }

    // ---------------------------------------------------------------- helpers

    private UUID requireCity(String city) {
        UUID cityId = repository.findCityId(city);
        if (cityId == null) {
            throw notFound("CITY_NOT_FOUND", "城市不存在");
        }
        return cityId;
    }

    private PoiRow requirePoi(UUID poiId) {
        PoiRow poi = repository.findPoi(poiId);
        if (poi == null) {
            throw notFound("POI_NOT_FOUND", "POI 不存在");
        }
        return poi;
    }

    private KnowledgeRow requireEntry(UUID entryId) {
        KnowledgeRow row = repository.findEntry(entryId);
        if (row == null) {
            throw notFound("KNOWLEDGE_NOT_FOUND", "知识条目不存在");
        }
        return row;
    }

    private RuleRow requireRule(UUID ruleId) {
        RuleRow row = repository.findRule(ruleId);
        if (row == null) {
            throw notFound("RULE_NOT_FOUND", "预约规则不存在");
        }
        return row;
    }

    private void requireExpectedVersion(int actual, Integer expected) {
        if (expected == null || expected != actual) {
            throw conflict("CONCURRENCY_CONFLICT", "版本号不匹配，请刷新后重试");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw badRequest("INVALID_JSON", "内容无法序列化为 JSON");
        }
    }

    private Object jsonValue(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException exception) {
            return value;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
