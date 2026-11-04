package com.agenttravel.backend.knowledge;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response DTOs for the S15 admin and internal read-only endpoints. */
public final class KnowledgeModels {

    private KnowledgeModels() {
    }

    // ---------------------------------------------------------------- sources

    public record SourceRegistrationRequest(
            @JsonProperty("source_type") @NotBlank @Size(max = 32) String sourceType,
            @JsonProperty("provider_name") @NotBlank @Size(max = 64) String providerName,
            @JsonProperty("external_record_id") @NotBlank @Size(max = 255) String externalRecordId,
            @JsonProperty("source_url") @Size(max = 2048) String sourceUrl,
            @JsonProperty("license_type") @NotBlank @Size(max = 64) String licenseType,
            @JsonProperty("license_reference") @Size(max = 255) String licenseReference,
            @JsonProperty("permitted_use") @Size(max = 64) String permittedUse,
            @JsonProperty("attribution_required") Boolean attributionRequired,
            @JsonProperty("valid_until") Instant validUntil,
            @JsonProperty("summary") Map<String, Object> summary) {
    }

    public record SourceView(
            @JsonProperty("source_record_id") UUID sourceRecordId,
            @JsonProperty("source_snapshot_id") UUID sourceSnapshotId,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("provider_name") String providerName,
            @JsonProperty("external_record_id") String externalRecordId,
            @JsonProperty("source_url") String sourceUrl,
            @JsonProperty("license_type") String licenseType,
            @JsonProperty("license_reference") String licenseReference,
            @JsonProperty("permitted_use") String permittedUse,
            @JsonProperty("attribution_required") boolean attributionRequired,
            @JsonProperty("retrieved_at") Instant retrievedAt,
            @JsonProperty("valid_until") Instant validUntil,
            @JsonProperty("freshness_status") String freshnessStatus,
            @JsonProperty("source_status") String sourceStatus) {
    }

    // -------------------------------------------------------------------- POI

    public record PoiDraftRequest(
            @JsonProperty("city") @NotBlank @Size(max = 128) String city,
            @JsonProperty("external_poi_id") @Size(max = 255) String externalPoiId,
            @JsonProperty("name") @NotBlank @Size(max = 255) String name,
            @JsonProperty("category") @Size(max = 64) String category,
            @JsonProperty("district") @Size(max = 128) String district,
            @JsonProperty("longitude") @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            @JsonProperty("latitude") @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @JsonProperty("coordinate_system") @Size(max = 16) String coordinateSystem,
            @JsonProperty("source_snapshot_id") @NotNull UUID sourceSnapshotId,
            @JsonProperty("merge_group_id") UUID mergeGroupId,
            @JsonProperty("aliases") List<@NotBlank @Size(max = 255) String> aliases) {
    }

    public record PoiView(
            @JsonProperty("poi_id") UUID poiId,
            @JsonProperty("city_code") String cityCode,
            @JsonProperty("external_poi_id") String externalPoiId,
            @JsonProperty("name") String name,
            @JsonProperty("category") String category,
            @JsonProperty("district") String district,
            @JsonProperty("longitude") Double longitude,
            @JsonProperty("latitude") Double latitude,
            @JsonProperty("coordinate_system") String coordinateSystem,
            @JsonProperty("status") String status,
            @JsonProperty("merge_group_id") UUID mergeGroupId,
            @JsonProperty("aliases") List<String> aliases,
            @JsonProperty("conflict_ids") List<UUID> conflictIds) {
    }

    public record AliasRequest(
            @JsonProperty("alias") @NotBlank @Size(max = 255) String alias,
            @JsonProperty("alias_type") @Size(max = 32) String aliasType) {
    }

    public record AliasView(
            @JsonProperty("alias_id") UUID aliasId,
            @JsonProperty("poi_id") UUID poiId,
            @JsonProperty("alias") String alias,
            @JsonProperty("alias_type") String aliasType,
            @JsonProperty("status") String status,
            @JsonProperty("conflict_ids") List<UUID> conflictIds) {
    }

