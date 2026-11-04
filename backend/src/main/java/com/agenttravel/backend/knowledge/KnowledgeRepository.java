package com.agenttravel.backend.knowledge;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence port for the S15 knowledge module.
 *
 * <p>Services depend on this interface and on {@code AuditRepository} only, so unit tests can use an
 * in-memory fake without MySQL, Flyway or Spring transactions. The JDBC implementation is the only
 * component that knows about table names.
 */
public interface KnowledgeRepository {

    // ---------------------------------------------------------------- sources

    SourceRow registerSource(SourceRegistration registration);

    SourceRow findSourceSnapshot(UUID sourceSnapshotId);

    // -------------------------------------------------------------------- POI

    UUID findCityId(String city);

    PoiRow findPoi(UUID poiId);

    List<PoiRow> findPoisByName(UUID cityId, String name);

    List<PoiRow> findPoisNear(UUID cityId, double latitude, double longitude, double tolerance);

    List<PoiRow> findPoisByAlias(UUID cityId, String alias);

    List<String> findAliases(UUID poiId);

    PoiRow insertPoi(PoiDraft draft);

    UUID insertAlias(UUID poiId, String alias, String aliasType);

    UUID insertPoiConflict(PoiConflictDraft draft);

    List<PoiConflictRow> listOpenPoiConflicts();

    // -------------------------------------------------------------- knowledge

    KnowledgeRow insertEntry(KnowledgeDraft draft);

    int updateEntryDraft(UUID entryId, KnowledgeDraft draft);

    KnowledgeRow findEntry(UUID entryId);

    List<KnowledgeRow> findEntriesByCity(String city);

    List<KnowledgeRow> findEntriesByKey(String entryKey);

    List<KnowledgeRow> findEntriesByPoi(UUID poiId);

    List<ChunkRow> findChunks(UUID entryId);

    int nextEntryVersion(String entryKey);

    int transitionEntry(UUID entryId, ReviewStatus expected, ReviewStatus next, Instant at, UUID reviewer);

    // -------------------------------------------------------- reservation rule

    RuleRow insertRule(RuleDraft draft);

    int updateRuleDraft(UUID ruleId, RuleDraft draft);

    RuleRow findRule(UUID ruleId);

    List<RuleRow> findRulesByCity(String city);

    List<RuleRow> findRulesByPoi(UUID poiId);

    List<RuleRow> findRulesByKey(String ruleKey);

    int nextRuleVersion(String ruleKey);

    int transitionRule(UUID ruleId, ReviewStatus expected, ReviewStatus next, Instant at, UUID reviewer);

    // ---------------------------------------------------------------- imports

    UUID insertImportBatch(ImportBatchDraft draft);

    // ------------------------------------------------------------ input rows

    record SourceRegistration(String sourceType, String providerName, String externalRecordId, String sourceUrl,
            String licenseType, String licenseReference, String permittedUse, boolean attributionRequired,
            Instant validUntil, Map<String, Object> summary, Map<String, Object> payload, Instant retrievedAt) {
    }

    record PoiDraft(UUID cityId, String externalPoiId, String name, String category, String district,
            double longitude, double latitude, String coordinateSystem, UUID mergeGroupId, UUID sourceSnapshotId) {
    }

    record PoiConflictDraft(UUID cityId, UUID candidatePoiId, UUID existingPoiId, String conflictType,
            Map<String, Object> details) {
    }

    record ChunkDraft(int chunkNo, String text, String vectorRecordId) {
    }

    record KnowledgeDraft(String entryKey, UUID poiId, UUID cityId, UUID parentEntryId, int versionNo,
            String title, String factType, String factValueJson, Instant effectiveFrom, Instant effectiveUntil,
            Instant lastVerifiedAt, UUID sourceSnapshotId, UUID createdBy, List<ChunkDraft> chunks) {
    }

    record RuleDraft(String ruleKey, UUID poiId, UUID parentRuleId, int versionNo, String ticketType,
            String audience, String channel, boolean reservationRequired, LocalTime releaseTime,
            String releaseTimezone, Integer advanceDays, String currency, Long amountMinor, String freeCondition,
            Instant effectiveFrom, Instant effectiveUntil, String holidayException, Instant lastVerifiedAt,
            int staleAfterDays, UUID sourceSnapshotId) {
    }

    record ImportBatchDraft(String batchKey, UUID operatorId, String importType, String status, int rowCount,
            int errorCount, String errorsJson, String resultJson, Instant createdAt) {
    }

    // ----------------------------------------------------------- output rows

    record SourceRow(UUID sourceRecordId, UUID sourceSnapshotId, String sourceType, String providerName,
            String externalRecordId, String sourceUrl, String licenseType, String licenseReference,
            String permittedUse, boolean attributionRequired, Instant retrievedAt, Instant validUntil,
            String freshnessStatus, String sourceStatus) {
    }

    record PoiRow(UUID poiId, UUID cityId, String cityCode, String externalPoiId, String name, String category,
            String status, String district, UUID mergeGroupId, Double latitude, Double longitude,
            String coordinateSystem, UUID currentSnapshotId) {
    }

    record PoiConflictRow(UUID candidateId, String cityCode, UUID candidatePoiId, String candidateName,
            UUID existingPoiId, String existingName, String conflictType, String status, Instant createdAt) {
    }

    record KnowledgeRow(UUID knowledgeId, String entryKey, UUID poiId, String poiName, UUID cityId,
            String cityCode, UUID parentEntryId, int versionNo, String title, String factType,
            String factValueJson, ReviewStatus status, Instant effectiveFrom, Instant effectiveUntil,
            Instant lastVerifiedAt, Instant publishedAt, Instant reviewedAt, UUID reviewedBy, Instant disabledAt,
            UUID sourceSnapshotId, UUID createdBy, Instant createdAt, Instant updatedAt) {
    }

    record ChunkRow(UUID chunkId, UUID knowledgeEntryId, int chunkNo, String contentText, String contentHash,
            String vectorRecordId) {
    }

    record RuleRow(UUID policyId, String ruleKey, UUID poiId, String poiName, String cityCode, UUID parentRuleId,
            int versionNo, String ticketType, String audience, String channel, boolean reservationRequired,
            LocalTime releaseTime, String releaseTimezone, Integer advanceDays, String currency, Long amountMinor,
            String freeCondition, Instant effectiveFrom, Instant effectiveUntil, String holidayException,
            Instant lastVerifiedAt, int staleAfterDays, ReviewStatus status, Instant publishedAt, Instant reviewedAt,
            UUID reviewedBy, UUID sourceSnapshotId, Instant createdAt) {
    }
}
