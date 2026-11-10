package com.agenttravel.backend.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class JdbcKnowledgeRepository implements KnowledgeRepository {

    private static final String SOURCE_COLUMNS = """
            SELECT BIN_TO_UUID(sr.id),BIN_TO_UUID(ss.id),sr.source_type,sr.provider_name,
              sr.external_record_id,
              JSON_UNQUOTE(JSON_EXTRACT(sr.sanitized_summary_json,'$.source_url')),
              JSON_UNQUOTE(JSON_EXTRACT(sr.sanitized_summary_json,'$.license_type')),
              JSON_UNQUOTE(JSON_EXTRACT(sr.sanitized_summary_json,'$.license_reference')),
              JSON_UNQUOTE(JSON_EXTRACT(sr.sanitized_summary_json,'$.permitted_use')),
              CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(sr.sanitized_summary_json,'$.attribution_required'))='true'
                THEN TRUE ELSE FALSE END,
              ss.retrieved_at,ss.valid_until,ss.freshness_status,sr.status
            FROM source_snapshot ss JOIN source_record sr ON sr.id=ss.source_record_id
            """;

    private static final String POI_COLUMNS = """
            SELECT BIN_TO_UUID(p.id),BIN_TO_UUID(p.city_id),c.city_code,p.external_poi_id,p.name,p.category,
              p.status,p.district,
              CASE WHEN p.merge_group_id IS NULL THEN NULL ELSE BIN_TO_UUID(p.merge_group_id) END,
              ps.latitude,ps.longitude,ps.coordinate_system,
              CASE WHEN p.current_snapshot_id IS NULL THEN NULL ELSE BIN_TO_UUID(p.current_snapshot_id) END
            FROM poi p JOIN city c ON c.id=p.city_id
            LEFT JOIN poi_snapshot ps ON ps.id=p.current_snapshot_id
            """;

    private static final String KNOWLEDGE_COLUMNS = """
            SELECT BIN_TO_UUID(ke.id),ke.entry_key,
              CASE WHEN ke.poi_id IS NULL THEN NULL ELSE BIN_TO_UUID(ke.poi_id) END,
              p.name,BIN_TO_UUID(ke.city_id),c.city_code,
              CASE WHEN ke.parent_entry_id IS NULL THEN NULL ELSE BIN_TO_UUID(ke.parent_entry_id) END,
              ke.version_no,ke.title,ke.fact_type,ke.fact_value_json,ke.status,
              ke.effective_from,ke.effective_until,ke.last_verified_at,ke.published_at,ke.reviewed_at,
              CASE WHEN ke.reviewed_by IS NULL THEN NULL ELSE BIN_TO_UUID(ke.reviewed_by) END,
              ke.disabled_at,BIN_TO_UUID(ke.source_snapshot_id),BIN_TO_UUID(ke.created_by),
              ke.created_at,ke.updated_at
            FROM knowledge_entry ke JOIN city c ON c.id=ke.city_id
            LEFT JOIN poi p ON p.id=ke.poi_id
            """;

    private static final String RULE_COLUMNS = """
            SELECT BIN_TO_UUID(r.id),r.rule_key,BIN_TO_UUID(r.poi_id),p.name,c.city_code,
              CASE WHEN r.parent_rule_id IS NULL THEN NULL ELSE BIN_TO_UUID(r.parent_rule_id) END,
              r.version_no,r.ticket_type,r.audience,r.channel,r.reservation_required,
              r.release_time,r.release_timezone,r.advance_days,r.currency,r.amount_minor,
              r.free_condition,r.effective_from,r.effective_until,r.holiday_exception,
              r.last_verified_at,r.stale_after_days,r.status,r.published_at,r.reviewed_at,
              CASE WHEN r.reviewed_by IS NULL THEN NULL ELSE BIN_TO_UUID(r.reviewed_by) END,
              BIN_TO_UUID(r.source_snapshot_id),r.created_at
            FROM reservation_rule r JOIN poi p ON p.id=r.poi_id JOIN city c ON c.id=p.city_id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcKnowledgeRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- sources

    @Override
    public SourceRow registerSource(SourceRegistration registration) {
        Instant retrievedAt = registration.retrievedAt() == null ? Instant.now() : registration.retrievedAt();
        String summaryJson = json(summary(registration));
        String payloadJson = json(registration.payload() == null ? Map.of() : registration.payload());
        String freshness = registration.validUntil() != null && !retrievedAt.isBefore(registration.validUntil())
                ? "STALE" : "FRESH";
        byte[] contentHash = hash(summaryJson + "|" + payloadJson + "|" + retrievedAt);

        UUID sourceRecordId;
        List<UUID> existing = jdbc.query(
                "SELECT BIN_TO_UUID(id) FROM source_record WHERE source_type=? AND provider_name=? AND external_record_id=?",
                (rs, rowNum) -> UUID.fromString(rs.getString(1)),
                registration.sourceType(), registration.providerName(), registration.externalRecordId());
        if (existing.isEmpty()) {
            sourceRecordId = UUID.randomUUID();
            try {
                jdbc.update("""
                        INSERT INTO source_record(
                          id,source_type,provider_name,external_record_id,sanitized_summary_json,status,
                          created_at,updated_at
                        ) VALUES(UUID_TO_BIN(?),?,?,?,CAST(? AS JSON),'ACTIVE',?,?)
                        """,
                        sourceRecordId.toString(), registration.sourceType(), registration.providerName(),
                        registration.externalRecordId(), summaryJson, Timestamp.from(retrievedAt),
                        Timestamp.from(retrievedAt));
            } catch (DuplicateKeyException exception) {
                sourceRecordId = jdbc.queryForObject(
                        "SELECT BIN_TO_UUID(id) FROM source_record WHERE source_type=? AND provider_name=? AND external_record_id=?",
                        (rs, rowNum) -> UUID.fromString(rs.getString(1)), registration.sourceType(),
                        registration.providerName(), registration.externalRecordId());
            }
        } else {
            sourceRecordId = existing.get(0);
        }
        jdbc.update("""
                UPDATE source_record SET sanitized_summary_json=CAST(? AS JSON),updated_at=?
                WHERE id=UUID_TO_BIN(?)
                """, summaryJson, Timestamp.from(retrievedAt), sourceRecordId.toString());

        UUID snapshotId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO source_snapshot(
                  id,source_record_id,retrieved_at,valid_until,freshness_status,is_estimated,
                  content_hash,sanitized_payload_json,created_at
                ) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,FALSE,?,CAST(? AS JSON),?)
                """,
                snapshotId.toString(), sourceRecordId.toString(), Timestamp.from(retrievedAt),
                timestamp(registration.validUntil()), freshness, contentHash, payloadJson,
                Timestamp.from(retrievedAt));
        return findSourceSnapshot(snapshotId);
    }

    @Override
    public SourceRow findSourceSnapshot(UUID sourceSnapshotId) {
        List<SourceRow> rows = jdbc.query(SOURCE_COLUMNS + " WHERE ss.id=UUID_TO_BIN(?)",
                this::sourceRow, sourceSnapshotId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    // -------------------------------------------------------------------- POI

    @Override
    public UUID findCityId(String city) {
        List<UUID> rows = jdbc.query("SELECT BIN_TO_UUID(id) FROM city WHERE city_code=? OR name=? LIMIT 1",
                (rs, rowNum) -> UUID.fromString(rs.getString(1)), city, city);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public PoiRow findPoi(UUID poiId) {
        List<PoiRow> rows = jdbc.query(POI_COLUMNS + " WHERE p.id=UUID_TO_BIN(?)", this::poiRow,
                poiId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<PoiRow> findPoisByName(UUID cityId, String name) {
        return jdbc.query(POI_COLUMNS + " WHERE p.city_id=UUID_TO_BIN(?) AND p.name=? ORDER BY p.created_at",
                this::poiRow, cityId.toString(), name);
    }

    @Override
    public List<PoiRow> findPoisNear(UUID cityId, double latitude, double longitude, double tolerance) {
        return jdbc.query(POI_COLUMNS + """
                 WHERE p.city_id=UUID_TO_BIN(?) AND ps.latitude IS NOT NULL AND ps.longitude IS NOT NULL
                   AND ABS(ps.latitude-?)<=? AND ABS(ps.longitude-?)<=?
                 ORDER BY p.created_at
                """, this::poiRow, cityId.toString(), latitude, tolerance, longitude, tolerance);
    }

    @Override
    public List<PoiRow> findPoisByAlias(UUID cityId, String alias) {
        return jdbc.query(POI_COLUMNS + """
                 JOIN poi_alias a ON a.poi_id=p.id AND a.status='ACTIVE'
                 WHERE p.city_id=UUID_TO_BIN(?) AND a.alias=?
                 ORDER BY p.created_at
                """, this::poiRow, cityId.toString(), alias);
    }

    @Override
    public List<String> findAliases(UUID poiId) {
        return jdbc.query("SELECT alias FROM poi_alias WHERE poi_id=UUID_TO_BIN(?) AND status='ACTIVE' ORDER BY created_at",
                (rs, rowNum) -> rs.getString(1), poiId.toString());
    }

    @Override
    public PoiRow insertPoi(PoiDraft draft) {
        UUID poiId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO poi(id,city_id,external_poi_id,name,category,status,district,merge_group_id,
                  created_at,updated_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,'DRAFT',?,UUID_TO_BIN(?),?,?)
                """,
                poiId.toString(), draft.cityId().toString(), draft.externalPoiId(), draft.name(),
                draft.category(), draft.district(), uuid(draft.mergeGroupId()), Timestamp.from(now),
                Timestamp.from(now));

        UUID snapshotId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", draft.name());
        payload.put("category", draft.category());
        payload.put("district", draft.district());
        payload.put("longitude", draft.longitude());
        payload.put("latitude", draft.latitude());
        payload.put("coordinate_system", draft.coordinateSystem());
        jdbc.update("""
                INSERT INTO poi_snapshot(
                  id,poi_id,source_snapshot_id,snapshot_no,name,latitude,longitude,coordinate_system,
                  category,payload_json,content_hash,created_at
                ) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),1,?,?,?,?,?,CAST(? AS JSON),?,?)
                """,
                snapshotId.toString(), poiId.toString(), draft.sourceSnapshotId().toString(), draft.name(),
                draft.latitude(), draft.longitude(), draft.coordinateSystem(), draft.category(), json(payload),
                hash(draft.name() + "|" + draft.latitude() + "|" + draft.longitude() + "|" + draft.category()),
                Timestamp.from(now));
        jdbc.update("UPDATE poi SET current_snapshot_id=UUID_TO_BIN(?),updated_at=? WHERE id=UUID_TO_BIN(?)",
                snapshotId.toString(), Timestamp.from(now), poiId.toString());
        return findPoi(poiId);
    }

    @Override
    public UUID insertAlias(UUID poiId, String alias, String aliasType) {
        UUID aliasId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO poi_alias(id,poi_id,alias,alias_type,status,created_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,'ACTIVE',?)
                """, aliasId.toString(), poiId.toString(), alias, aliasType, Timestamp.from(Instant.now()));
        return aliasId;
    }

    @Override
    public UUID insertPoiConflict(PoiConflictDraft draft) {
        UUID candidateId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO poi_merge_candidate(
                      id,city_id,candidate_poi_id,existing_poi_id,conflict_type,status,details_json,created_at
                    ) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,'OPEN',
                      CAST(? AS JSON),?)
                    """,
                    candidateId.toString(), draft.cityId().toString(), draft.candidatePoiId().toString(),
                    draft.existingPoiId().toString(), draft.conflictType(), json(draft.details()),
                    Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException exception) {
            return jdbc.queryForObject("""
                    SELECT BIN_TO_UUID(id) FROM poi_merge_candidate
                    WHERE candidate_poi_id=UUID_TO_BIN(?) AND existing_poi_id=UUID_TO_BIN(?) AND conflict_type=?
                    """, (rs, rowNum) -> UUID.fromString(rs.getString(1)),
                    draft.candidatePoiId().toString(), draft.existingPoiId().toString(), draft.conflictType());
        }
        return candidateId;
    }

    @Override
    public PoiConflictRow findPoiConflict(UUID conflictId) {
        List<PoiConflictRow> rows = jdbc.query("""
                SELECT BIN_TO_UUID(m.id),c.city_code,BIN_TO_UUID(m.candidate_poi_id),cp.name,
                  BIN_TO_UUID(m.existing_poi_id),ep.name,m.conflict_type,m.status,m.created_at
                FROM poi_merge_candidate m
                JOIN city c ON c.id=m.city_id
                JOIN poi cp ON cp.id=m.candidate_poi_id
                JOIN poi ep ON ep.id=m.existing_poi_id
                WHERE m.id=UUID_TO_BIN(?)
                """, JdbcKnowledgeRepository::poiConflict, conflictId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<PoiConflictRow> listOpenPoiConflicts() {
        return jdbc.query("""
                SELECT BIN_TO_UUID(m.id),c.city_code,BIN_TO_UUID(m.candidate_poi_id),cp.name,
                  BIN_TO_UUID(m.existing_poi_id),ep.name,m.conflict_type,m.status,m.created_at
                FROM poi_merge_candidate m
                JOIN city c ON c.id=m.city_id
                JOIN poi cp ON cp.id=m.candidate_poi_id
                JOIN poi ep ON ep.id=m.existing_poi_id
                WHERE m.status='OPEN'
                ORDER BY m.created_at
                """, JdbcKnowledgeRepository::poiConflict);
    }

    @Override
    public int resolvePoiConflict(UUID conflictId, String status, UUID resolvedBy, Instant at) {
        return jdbc.update("""
                UPDATE poi_merge_candidate
                SET status=?,resolved_by=UUID_TO_BIN(?),resolved_at=?
                WHERE id=UUID_TO_BIN(?) AND status='OPEN'
                """, status, resolvedBy.toString(), Timestamp.from(at), conflictId.toString());
    }

    @Override
    public int setPoiMergeGroup(UUID poiId, UUID mergeGroupId) {
        return jdbc.update("""
                UPDATE poi SET merge_group_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)
                """, mergeGroupId.toString(), poiId.toString());
    }

    private static PoiConflictRow poiConflict(ResultSet rs, int rowNum) throws SQLException {
        return new PoiConflictRow(UUID.fromString(rs.getString(1)), rs.getString(2),
                UUID.fromString(rs.getString(3)), rs.getString(4), UUID.fromString(rs.getString(5)),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant());
    }

    // -------------------------------------------------------------- knowledge

    @Override
    public KnowledgeRow insertEntry(KnowledgeDraft draft) {
        UUID entryId = UUID.randomUUID();
        Instant now = Instant.now();
        byte[] contentHash = entryHash(draft);
        jdbc.update("""
                INSERT INTO knowledge_entry(
                  id,entry_key,poi_id,city_id,parent_entry_id,version_no,title,status,
                  effective_from,effective_until,last_verified_at,content_hash,source_snapshot_id,created_by,
                  fact_type,fact_value_json,created_at,updated_at
                ) VALUES(UUID_TO_BIN(?),?,UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,'DRAFT',?,?,?,?,
                  UUID_TO_BIN(?),UUID_TO_BIN(?),?,CAST(? AS JSON),?,?)
                """,
                entryId.toString(), draft.entryKey(), draft.poiId().toString(), draft.cityId().toString(),
                uuid(draft.parentEntryId()), draft.versionNo(), draft.title(), timestamp(draft.effectiveFrom()),
                timestamp(draft.effectiveUntil()), timestamp(draft.lastVerifiedAt()), contentHash,
                draft.sourceSnapshotId().toString(), draft.createdBy().toString(), draft.factType(),
                draft.factValueJson(), Timestamp.from(now), Timestamp.from(now));
        insertChunks(entryId, draft.chunks());
        return findEntry(entryId);
    }

    @Override
    public int updateEntryDraft(UUID entryId, KnowledgeDraft draft) {
        Instant now = Instant.now();
        int updated = jdbc.update("""
                UPDATE knowledge_entry
                SET title=?,fact_type=?,fact_value_json=CAST(? AS JSON),effective_from=?,effective_until=?,
                  last_verified_at=?,source_snapshot_id=UUID_TO_BIN(?),content_hash=?,updated_at=?
                WHERE id=UUID_TO_BIN(?) AND status='DRAFT'
                """,
                draft.title(), draft.factType(), draft.factValueJson(), timestamp(draft.effectiveFrom()),
                timestamp(draft.effectiveUntil()), timestamp(draft.lastVerifiedAt()),
                draft.sourceSnapshotId().toString(), entryHash(draft), Timestamp.from(now), entryId.toString());
        if (updated != 1) {
            return updated;
        }
        jdbc.update("DELETE FROM knowledge_chunk WHERE knowledge_entry_id=UUID_TO_BIN(?)", entryId.toString());
        insertChunks(entryId, draft.chunks());
        return 1;
    }

    private void insertChunks(UUID entryId, List<ChunkDraft> chunks) {
        for (ChunkDraft chunk : chunks) {
            jdbc.update("""
                    INSERT INTO knowledge_chunk(
                      id,knowledge_entry_id,chunk_no,content_text,content_hash,vector_record_id,created_at
                    ) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?,?)
                    """,
                    UUID.randomUUID().toString(), entryId.toString(), chunk.chunkNo(), chunk.text(),
                    hash(chunk.text() + "|" + chunk.chunkNo()), chunk.vectorRecordId(),
                    Timestamp.from(Instant.now()));
        }
    }

    @Override
    public KnowledgeRow findEntry(UUID entryId) {
        List<KnowledgeRow> rows = jdbc.query(KNOWLEDGE_COLUMNS + " WHERE ke.id=UUID_TO_BIN(?)",
                this::knowledgeRow, entryId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<KnowledgeRow> findEntriesByCity(String city) {
        return jdbc.query(KNOWLEDGE_COLUMNS + " WHERE c.city_code=? OR c.name=? ORDER BY ke.entry_key,ke.version_no DESC",
                this::knowledgeRow, city, city);
    }

    @Override
    public List<KnowledgeRow> findEntriesByKey(String entryKey) {
        return jdbc.query(KNOWLEDGE_COLUMNS + " WHERE ke.entry_key=? ORDER BY ke.version_no DESC",
                this::knowledgeRow, entryKey);
    }

    @Override
    public List<KnowledgeRow> findEntriesByPoi(UUID poiId) {
        return jdbc.query(KNOWLEDGE_COLUMNS + " WHERE ke.poi_id=UUID_TO_BIN(?) ORDER BY ke.entry_key,ke.version_no DESC",
                this::knowledgeRow, poiId.toString());
    }

    @Override
    public List<ChunkRow> findChunks(UUID entryId) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(id),BIN_TO_UUID(knowledge_entry_id),chunk_no,content_text,content_hash,
                  vector_record_id
                FROM knowledge_chunk WHERE knowledge_entry_id=UUID_TO_BIN(?) ORDER BY chunk_no
                """, (rs, rowNum) -> new ChunkRow(UUID.fromString(rs.getString(1)),
                UUID.fromString(rs.getString(2)), rs.getInt(3), rs.getString(4), rs.getString(5),
                rs.getString(6)), entryId.toString());
    }

    @Override
    public int nextEntryVersion(String entryKey) {
        Integer latest = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0) FROM knowledge_entry WHERE entry_key=?", Integer.class,
                entryKey);
        return (latest == null ? 0 : latest) + 1;
    }

    @Override
    public int transitionEntry(UUID entryId, ReviewStatus expected, ReviewStatus next, Instant at, UUID reviewer) {
        StringBuilder sql = new StringBuilder("UPDATE knowledge_entry SET status=?,updated_at=?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(next.name());
        arguments.add(Timestamp.from(at));
        if (next == ReviewStatus.PUBLISHED) {
            sql.append(",published_at=?,reviewed_at=?,reviewed_by=UUID_TO_BIN(?)");
            arguments.add(Timestamp.from(at));
            arguments.add(Timestamp.from(at));
            arguments.add(reviewer == null ? null : reviewer.toString());
        }
        if (next == ReviewStatus.DISABLED || next == ReviewStatus.ROLLED_BACK) {
            sql.append(",disabled_at=?");
            arguments.add(Timestamp.from(at));
        }
        sql.append(" WHERE id=UUID_TO_BIN(?) AND status=?");
        arguments.add(entryId.toString());
        arguments.add(expected.name());
        return jdbc.update(sql.toString(), arguments.toArray());
    }

    // -------------------------------------------------------- reservation rule

    @Override
    public RuleRow insertRule(RuleDraft draft) {
        UUID ruleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reservation_rule(
                  id,rule_key,poi_id,parent_rule_id,version_no,ticket_type,audience,channel,
                  reservation_required,release_time,release_timezone,advance_days,currency,amount_minor,
                  free_condition,effective_from,effective_until,holiday_exception,last_verified_at,
                  stale_after_days,status,source_snapshot_id,created_at
                ) VALUES(UUID_TO_BIN(?),?,UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,
                  'DRAFT',UUID_TO_BIN(?),?)
                """,
                ruleId.toString(), draft.ruleKey(), draft.poiId().toString(), uuid(draft.parentRuleId()),
                draft.versionNo(), draft.ticketType(), draft.audience(), draft.channel(),
                draft.reservationRequired(), time(draft.releaseTime()), draft.releaseTimezone(),
                draft.advanceDays(), draft.currency(), draft.amountMinor(), draft.freeCondition(),
                timestamp(draft.effectiveFrom()), timestamp(draft.effectiveUntil()), draft.holidayException(),
                timestamp(draft.lastVerifiedAt()), draft.staleAfterDays(), draft.sourceSnapshotId().toString(),
                Timestamp.from(Instant.now()));
        return findRule(ruleId);
    }

    @Override
    public int updateRuleDraft(UUID ruleId, RuleDraft draft) {
        return jdbc.update("""
                UPDATE reservation_rule
                SET ticket_type=?,audience=?,channel=?,reservation_required=?,release_time=?,release_timezone=?,
                  advance_days=?,currency=?,amount_minor=?,free_condition=?,effective_from=?,effective_until=?,
                  holiday_exception=?,last_verified_at=?,stale_after_days=?,source_snapshot_id=UUID_TO_BIN(?)
                WHERE id=UUID_TO_BIN(?) AND status='DRAFT'
                """,
                draft.ticketType(), draft.audience(), draft.channel(), draft.reservationRequired(),
                time(draft.releaseTime()), draft.releaseTimezone(), draft.advanceDays(), draft.currency(),
                draft.amountMinor(), draft.freeCondition(), timestamp(draft.effectiveFrom()),
                timestamp(draft.effectiveUntil()), draft.holidayException(), timestamp(draft.lastVerifiedAt()),
                draft.staleAfterDays(), draft.sourceSnapshotId().toString(), ruleId.toString());
    }

    @Override
    public RuleRow findRule(UUID ruleId) {
        List<RuleRow> rows = jdbc.query(RULE_COLUMNS + " WHERE r.id=UUID_TO_BIN(?)", this::ruleRow,
                ruleId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<RuleRow> findRulesByCity(String city) {
        return jdbc.query(RULE_COLUMNS + " WHERE c.city_code=? OR c.name=? ORDER BY r.rule_key,r.version_no DESC",
                this::ruleRow, city, city);
    }

    @Override
    public List<RuleRow> findRulesByPoi(UUID poiId) {
        return jdbc.query(RULE_COLUMNS + " WHERE r.poi_id=UUID_TO_BIN(?) ORDER BY r.rule_key,r.version_no DESC",
                this::ruleRow, poiId.toString());
    }

    @Override
    public List<RuleRow> findRulesByKey(String ruleKey) {
        return jdbc.query(RULE_COLUMNS + " WHERE r.rule_key=? ORDER BY r.version_no DESC", this::ruleRow, ruleKey);
    }

    @Override
    public int nextRuleVersion(String ruleKey) {
        Integer latest = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0) FROM reservation_rule WHERE rule_key=?", Integer.class, ruleKey);
        return (latest == null ? 0 : latest) + 1;
    }

    @Override
    public int transitionRule(UUID ruleId, ReviewStatus expected, ReviewStatus next, Instant at, UUID reviewer) {
        StringBuilder sql = new StringBuilder("UPDATE reservation_rule SET status=?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(next.name());
        if (next == ReviewStatus.PUBLISHED) {
            sql.append(",published_at=?,reviewed_at=?,reviewed_by=UUID_TO_BIN(?)");
            arguments.add(Timestamp.from(at));
            arguments.add(Timestamp.from(at));
            arguments.add(reviewer == null ? null : reviewer.toString());
        }
        sql.append(" WHERE id=UUID_TO_BIN(?) AND status=?");
        arguments.add(ruleId.toString());
        arguments.add(expected.name());
        return jdbc.update(sql.toString(), arguments.toArray());
    }

    // ---------------------------------------------------------------- imports

    @Override
    public UUID insertImportBatch(ImportBatchDraft draft) {
        UUID batchId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO import_batch(
                  id,batch_key,operator_id,import_type,status,row_count,error_count,errors_json,result_json,
                  created_at,completed_at
                ) VALUES(UUID_TO_BIN(?),?,UUID_TO_BIN(?),?,?,?,?,CAST(? AS JSON),CAST(? AS JSON),?,?)
                """,
                batchId.toString(), draft.batchKey(), draft.operatorId().toString(), draft.importType(),
                draft.status(), draft.rowCount(), draft.errorCount(),
                draft.errorsJson() == null ? null : draft.errorsJson(),
                draft.resultJson() == null ? null : draft.resultJson(), Timestamp.from(draft.createdAt()),
                Timestamp.from(draft.createdAt()));
        return batchId;
    }

    // --------------------------------------------------------------- mapping

    private SourceRow sourceRow(ResultSet rs, int rowNum) throws SQLException {
        return new SourceRow(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getBoolean(10), instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12)),
                rs.getString(13), rs.getString(14));
    }

    private PoiRow poiRow(ResultSet rs, int rowNum) throws SQLException {
        return new PoiRow(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                uuid(rs.getString(9)), decimal(rs, 10), decimal(rs, 11), rs.getString(12),
                uuid(rs.getString(13)));
    }

    private KnowledgeRow knowledgeRow(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeRow(UUID.fromString(rs.getString(1)), rs.getString(2), uuid(rs.getString(3)),
                rs.getString(4), UUID.fromString(rs.getString(5)), rs.getString(6), uuid(rs.getString(7)),
                rs.getInt(8), rs.getString(9), rs.getString(10), rs.getString(11),
                ReviewStatus.valueOf(rs.getString(12)), instant(rs.getTimestamp(13)),
                instant(rs.getTimestamp(14)), instant(rs.getTimestamp(15)), instant(rs.getTimestamp(16)),
                instant(rs.getTimestamp(17)), uuid(rs.getString(18)), instant(rs.getTimestamp(19)),
                UUID.fromString(rs.getString(20)), uuid(rs.getString(21)), instant(rs.getTimestamp(22)),
                instant(rs.getTimestamp(23)));
    }

    private RuleRow ruleRow(ResultSet rs, int rowNum) throws SQLException {
        Time release = rs.getTime(12);
        Integer advance = (Integer) rs.getObject(14);
        Long amount = rs.getObject(16) == null ? null : rs.getLong(16);
        return new RuleRow(UUID.fromString(rs.getString(1)), rs.getString(2), UUID.fromString(rs.getString(3)),
                rs.getString(4), rs.getString(5), uuid(rs.getString(6)), rs.getInt(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getBoolean(11), release == null ? null : release.toLocalTime(),
                rs.getString(13), advance, rs.getString(15), amount, rs.getString(17),
                instant(rs.getTimestamp(18)), instant(rs.getTimestamp(19)), rs.getString(20),
                instant(rs.getTimestamp(21)), rs.getInt(22), ReviewStatus.valueOf(rs.getString(23)),
                instant(rs.getTimestamp(24)), instant(rs.getTimestamp(25)), uuid(rs.getString(26)),
                UUID.fromString(rs.getString(27)), instant(rs.getTimestamp(28)));
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> summary(SourceRegistration registration) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (registration.summary() != null) {
            summary.putAll(registration.summary());
        }
        summary.put("source_url", registration.sourceUrl());
        summary.put("license_type", registration.licenseType());
        summary.put("license_reference", registration.licenseReference());
        summary.put("permitted_use", registration.permittedUse());
        summary.put("attribution_required", registration.attributionRequired());
        return summary;
    }

    private byte[] entryHash(KnowledgeDraft draft) {
        StringBuilder value = new StringBuilder();
        value.append(draft.entryKey()).append('|').append(draft.versionNo()).append('|').append(draft.title())
                .append('|').append(draft.factValueJson());
        for (ChunkDraft chunk : draft.chunks()) {
            value.append('|').append(chunk.text());
        }
        return hash(value.toString());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("知识库内容无法序列化", exception);
        }
    }

    private byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Double decimal(ResultSet rs, int index) throws SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(index);
        return value == null ? null : value.doubleValue();
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Time time(LocalTime value) {
        return value == null ? null : Time.valueOf(value);
    }

    private String uuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