    public record PoiConflictView(
            @JsonProperty("candidate_id") UUID candidateId,
            @JsonProperty("city_code") String cityCode,
            @JsonProperty("candidate_poi_id") UUID candidatePoiId,
            @JsonProperty("candidate_name") String candidateName,
            @JsonProperty("existing_poi_id") UUID existingPoiId,
            @JsonProperty("existing_name") String existingName,
            @JsonProperty("conflict_type") String conflictType,
            @JsonProperty("status") String status,
            @JsonProperty("created_at") Instant createdAt) {
    }

    // -------------------------------------------------------------- knowledge

    public record ChunkRequest(
            @JsonProperty("text") @NotBlank @Size(max = 8000) String text,
            @JsonProperty("vector_record_id") @Size(max = 255) String vectorRecordId) {
    }

    public record EntryDraftRequest(
            @JsonProperty("entry_key") @Size(max = 128) String entryKey,
            @JsonProperty("parent_entry_id") UUID parentEntryId,
            @JsonProperty("poi_id") @NotNull UUID poiId,
            @JsonProperty("city") @NotBlank @Size(max = 128) String city,
            @JsonProperty("title") @NotBlank @Size(max = 255) String title,
            @JsonProperty("fact_type") @NotBlank @Size(max = 32) String factType,
            @JsonProperty("fact_value") @NotNull Object factValue,
            @JsonProperty("effective_from") Instant effectiveFrom,
            @JsonProperty("effective_until") Instant effectiveUntil,
            @JsonProperty("last_verified_at") @NotNull Instant lastVerifiedAt,
            @JsonProperty("source_snapshot_id") @NotNull UUID sourceSnapshotId,
            @JsonProperty("chunks") @NotEmpty List<ChunkRequest> chunks) {
    }

    public record EntryTransitionRequest(
            @JsonProperty("expected_version_no") @Min(1) Integer expectedVersionNo) {
    }

    public record ChunkView(
            @JsonProperty("chunk_id") UUID chunkId,
            @JsonProperty("chunk_no") int chunkNo,
            @JsonProperty("text") String text,
            @JsonProperty("vector_record_id") String vectorRecordId) {
    }

    public record KnowledgeView(
            @JsonProperty("knowledge_id") UUID knowledgeId,
            @JsonProperty("entry_key") String entryKey,
            @JsonProperty("poi_id") UUID poiId,
            @JsonProperty("poi_name") String poiName,
            @JsonProperty("city_code") String cityCode,
            @JsonProperty("parent_entry_id") UUID parentEntryId,
            @JsonProperty("version_no") int versionNo,
            @JsonProperty("title") String title,
            @JsonProperty("fact_type") String factType,
            @JsonProperty("fact_value_json") String factValueJson,
            @JsonProperty("status") ReviewStatus status,
            @JsonProperty("effective_from") Instant effectiveFrom,
            @JsonProperty("effective_until") Instant effectiveUntil,
            @JsonProperty("last_verified_at") Instant lastVerifiedAt,
            @JsonProperty("published_at") Instant publishedAt,
            @JsonProperty("reviewed_at") Instant reviewedAt,
            @JsonProperty("reviewed_by") UUID reviewedBy,
            @JsonProperty("disabled_at") Instant disabledAt,
            @JsonProperty("source_snapshot_id") UUID sourceSnapshotId,
            @JsonProperty("source") SourceView source,
            @JsonProperty("chunks") List<ChunkView> chunks) {
    }

    // -------------------------------------------------------- reservation rule

