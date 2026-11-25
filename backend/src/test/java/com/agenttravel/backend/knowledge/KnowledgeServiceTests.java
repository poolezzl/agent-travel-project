package com.agenttravel.backend.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import com.agenttravel.backend.common.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class KnowledgeServiceTests {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-02-10T00:00:00Z");

    private FakeKnowledgeRepository repository;
    private List<String> auditActions;
    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        repository = new FakeKnowledgeRepository();
        auditActions = new ArrayList<>();
        AuditRepository audit = new AuditRepository(null) {
            @Override
            public void write(UUID actor, String action, String resourceType, UUID resourceId, String outcome,
                    String code) {
                auditActions.add(action + ":" + outcome);
            }
        };
        service = new KnowledgeService(repository, audit, new ObjectMapper());
        repository.addCity("北京", "BJ");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void duplicatePoiNameCreatesConflictWithoutAutoMerge() {
        UUID sourceId = source();
        KnowledgeModels.PoiView first = service.createPoi(ADMIN, poiRequest("故宫", 116.397, 39.918, sourceId));
        KnowledgeModels.PoiView second = service.createPoi(ADMIN, poiRequest("故宫", 116.500, 39.700, sourceId));

        assertThat(repository.pois).hasSize(2);
        assertThat(second.conflictIds()).isNotEmpty();
        List<KnowledgeModels.PoiConflictView> conflicts = service.listPoiConflicts();
        assertThat(conflicts).anySatisfy(conflict -> {
            assertThat(conflict.conflictType()).isEqualTo("NAME");
            assertThat(conflict.candidatePoiId()).isEqualTo(second.poiId());
            assertThat(conflict.existingPoiId()).isEqualTo(first.poiId());
            assertThat(conflict.status()).isEqualTo("OPEN");
        });
        assertThat(repository.pois.get(first.poiId()).status()).isEqualTo("DRAFT");
        assertThat(repository.pois.get(second.poiId()).status()).isEqualTo("DRAFT");
    }

    @Test
    void mergeConflictIsManualAuditedAndKeepsBothPois() {
        UUID sourceId = source();
        KnowledgeModels.PoiView first = service.createPoi(ADMIN, poiRequest("故宫", 116.397, 39.918, sourceId));
        KnowledgeModels.PoiView second = service.createPoi(ADMIN, poiRequest("故宫", 116.500, 39.700, sourceId));

        KnowledgeModels.PoiConflictView resolved = service.resolvePoiConflict(ADMIN, second.conflictIds().get(0),
                new KnowledgeModels.PoiConflictResolveRequest("merge"));

        assertThat(resolved.status()).isEqualTo("MERGED");
        assertThat(repository.pois.get(first.poiId()).mergeGroupId())
                .isNotNull()
                .isEqualTo(repository.pois.get(second.poiId()).mergeGroupId());
        assertThat(service.listPoiConflicts()).isEmpty();
        assertThat(auditActions).contains("KNOWLEDGE_POI_MERGE:SUCCESS");
    }

    @Test
    void rejectConflictKeepsPoisSeparateAndAudits() {
        UUID sourceId = source();
        KnowledgeModels.PoiView first = service.createPoi(ADMIN, poiRequest("故宫", 116.397, 39.918, sourceId));
        KnowledgeModels.PoiView second = service.createPoi(ADMIN, poiRequest("故宫", 116.500, 39.700, sourceId));

        KnowledgeModels.PoiConflictView resolved = service.resolvePoiConflict(ADMIN, second.conflictIds().get(0),
                new KnowledgeModels.PoiConflictResolveRequest("REJECT"));

        assertThat(resolved.status()).isEqualTo("REJECTED");
        assertThat(repository.pois.get(first.poiId()).mergeGroupId()).isNull();
        assertThat(repository.pois.get(second.poiId()).mergeGroupId()).isNull();
        assertThat(service.listPoiConflicts()).isEmpty();
        assertThat(auditActions).contains("KNOWLEDGE_POI_CONFLICT_REJECT:SUCCESS");
    }

    @Test
    void invalidConflictDecisionIsRejected() {
        UUID sourceId = source();
        service.createPoi(ADMIN, poiRequest("故宫", 116.397, 39.918, sourceId));
        KnowledgeModels.PoiView second = service.createPoi(ADMIN, poiRequest("故宫", 116.500, 39.700, sourceId));

        assertThrows(ApiException.class, () -> service.resolvePoiConflict(ADMIN, second.conflictIds().get(0),
                new KnowledgeModels.PoiConflictResolveRequest("SPLIT")));
    }

    @Test
    void aliasConflictIsRecordedAndDoesNotMergePois() {
        UUID sourceId = source();
        KnowledgeModels.PoiView first = service.createPoi(ADMIN,
                new KnowledgeModels.PoiDraftRequest("北京", "ext-1", "故宫", "MUSEUM", "东城", 116.397,
                        39.918, "WGS84", sourceId, null, List.of("紫禁城")));
        KnowledgeModels.PoiView second = service.createPoi(ADMIN, poiRequest("景山公园", 116.400, 39.930, sourceId));

        KnowledgeModels.AliasView alias = service.addAlias(ADMIN, second.poiId(),
                new KnowledgeModels.AliasRequest("紫禁城", "MANUAL"));

        assertThat(alias.status()).isEqualTo("ACTIVE");
        assertThat(alias.conflictIds()).isNotEmpty();
        assertThat(repository.pois).hasSize(2);
        assertThat(service.listPoiConflicts())
                .anySatisfy(conflict -> assertThat(conflict.conflictType()).isEqualTo("ALIAS"));
        assertThat(repository.findAliases(first.poiId())).contains("紫禁城");
    }

    @Test
    void attractionQueryReturnsEmptyForUnknownCity() {
        KnowledgeModels.AttractionQueryResponse response = service.queryAttractions(
                new KnowledgeModels.AttractionQueryRequest("不存在", List.of(point(null, "故宫")), List.of(), 10));

        assertThat(response.cards()).isEmpty();
        assertThat(response.sourceMetadata()).isEmpty();
    }

    @Test
    void attractionQueryExcludesExpiredKnowledge() {
        UUID sourceId = source();
        UUID poiId = createPoi(sourceId);
        publishedEntry(poiId, sourceId, Instant.now().minus(Duration.ofDays(2)),
                Instant.now().minus(Duration.ofHours(1)));

        KnowledgeModels.AttractionQueryResponse response = service.queryAttractions(attractionQuery(poiId));

        assertThat(response.cards()).isEmpty();
    }

    @Test
    void attractionQueryExcludesNonPublishedKnowledge() {
        UUID sourceId = source();
        UUID poiId = createPoi(sourceId);
        service.createEntry(ADMIN, entryRequest(poiId, sourceId, null, null));

        KnowledgeModels.AttractionQueryResponse response = service.queryAttractions(attractionQuery(poiId));
        assertThat(response.cards()).isEmpty();

        publishedEntry(poiId, sourceId, Instant.now().minus(Duration.ofDays(1)), null);
        assertThat(service.queryAttractions(attractionQuery(poiId)).cards()).hasSize(1);
    }

    @Test
    void exportPublishedCatalogIncludesPoisEntriesChunksAndRules() {
        UUID sourceId = source();
        UUID poiId = createPoi(sourceId);
        publishedEntry(poiId, sourceId, Instant.now().minus(Duration.ofDays(1)), null);
        publishedRule(poiId, sourceId, Instant.now().minus(Duration.ofDays(1)), null,
                Instant.now().minus(Duration.ofHours(1)), 30, "CNY", 100L);

        Map<String, Object> catalog = service.exportPublishedCatalog("北京");

        assertThat(catalog.get("pois")).asList().hasSize(1);
        Map<String, Object> poi = (Map<String, Object>) ((List<?>) catalog.get("pois")).get(0);
        assertThat(poi).containsEntry("latitude", 39.918).containsEntry("longitude", 116.397);
        assertThat(catalog.get("entries")).asList().hasSize(1);
        assertThat(catalog.get("rules")).asList().hasSize(1);
        Map<String, Object> entry = (Map<String, Object>) ((List<?>) catalog.get("entries")).get(0);
        assertThat(entry).containsKeys("knowledge_id", "source", "chunks");
        assertThat((List<?>) entry.get("chunks")).hasSize(1);
    }

    @Test
    void invalidStateTransitionIsRejected() {
        UUID sourceId = source();
        UUID poiId = createPoi(sourceId);
        KnowledgeModels.KnowledgeView draft = service.createEntry(ADMIN, entryRequest(poiId, sourceId, null, null));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.publishEntry(ADMIN, draft.knowledgeId(), 1));

        assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.code()).isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void wrongExpectedVersionIsRejectedAsConcurrencyConflict() {
        UUID sourceId = source();
        UUID poiId = createPoi(sourceId);
        KnowledgeModels.KnowledgeView draft = service.createEntry(ADMIN, entryRequest(poiId, sourceId, null, null));
        service.submitEntry(ADMIN, draft.knowledgeId(), 1);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.publishEntry(ADMIN, draft.knowledgeId(), 99));

        assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.code()).isEqualTo("CONCURRENCY_CONFLICT");
    }

    @Test
    void staleRuleIsFlaggedAndMissingPriceStaysNull() {
        UUID sourceId = source();
        UUID poiId = createPoi(sourceId);
        UUID ruleId = publishedRule(poiId, sourceId, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-03-31T23:59:59Z"), Instant.now().minus(Duration.ofDays(40)), 30, null, null);

        KnowledgeModels.ReservationQueryResponse response = service.queryReservations(
                new KnowledgeModels.ReservationQueryRequest("北京", List.of(point(poiId, "故宫")),
                        LocalDate.parse("2026-02-15"), 10));

        assertThat(response.reservations()).hasSize(1);
        KnowledgeModels.ReservationItem item = response.reservations().get(0);
        assertThat(item.policyId()).isEqualTo(ruleId);
        assertThat(item.stale()).isTrue();
        assertThat(item.amountMinor()).isNull();
        assertThat(item.currency()).isNull();
    }

    @Test
    void crossMonthRuleIsIncludedButOutOfWindowRuleIsExcluded() {
        UUID sourceId = source();
        UUID poiId = createPoi(sourceId);
        publishedRule(poiId, sourceId, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-03-31T23:59:59Z"), Instant.now(), 30, "CNY", 6000L);

        KnowledgeModels.ReservationQueryResponse inside = service.queryReservations(
                new KnowledgeModels.ReservationQueryRequest("北京", List.of(point(poiId, "故宫")),
                        LocalDate.parse("2026-02-15"), 10));
        KnowledgeModels.ReservationQueryResponse outside = service.queryReservations(
                new KnowledgeModels.ReservationQueryRequest("北京", List.of(point(poiId, "故宫")),
                        LocalDate.parse("2026-05-01"), 10));

        assertThat(inside.reservations()).hasSize(1);
        assertThat(inside.reservations().get(0).amountMinor()).isEqualTo(6000L);
        assertThat(inside.reservations().get(0).stale()).isFalse();
        assertThat(outside.reservations()).isEmpty();
    }

    @Test
    void rollbackCreatesNewDraftVersionAndKeepsHistory() {
        UUID sourceId = source();
        UUID poiId = createPoi(sourceId);
        KnowledgeModels.KnowledgeView published = publishedEntry(poiId, sourceId,
                Instant.now().minus(Duration.ofDays(1)), null);

        KnowledgeModels.KnowledgeView rolledBack = service.rollbackEntry(ADMIN, published.knowledgeId(), 1);

        assertThat(rolledBack.status()).isEqualTo(ReviewStatus.DRAFT);
        assertThat(rolledBack.versionNo()).isEqualTo(2);
        assertThat(rolledBack.parentEntryId()).isEqualTo(published.knowledgeId());
        assertThat(rolledBack.chunks()).hasSize(1);
        KnowledgeRepository.KnowledgeRow history = repository.findEntry(published.knowledgeId());
        assertThat(history).isNotNull();
        assertThat(history.status()).isEqualTo(ReviewStatus.ROLLED_BACK);
        assertThat(service.entry(rolledBack.knowledgeId()).entryKey()).isEqualTo(published.entryKey());
    }

    @Test
    void adminControllerRejectsNonAdminRole() {
        KnowledgeAdminController controller = new KnowledgeAdminController(service);
        RequestContext.set("req-1", "trace-1", UUID.randomUUID(), "USER");

        ApiException exception = assertThrows(ApiException.class, controller::conflicts);

        assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exception.code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void internalQueryDoesNotTrustExternalRole() {
        RequestContext.set("req-1", "trace-1", UUID.randomUUID(), "USER");
        InternalKnowledgeController controller = new InternalKnowledgeController(service);

        KnowledgeModels.AttractionQueryResponse response = controller.queryAttractions(attractionQuery(null));

        assertThat(response.cards()).isEmpty();
    }

    @Test
    void importPreviewReportsValidationErrorsWithoutWriting() {
        KnowledgeModels.ImportPreviewResponse response = service.previewImport(ADMIN,
                new KnowledgeModels.ImportPreviewRequest("ATTRACTION_KNOWLEDGE", List.of(
                        new KnowledgeModels.ImportRow(1, "火星", null, null, null, null, null, null, null, null,
                                null, null, null))));

        assertThat(response.rowCount()).isEqualTo(1);
        assertThat(response.validCount()).isZero();
        assertThat(response.errorCount()).isEqualTo(response.errors().size());
        assertThat(response.errors()).extracting(KnowledgeModels.ImportError::code)
                .contains("CITY_NOT_FOUND", "TITLE_REQUIRED", "CHUNKS_REQUIRED");
        assertThat(repository.entries).isEmpty();
    }

    @Test
    void importCommitCreatesDraftOnly() {
        UUID sourceId = source();
        UUID poiId = createPoi(sourceId);
        KnowledgeModels.ImportPreviewResponse preview = service.previewImport(ADMIN,
                importRequest(poiId, sourceId, "import-1"));
        assertThat(preview.errorCount()).isZero();

        KnowledgeModels.ImportCommitResponse response = service.commitImport(ADMIN,
                importRequest(poiId, sourceId, "import-1"));

        assertThat(response.createdCount()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        KnowledgeRepository.KnowledgeRow created = repository.findEntry(response.knowledgeIds().get(0));
        assertThat(created.status()).isEqualTo(ReviewStatus.DRAFT);
        assertThat(created.versionNo()).isEqualTo(1);
        assertThat(repository.batches).anySatisfy(batch -> {
            assertThat(batch.status()).isEqualTo("COMMITTED");
            assertThat(batch.errorCount()).isZero();
        });
        assertThat(auditActions).contains("KNOWLEDGE_IMPORT_COMMIT:SUCCESS");
    }

    @Test
    void importPoiCreatesDraftPoiWithAlias() throws Exception {
        UUID sourceId = source();
        String json = """
                {"row_no":1,"city":"北京","poi_name":"导入景点","poi_category":"MUSEUM",
                 "district":"东城","longitude":116.4,"latitude":39.9,"coordinate_system":"WGS84",
                 "source_snapshot_id":"%s","aliases":["导入别名"]}
                """.formatted(sourceId);
        KnowledgeModels.ImportRow row = new ObjectMapper().findAndRegisterModules()
                .readValue(json, KnowledgeModels.ImportRow.class);
        KnowledgeModels.ImportPreviewRequest request = new KnowledgeModels.ImportPreviewRequest("POI",
                List.of(row));

        assertThat(service.previewImport(ADMIN, request).errorCount()).isZero();
        KnowledgeModels.ImportCommitResponse response = service.commitImport(ADMIN, request);

        assertThat(response.createdCount()).isEqualTo(1);
        assertThat(response.createdIds()).hasSize(1);
        UUID poiId = response.createdIds().get(0);
        assertThat(repository.pois.get(poiId).status()).isEqualTo("DRAFT");
        assertThat(repository.findAliases(poiId)).contains("导入别名");
        assertThat(auditActions).contains("KNOWLEDGE_POI_CREATE:SUCCESS");
    }

    @Test
    void importReservationRuleCreatesDraftRule() throws Exception {
        UUID sourceId = source();
        UUID poiId = createPoi(sourceId);
        String json = """
                {"row_no":1,"city":"北京","poi_id":"%s","ticket_type":"standard",
                 "release_time":"20:00","release_timezone":"Asia/Shanghai","advance_days":7,
                 "currency":"CNY","amount_minor":6000,
                 "effective_from":"2026-01-01T00:00:00Z","last_verified_at":"2026-10-01T00:00:00Z",
                 "stale_after_days":30,"source_snapshot_id":"%s"}
                """.formatted(poiId, sourceId);
        KnowledgeModels.ImportRow row = new ObjectMapper().findAndRegisterModules()
                .readValue(json, KnowledgeModels.ImportRow.class);
        KnowledgeModels.ImportPreviewRequest request = new KnowledgeModels.ImportPreviewRequest(
                "RESERVATION_RULE", List.of(row));

        assertThat(service.previewImport(ADMIN, request).errorCount()).isZero();
        KnowledgeModels.ImportCommitResponse response = service.commitImport(ADMIN, request);

        assertThat(response.createdCount()).isEqualTo(1);
        assertThat(response.createdIds()).hasSize(1);
        KnowledgeRepository.RuleRow created = repository.rules.get(response.createdIds().get(0));
        assertThat(created.status()).isEqualTo(ReviewStatus.DRAFT);
        assertThat(created.amountMinor()).isEqualTo(6000L);
        assertThat(auditActions).contains("RESERVATION_RULE_CREATE:SUCCESS");
    }

    // ---------------------------------------------------------------- helpers

    private UUID source() {
        return service.registerSource(ADMIN, new KnowledgeModels.SourceRegistrationRequest("KNOWLEDGE_BASE",
                "example", "record-" + UUID.randomUUID(), "https://example.test/knowledge", "CC-BY-4.0", null,
                "DISPLAY", true, Instant.now().plus(Duration.ofDays(365)), Map.of("note", "synthetic")))
                .sourceSnapshotId();
    }

    private KnowledgeModels.PoiDraftRequest poiRequest(String name, double longitude, double latitude,
            UUID sourceId) {
        return new KnowledgeModels.PoiDraftRequest("北京", "ext-" + UUID.randomUUID(), name, "MUSEUM", "东城",
                longitude, latitude, "WGS84", sourceId, null, List.of());
    }

    private UUID createPoi(UUID sourceId) {
        return service.createPoi(ADMIN, poiRequest("故宫", 116.397, 39.918, sourceId)).poiId();
    }

    private KnowledgeModels.EntryDraftRequest entryRequest(UUID poiId, UUID sourceId, Instant effectiveUntil,
            String factType) {
        return new KnowledgeModels.EntryDraftRequest("entry-" + UUID.randomUUID(), null, poiId, "北京", "故宫开放信息",
                factType == null ? "opening_hours" : factType, Map.of("open", "08:30-17:00"),
                Instant.now().minus(Duration.ofDays(1)), effectiveUntil, Instant.now().minus(Duration.ofHours(1)),
                sourceId, List.of(new KnowledgeModels.ChunkRequest("故宫 08:30 开放", null)));
    }

    private KnowledgeModels.KnowledgeView publishedEntry(UUID poiId, UUID sourceId, Instant effectiveFrom,
            Instant effectiveUntil) {
        KnowledgeModels.EntryDraftRequest request = new KnowledgeModels.EntryDraftRequest(
                "entry-" + UUID.randomUUID(), null, poiId, "北京", "故宫开放信息", "opening_hours",
                Map.of("open", "08:30-17:00"), effectiveFrom, effectiveUntil,
                Instant.now().minus(Duration.ofHours(1)), sourceId,
                List.of(new KnowledgeModels.ChunkRequest("故宫 08:30 开放", null)));
        KnowledgeModels.KnowledgeView draft = service.createEntry(ADMIN, request);
        service.submitEntry(ADMIN, draft.knowledgeId(), draft.versionNo());
        return service.publishEntry(ADMIN, draft.knowledgeId(), draft.versionNo());
    }

    private UUID publishedRule(UUID poiId, UUID sourceId, Instant from, Instant until, Instant lastVerified,
            int staleAfterDays, String currency, Long amountMinor) {
        KnowledgeModels.ReservationRuleDraftRequest request = new KnowledgeModels.ReservationRuleDraftRequest(
                poiId, "rule-" + UUID.randomUUID(), "ADULT", "ALL", "ONLINE", true, "20:00", "Asia/Shanghai",
                7, currency, amountMinor, null, from, until, null, lastVerified, staleAfterDays, sourceId);
        KnowledgeModels.ReservationRuleView draft = service.createRule(ADMIN, request);
        service.submitRule(ADMIN, draft.policyId(), draft.versionNo());
        return service.publishRule(ADMIN, draft.policyId(), draft.versionNo()).policyId();
    }

    private KnowledgeModels.AttractionQueryRequest attractionQuery(UUID poiId) {
        return new KnowledgeModels.AttractionQueryRequest("北京", List.of(point(poiId, "故宫")), List.of("历史"),
                10);
    }

    private KnowledgeModels.QueryPoint point(UUID poiId, String name) {
        return new KnowledgeModels.QueryPoint("point-1", poiId, name, 116.397, 39.918);
    }

    private KnowledgeModels.ImportPreviewRequest importRequest(UUID poiId, UUID sourceId, String entryKey) {
        return new KnowledgeModels.ImportPreviewRequest("ATTRACTION_KNOWLEDGE", List.of(
                new KnowledgeModels.ImportRow(1, "北京", poiId, "故宫", entryKey, "故宫亮点", "highlights",
                        Map.of("items", List.of("中轴线")), Instant.now().minus(Duration.ofDays(1)), null,
                        Instant.now().minus(Duration.ofHours(2)), sourceId, List.of("故宫位于北京中轴线"))));
    }

    /** In-memory repository: no MySQL, Flyway or Spring transaction is required. */
    static final class FakeKnowledgeRepository implements KnowledgeRepository {

        final Map<String, UUID> cities = new LinkedHashMap<>();
        final Map<UUID, String> cityCodes = new LinkedHashMap<>();
        final Map<UUID, String> cityNames = new LinkedHashMap<>();
        final Map<UUID, SourceRow> sources = new LinkedHashMap<>();
        final Map<UUID, PoiRow> pois = new LinkedHashMap<>();
        final Map<UUID, PoiRow> poiByExternal = new LinkedHashMap<>();
        final List<StoredAlias> aliases = new ArrayList<>();
        final Map<String, PoiConflictRow> conflicts = new LinkedHashMap<>();
        final Map<UUID, KnowledgeRow> entries = new LinkedHashMap<>();
        final Map<UUID, List<ChunkRow>> chunks = new LinkedHashMap<>();
        final Map<UUID, RuleRow> rules = new LinkedHashMap<>();
        final List<ImportBatchDraft> batches = new ArrayList<>();

        UUID addCity(String name, String code) {
            UUID id = UUID.randomUUID();
            cities.put(name, id);
            cities.put(code, id);
            cityCodes.put(id, code);
            cityNames.put(id, name);
            return id;
        }

        @Override
        public SourceRow registerSource(SourceRegistration registration) {
            UUID sourceRecordId = UUID.randomUUID();
            UUID snapshotId = UUID.randomUUID();
            SourceRow row = new SourceRow(sourceRecordId, snapshotId, registration.sourceType(),
                    registration.providerName(), registration.externalRecordId(), registration.sourceUrl(),
                    registration.licenseType(), registration.licenseReference(), registration.permittedUse(),
                    registration.attributionRequired(), registration.retrievedAt(), registration.validUntil(),
                    "FRESH", "ACTIVE");
            sources.put(snapshotId, row);
            return row;
        }

        @Override
        public SourceRow findSourceSnapshot(UUID sourceSnapshotId) {
            return sources.get(sourceSnapshotId);
        }

        @Override
        public UUID findCityId(String city) {
            return cities.get(city);
        }

        @Override
        public PoiRow findPoi(UUID poiId) {
            return pois.get(poiId);
        }

        @Override
        public List<PoiRow> findPoisByName(UUID cityId, String name) {
            return pois.values().stream()
                    .filter(poi -> poi.cityId().equals(cityId) && poi.name().equals(name)).toList();
        }

        @Override
        public List<PoiRow> findPoisNear(UUID cityId, double latitude, double longitude, double tolerance) {
            return pois.values().stream()
                    .filter(poi -> poi.cityId().equals(cityId) && poi.latitude() != null && poi.longitude() != null)
                    .filter(poi -> Math.abs(poi.latitude() - latitude) <= tolerance
                            && Math.abs(poi.longitude() - longitude) <= tolerance)
                    .toList();
        }

        @Override
        public List<PoiRow> findPoisByAlias(UUID cityId, String alias) {
            return aliases.stream()
                    .filter(item -> item.alias.equals(alias) && "ACTIVE".equals(item.status))
                    .map(item -> pois.get(item.poiId))
                    .filter(poi -> poi != null && poi.cityId().equals(cityId))
                    .toList();
        }

        @Override
        public List<String> findAliases(UUID poiId) {
            return aliases.stream()
                    .filter(item -> item.poiId.equals(poiId) && "ACTIVE".equals(item.status))
                    .map(item -> item.alias)
                    .toList();
        }

        @Override
        public PoiRow insertPoi(PoiDraft draft) {
            UUID poiId = UUID.randomUUID();
            PoiRow row = new PoiRow(poiId, draft.cityId(), cityCodes.get(draft.cityId()), draft.externalPoiId(),
                    draft.name(), draft.category(), "DRAFT", draft.district(), draft.mergeGroupId(),
                    draft.latitude(), draft.longitude(), draft.coordinateSystem(), draft.sourceSnapshotId());
            pois.put(poiId, row);
            poiByExternal.put(draft.cityId(), row);
            return row;
        }

        @Override
        public UUID insertAlias(UUID poiId, String alias, String aliasType) {
            UUID id = UUID.randomUUID();
            aliases.add(new StoredAlias(id, poiId, alias, aliasType, "ACTIVE"));
            return id;
        }

        @Override
        public UUID insertPoiConflict(PoiConflictDraft draft) {
            String key = draft.candidatePoiId() + "|" + draft.existingPoiId() + "|" + draft.conflictType();
            UUID id = UUID.randomUUID();
            PoiRow candidate = pois.get(draft.candidatePoiId());
            PoiRow existing = pois.get(draft.existingPoiId());
            PoiConflictRow row = new PoiConflictRow(id, cityCodes.get(draft.cityId()),
                    draft.candidatePoiId(), candidate == null ? null : candidate.name(), draft.existingPoiId(),
                    existing == null ? null : existing.name(), draft.conflictType(), "OPEN", Instant.now());
            conflicts.putIfAbsent(key, row);
            return conflicts.get(key).candidateId();
        }

        @Override
        public PoiConflictRow findPoiConflict(UUID conflictId) {
            return conflicts.values().stream()
                    .filter(row -> row.candidateId().equals(conflictId))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<PoiConflictRow> listOpenPoiConflicts() {
            return conflicts.values().stream()
                    .filter(row -> "OPEN".equals(row.status()))
                    .toList();
        }

        @Override
        public int resolvePoiConflict(UUID conflictId, String status, UUID resolvedBy, Instant at) {
            for (Map.Entry<String, PoiConflictRow> entry : conflicts.entrySet()) {
                PoiConflictRow row = entry.getValue();
                if (row.candidateId().equals(conflictId) && "OPEN".equals(row.status())) {
                    entry.setValue(new PoiConflictRow(row.candidateId(), row.cityCode(), row.candidatePoiId(),
                            row.candidateName(), row.existingPoiId(), row.existingName(), row.conflictType(),
                            status, row.createdAt()));
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int setPoiMergeGroup(UUID poiId, UUID mergeGroupId) {
            PoiRow row = pois.get(poiId);
            if (row == null) {
                return 0;
            }
            pois.put(poiId, new PoiRow(row.poiId(), row.cityId(), row.cityCode(), row.externalPoiId(),
                    row.name(), row.category(), row.status(), row.district(), mergeGroupId, row.latitude(),
                    row.longitude(), row.coordinateSystem(), row.currentSnapshotId()));
            return 1;
        }

        @Override
        public KnowledgeRow insertEntry(KnowledgeDraft draft) {
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            PoiRow poi = pois.get(draft.poiId());
            KnowledgeRow row = new KnowledgeRow(id, draft.entryKey(), draft.poiId(),
                    poi == null ? null : poi.name(), draft.cityId(), cityCodes.get(draft.cityId()),
                    draft.parentEntryId(), draft.versionNo(), draft.title(), draft.factType(),
                    draft.factValueJson(), ReviewStatus.DRAFT, draft.effectiveFrom(), draft.effectiveUntil(),
                    draft.lastVerifiedAt(), null, null, null, null, draft.sourceSnapshotId(), draft.createdBy(),
                    now, now);
            entries.put(id, row);
            List<ChunkRow> list = new ArrayList<>();
            for (ChunkDraft chunk : draft.chunks()) {
                list.add(new ChunkRow(UUID.randomUUID(), id, chunk.chunkNo(), chunk.text(), "hash",
                        chunk.vectorRecordId()));
            }
            chunks.put(id, list);
            return row;
        }

        @Override
        public int updateEntryDraft(UUID entryId, KnowledgeDraft draft) {
            KnowledgeRow row = entries.get(entryId);
            if (row == null || row.status() != ReviewStatus.DRAFT) {
                return 0;
            }
            entries.put(entryId, new KnowledgeRow(row.knowledgeId(), row.entryKey(), row.poiId(), row.poiName(),
                    row.cityId(), row.cityCode(), row.parentEntryId(), row.versionNo(), draft.title(),
                    draft.factType(), draft.factValueJson(), row.status(), draft.effectiveFrom(),
                    draft.effectiveUntil(), draft.lastVerifiedAt(), row.publishedAt(), row.reviewedAt(),
                    row.reviewedBy(), row.disabledAt(), draft.sourceSnapshotId(), row.createdBy(),
                    row.createdAt(), Instant.now()));
            List<ChunkRow> list = new ArrayList<>();
            for (ChunkDraft chunk : draft.chunks()) {
                list.add(new ChunkRow(UUID.randomUUID(), entryId, chunk.chunkNo(), chunk.text(), "hash",
                        chunk.vectorRecordId()));
            }
            chunks.put(entryId, list);
            return 1;
        }

        @Override
        public KnowledgeRow findEntry(UUID entryId) {
            return entries.get(entryId);
        }

        @Override
        public List<KnowledgeRow> findEntriesByCity(String city) {
            return entries.values().stream()
                    .filter(row -> city.equals(row.cityCode()) || city.equals(cityNames.get(row.cityId())))
                    .toList();
        }

        @Override
        public List<KnowledgeRow> findEntriesByKey(String entryKey) {
            return entries.values().stream().filter(row -> row.entryKey().equals(entryKey)).toList();
        }

        @Override
        public List<KnowledgeRow> findEntriesByPoi(UUID poiId) {
            return entries.values().stream().filter(row -> poiId.equals(row.poiId())).toList();
        }

        @Override
        public List<ChunkRow> findChunks(UUID entryId) {
            return chunks.getOrDefault(entryId, List.of());
        }

        @Override
        public int nextEntryVersion(String entryKey) {
            return entries.values().stream().filter(row -> row.entryKey().equals(entryKey))
                    .mapToInt(KnowledgeRow::versionNo).max().orElse(0) + 1;
        }

        @Override
        public int transitionEntry(UUID entryId, ReviewStatus expected, ReviewStatus next, Instant at,
                UUID reviewer) {
            KnowledgeRow row = entries.get(entryId);
            if (row == null || row.status() != expected) {
                return 0;
            }
            boolean published = next == ReviewStatus.PUBLISHED;
            boolean disabled = next == ReviewStatus.DISABLED || next == ReviewStatus.ROLLED_BACK;
            entries.put(entryId, new KnowledgeRow(row.knowledgeId(), row.entryKey(), row.poiId(), row.poiName(),
                    row.cityId(), row.cityCode(), row.parentEntryId(), row.versionNo(), row.title(),
                    row.factType(), row.factValueJson(), next, row.effectiveFrom(), row.effectiveUntil(),
                    row.lastVerifiedAt(), published ? at : row.publishedAt(), published ? at : row.reviewedAt(),
                    published ? reviewer : row.reviewedBy(), disabled ? at : row.disabledAt(),
                    row.sourceSnapshotId(), row.createdBy(), row.createdAt(), at));
            return 1;
        }

        @Override
        public RuleRow insertRule(RuleDraft draft) {
            UUID id = UUID.randomUUID();
            PoiRow poi = pois.get(draft.poiId());
            RuleRow row = new RuleRow(id, draft.ruleKey(), draft.poiId(), poi == null ? null : poi.name(),
                    poi == null ? null : cityCodes.get(poi.cityId()), draft.parentRuleId(), draft.versionNo(),
                    draft.ticketType(), draft.audience(), draft.channel(), draft.reservationRequired(),
                    draft.releaseTime(), draft.releaseTimezone(), draft.advanceDays(), draft.currency(),
                    draft.amountMinor(), draft.freeCondition(), draft.effectiveFrom(), draft.effectiveUntil(),
                    draft.holidayException(), draft.lastVerifiedAt(), draft.staleAfterDays(),
                    ReviewStatus.DRAFT, null, null, null, draft.sourceSnapshotId(), Instant.now());
            rules.put(id, row);
            return row;
        }

        @Override
        public int updateRuleDraft(UUID ruleId, RuleDraft draft) {
            RuleRow row = rules.get(ruleId);
            if (row == null || row.status() != ReviewStatus.DRAFT) {
                return 0;
            }
            rules.put(ruleId, new RuleRow(ruleId, row.ruleKey(), row.poiId(), row.poiName(), row.cityCode(),
                    row.parentRuleId(), row.versionNo(), draft.ticketType(), draft.audience(), draft.channel(),
                    draft.reservationRequired(), draft.releaseTime(), draft.releaseTimezone(),
                    draft.advanceDays(), draft.currency(), draft.amountMinor(), draft.freeCondition(),
                    draft.effectiveFrom(), draft.effectiveUntil(), draft.holidayException(),
                    draft.lastVerifiedAt(), draft.staleAfterDays(), row.status(), row.publishedAt(),
                    row.reviewedAt(), row.reviewedBy(), draft.sourceSnapshotId(), row.createdAt()));
            return 1;
        }

        @Override
        public RuleRow findRule(UUID ruleId) {
            return rules.get(ruleId);
        }

        @Override
        public List<RuleRow> findRulesByCity(String city) {
            return rules.values().stream()
                    .filter(row -> city.equals(row.cityCode()) || city.equals(cityName(row.poiId())))
                    .toList();
        }

        private String cityName(UUID poiId) {
            PoiRow poi = pois.get(poiId);
            return poi == null ? null : cityNames.get(poi.cityId());
        }

        @Override
        public List<RuleRow> findRulesByPoi(UUID poiId) {
            return rules.values().stream().filter(row -> poiId.equals(row.poiId())).toList();
        }

        @Override
        public List<RuleRow> findRulesByKey(String ruleKey) {
            return rules.values().stream().filter(row -> ruleKey.equals(row.ruleKey())).toList();
        }

        @Override
        public int nextRuleVersion(String ruleKey) {
            return rules.values().stream().filter(row -> row.ruleKey().equals(ruleKey))
                    .mapToInt(RuleRow::versionNo).max().orElse(0) + 1;
        }

        @Override
        public int transitionRule(UUID ruleId, ReviewStatus expected, ReviewStatus next, Instant at,
                UUID reviewer) {
            RuleRow row = rules.get(ruleId);
            if (row == null || row.status() != expected) {
                return 0;
            }
            boolean published = next == ReviewStatus.PUBLISHED;
            rules.put(ruleId, new RuleRow(row.policyId(), row.ruleKey(), row.poiId(), row.poiName(),
                    row.cityCode(), row.parentRuleId(), row.versionNo(), row.ticketType(), row.audience(),
                    row.channel(), row.reservationRequired(), row.releaseTime(), row.releaseTimezone(),
                    row.advanceDays(), row.currency(), row.amountMinor(), row.freeCondition(),
                    row.effectiveFrom(), row.effectiveUntil(), row.holidayException(), row.lastVerifiedAt(),
                    row.staleAfterDays(), next, published ? at : row.publishedAt(),
                    published ? at : row.reviewedAt(), published ? reviewer : row.reviewedBy(),
                    row.sourceSnapshotId(), row.createdAt()));
            return 1;
        }

        @Override
        public UUID insertImportBatch(ImportBatchDraft draft) {
            batches.add(draft);
            return UUID.randomUUID();
        }

        record StoredAlias(UUID id, UUID poiId, String alias, String aliasType, String status) {
        }
    }
}