    public record ReservationRuleDraftRequest(
            @JsonProperty("poi_id") @NotNull UUID poiId,
            @JsonProperty("rule_key") @Size(max = 128) String ruleKey,
            @JsonProperty("ticket_type") @NotBlank @Size(max = 128) String ticketType,
            @JsonProperty("audience") @Size(max = 128) String audience,
            @JsonProperty("channel") @Size(max = 128) String channel,
            @JsonProperty("reservation_required") Boolean reservationRequired,
            @JsonProperty("release_time") String releaseTime,
            @JsonProperty("release_timezone") @Size(max = 64) String releaseTimezone,
            @JsonProperty("advance_days") @Min(0) Integer advanceDays,
            @JsonProperty("currency") @Size(min = 3, max = 3) String currency,
            @JsonProperty("amount_minor") @Min(0) Long amountMinor,
            @JsonProperty("free_condition") @Size(max = 1000) String freeCondition,
            @JsonProperty("effective_from") @NotNull Instant effectiveFrom,
            @JsonProperty("effective_until") Instant effectiveUntil,
            @JsonProperty("holiday_exception") @Size(max = 1000) String holidayException,
            @JsonProperty("last_verified_at") @NotNull Instant lastVerifiedAt,
            @JsonProperty("stale_after_days") @Min(1) Integer staleAfterDays,
            @JsonProperty("source_snapshot_id") @NotNull UUID sourceSnapshotId) {
    }

    public record ReservationRuleView(
            @JsonProperty("policy_id") UUID policyId,
            @JsonProperty("rule_key") String ruleKey,
            @JsonProperty("poi_id") UUID poiId,
            @JsonProperty("poi_name") String poiName,
            @JsonProperty("city_code") String cityCode,
            @JsonProperty("parent_rule_id") UUID parentRuleId,
            @JsonProperty("version_no") int versionNo,
            @JsonProperty("ticket_type") String ticketType,
            @JsonProperty("audience") String audience,
            @JsonProperty("channel") String channel,
            @JsonProperty("reservation_required") boolean reservationRequired,
            @JsonProperty("release_time") LocalTime releaseTime,
            @JsonProperty("release_timezone") String releaseTimezone,
            @JsonProperty("advance_days") Integer advanceDays,
            @JsonProperty("currency") String currency,
            @JsonProperty("amount_minor") Long amountMinor,
            @JsonProperty("free_condition") String freeCondition,
            @JsonProperty("effective_from") Instant effectiveFrom,
            @JsonProperty("effective_until") Instant effectiveUntil,
            @JsonProperty("holiday_exception") String holidayException,
            @JsonProperty("last_verified_at") Instant lastVerifiedAt,
            @JsonProperty("stale_after_days") int staleAfterDays,
            @JsonProperty("stale") boolean stale,
            @JsonProperty("status") ReviewStatus status,
            @JsonProperty("published_at") Instant publishedAt,
            @JsonProperty("reviewed_at") Instant reviewedAt,
            @JsonProperty("reviewed_by") UUID reviewedBy,
            @JsonProperty("source_snapshot_id") UUID sourceSnapshotId,
            @JsonProperty("source") SourceView source) {
    }

    // --------------------------------------------------------------- imports

    public record ImportRow(
            @JsonProperty("row_no") Integer rowNo,
            @JsonProperty("city") @NotBlank String city,
            @JsonProperty("poi_id") UUID poiId,
            @JsonProperty("poi_name") @Size(max = 255) String poiName,
            @JsonProperty("entry_key") @Size(max = 128) String entryKey,
            @JsonProperty("title") @Size(max = 255) String title,
            @JsonProperty("fact_type") @Size(max = 32) String factType,
            @JsonProperty("fact_value") Object factValue,
            @JsonProperty("effective_from") Instant effectiveFrom,
            @JsonProperty("effective_until") Instant effectiveUntil,
            @JsonProperty("last_verified_at") Instant lastVerifiedAt,
            @JsonProperty("source_snapshot_id") UUID sourceSnapshotId,
            @JsonProperty("chunks") List<String> chunks) {
    }

    public record ImportPreviewRequest(
            @JsonProperty("import_type") @NotBlank String importType,
            @JsonProperty("rows") @NotEmpty List<ImportRow> rows) {
    }

    public record ImportError(
            @JsonProperty("row_no") int rowNo,
            @JsonProperty("code") String code,
            @JsonProperty("message") String message) {
    }

    public record ImportPreviewResponse(
            @JsonProperty("row_count") int rowCount,
            @JsonProperty("valid_count") int validCount,
            @JsonProperty("error_count") int errorCount,
            @JsonProperty("errors") List<ImportError> errors) {
    }

    public record ImportCommitResponse(
            @JsonProperty("import_batch_id") UUID importBatchId,
            @JsonProperty("created_count") int createdCount,
            @JsonProperty("knowledge_ids") List<UUID> knowledgeIds,
            @JsonProperty("errors") List<ImportError> errors) {
    }

    // ---------------------------------------------------------------- queries

    public record QueryPoint(
            @JsonProperty("point_id") @NotBlank String pointId,
            @JsonProperty("poi_id") UUID poiId,
            @JsonProperty("name") @Size(max = 255) String name,
            @JsonProperty("longitude") Double longitude,
            @JsonProperty("latitude") Double latitude) {
    }

    public record AttractionQueryRequest(
            @JsonProperty("city") @NotBlank @Size(max = 128) String city,
            @JsonProperty("points") @NotEmpty List<QueryPoint> points,
            @JsonProperty("preference_tags") List<String> preferenceTags,
            @JsonProperty("limit") @Min(1) @Max(50) Integer limit) {
    }

    public record SourceMetadata(
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("source_record_id") UUID sourceRecordId,
            @JsonProperty("retrieved_at") Instant retrievedAt,
            @JsonProperty("valid_until") Instant validUntil,
            @JsonProperty("freshness_status") String freshnessStatus,
            @JsonProperty("is_estimated") boolean isEstimated) {
    }

    public record AttractionCard(
            @JsonProperty("point_id") String pointId,
            @JsonProperty("poi_id") UUID poiId,
            @JsonProperty("knowledge_id") UUID knowledgeId,
            @JsonProperty("chunk_ids") List<UUID> chunkIds,
            @JsonProperty("title") String title,
            @JsonProperty("fact_type") String factType,
            @JsonProperty("fact_value") Object factValue,
            @JsonProperty("retrieved_at") Instant retrievedAt,
            @JsonProperty("valid_until") Instant validUntil,
            @JsonProperty("freshness_status") String freshnessStatus,
            @JsonProperty("source") String source,
            @JsonProperty("source_metadata") SourceMetadata sourceMetadata) {
    }

    public record AttractionQueryResponse(
            @JsonProperty("city") String city,
            @JsonProperty("cards") List<AttractionCard> cards,
            @JsonProperty("source_metadata") List<SourceMetadata> sourceMetadata,
            @JsonProperty("retrieved_at") Instant retrievedAt) {
    }

    public record ReservationQueryRequest(
            @JsonProperty("city") @NotBlank @Size(max = 128) String city,
            @JsonProperty("points") @NotEmpty List<QueryPoint> points,
            @JsonProperty("date") @NotNull LocalDate date,
            @JsonProperty("limit") @Min(1) @Max(50) Integer limit) {
    }

    public record ReservationItem(
            @JsonProperty("point_id") String pointId,
            @JsonProperty("policy_id") UUID policyId,
            @JsonProperty("ticket_type") String ticketType,
            @JsonProperty("currency") String currency,
            @JsonProperty("amount_minor") Long amountMinor,
            @JsonProperty("effective_from") Instant effectiveFrom,
            @JsonProperty("effective_until") Instant effectiveUntil,
            @JsonProperty("timezone") String timezone,
            @JsonProperty("last_verified") Instant lastVerified,
            @JsonProperty("stale") boolean stale) {
    }

    public record ReservationQueryResponse(
            @JsonProperty("city") String city,
            @JsonProperty("date") LocalDate date,
            @JsonProperty("reservations") List<ReservationItem> reservations,
            @JsonProperty("source_metadata") List<SourceMetadata> sourceMetadata,
            @JsonProperty("retrieved_at") Instant retrievedAt) {
    }

    // ------------------------------------------------------- internal rows

    public record ImportBatchView(
            @JsonProperty("import_batch_id") UUID importBatchId,
            @JsonProperty("import_type") String importType,
            @JsonProperty("status") String status,
            @JsonProperty("row_count") int rowCount,
            @JsonProperty("error_count") int errorCount,
            @JsonProperty("created_at") Instant createdAt) {
    }
}
