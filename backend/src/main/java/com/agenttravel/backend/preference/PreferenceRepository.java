package com.agenttravel.backend.preference;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class PreferenceRepository {

    private final JdbcTemplate jdbc;

    public PreferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record TripRow(UUID id, String title, LocalDate startDate, LocalDate endDate, String status, UUID currentVersionId) {
    }

    private static final RowMapper<TripRow> TRIP = (rs, index) -> new TripRow(
            UUID.fromString(rs.getString(1)), rs.getString(2),
            rs.getDate(3).toLocalDate(), rs.getDate(4).toLocalDate(), rs.getString(5),
            rs.getString(6) == null ? null : UUID.fromString(rs.getString(6)));

    List<TripRow> findTrips(UUID owner, LocalDate from, LocalDate to, String city, Integer days,
            String status, String query, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT BIN_TO_UUID(id), title, start_date, end_date, status, BIN_TO_UUID(current_version_id)
                FROM trip
                WHERE owner_id = UUID_TO_BIN(?) AND deleted_at IS NULL
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(owner.toString());
        if (from != null) {
            sql.append(" AND start_date >= ?");
            args.add(java.sql.Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND start_date <= ?");
            args.add(java.sql.Date.valueOf(to));
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND title LIKE ?");
            args.add("%" + query + "%");
        }
        if (days != null) {
            sql.append(" AND DATEDIFF(end_date, start_date) + 1 = ?");
            args.add(days);
        }
        if (city != null && !city.isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM trip_city tc JOIN city c ON c.id = tc.city_id ")
                    .append("WHERE tc.trip_id = trip.id AND c.name LIKE ?)");
            args.add("%" + city + "%");
        }
        sql.append(" ORDER BY updated_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), TRIP, args.toArray());
    }

    List<String> findCities(UUID tripId) {
        return jdbc.query("""
                SELECT c.name FROM trip_city tc JOIN city c ON c.id = tc.city_id
                WHERE tc.trip_id = UUID_TO_BIN(?) ORDER BY tc.sequence_no
                """, (rs, index) -> rs.getString(1), tripId.toString());
    }

    int countVersions(UUID tripId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM trip_version WHERE trip_id = UUID_TO_BIN(?)",
                Integer.class, tripId.toString());
        return count == null ? 0 : count;
    }

    TripRow findTrip(UUID owner, UUID tripId) {
        List<TripRow> rows = jdbc.query("""
                SELECT BIN_TO_UUID(id), title, start_date, end_date, status, BIN_TO_UUID(current_version_id)
                FROM trip WHERE id = UUID_TO_BIN(?) AND owner_id = UUID_TO_BIN(?) AND deleted_at IS NULL
                """, TRIP, tripId.toString(), owner.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    record SummaryRow(int version, int sampleCount, boolean eligible, String summaryJson,
            String narrative, Instant generatedAt) {
    }

    private static final RowMapper<SummaryRow> SUMMARY = (rs, index) -> new SummaryRow(
            rs.getInt(1), rs.getInt(2), rs.getBoolean(3), rs.getString(4), rs.getString(5),
            rs.getTimestamp(6).toInstant());

    SummaryRow findLatestSummary(UUID user) {
        List<SummaryRow> rows = jdbc.query("""
                SELECT summary_version, sample_count, eligible, summary_json,
                       JSON_UNQUOTE(JSON_EXTRACT(summary_json, '$.narrative')) AS narrative, generated_at
                FROM preference_summary WHERE user_id = UUID_TO_BIN(?)
                ORDER BY summary_version DESC LIMIT 1
                """, SUMMARY, user.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    int countActiveSummary(UUID user) {
        SummaryRow row = findLatestSummary(user);
        return row != null && row.eligible() ? 1 : 0;
    }

    int countHistories(UUID user) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip WHERE owner_id = UUID_TO_BIN(?)
                  AND deleted_at IS NULL AND status IN ('ACTIVE','ARCHIVED')
                """, Integer.class, user.toString());
        return count == null ? 0 : count;
    }

    record PlanFact(String planJson, LocalDate startDate, UUID snapshotId, UUID tripId, Instant observedAt) {
    }

    List<PlanFact> findLatestPlanFacts(UUID user) {
        return jdbc.query("""
                SELECT s.plan_json, t.start_date, BIN_TO_UUID(s.id), BIN_TO_UUID(t.id), s.created_at
                FROM trip t JOIN trip_version v ON v.id = t.current_version_id
                JOIN plan_snapshot s ON s.trip_version_id = v.id
                WHERE t.owner_id = UUID_TO_BIN(?) AND t.deleted_at IS NULL
                  AND t.status IN ('ACTIVE','ARCHIVED') AND s.plan_json IS NOT NULL
                """, (rs, index) -> new PlanFact(rs.getString(1), rs.getDate(2).toLocalDate(),
                        UUID.fromString(rs.getString(3)), UUID.fromString(rs.getString(4)),
                        rs.getTimestamp(5).toInstant()),
                user.toString());
    }

    record CityCount(String city, int count) {
    }

    List<CityCount> topCities(UUID user, int topK) {
        return jdbc.query("""
                SELECT c.name, COUNT(*) AS cnt
                FROM trip t
                JOIN trip_city tc ON tc.trip_id = t.id
                JOIN city c ON c.id = tc.city_id
                WHERE t.owner_id = UUID_TO_BIN(?) AND t.deleted_at IS NULL
                  AND t.status IN ('ACTIVE','ARCHIVED')
                GROUP BY c.name ORDER BY cnt DESC, c.name ASC LIMIT ?
                """, (rs, index) -> new CityCount(rs.getString(1), rs.getInt(2)), user.toString(), topK);
    }

    /** 城市维度的证据来源行程（用于写 preference_evidence）。 */
    record CityRef(String city, UUID tripId, Instant observedAt) {
    }

    List<CityRef> cityRefs(UUID user, List<String> cities) {
        if (cities == null || cities.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(cities.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(user.toString());
        args.addAll(cities);
        return jdbc.query("""
                SELECT c.name, BIN_TO_UUID(t.id), t.created_at
                FROM trip t
                JOIN trip_city tc ON tc.trip_id = t.id
                JOIN city c ON c.id = tc.city_id
                WHERE t.owner_id = UUID_TO_BIN(?) AND t.deleted_at IS NULL
                  AND t.status IN ('ACTIVE','ARCHIVED') AND c.name IN (%s)
                """.formatted(placeholders), (rs, index) -> new CityRef(rs.getString(1),
                UUID.fromString(rs.getString(2)), rs.getTimestamp(3).toInstant()), args.toArray());
    }

    record StatusCount(String status, int count) {
    }

    List<StatusCount> statusCounts(UUID user) {
        return jdbc.query("""
                SELECT status, COUNT(*) AS cnt FROM trip
                WHERE owner_id = UUID_TO_BIN(?) AND deleted_at IS NULL
                  AND status IN ('ACTIVE','ARCHIVED')
                GROUP BY status
                """, (rs, index) -> new StatusCount(rs.getString(1), rs.getInt(2)), user.toString());
    }

    /** 状态维度的证据来源行程（用于写 preference_evidence）。 */
    record TripRef(String status, UUID tripId, Instant observedAt) {
    }

    List<TripRef> statusRefs(UUID user) {
        return jdbc.query("""
                SELECT status, BIN_TO_UUID(id), created_at FROM trip
                WHERE owner_id = UUID_TO_BIN(?) AND deleted_at IS NULL
                  AND status IN ('ACTIVE','ARCHIVED')
                """, (rs, index) -> new TripRef(rs.getString(1), UUID.fromString(rs.getString(2)),
                rs.getTimestamp(3).toInstant()), user.toString());
    }

    record DateRange(java.time.LocalDate earliest, java.time.LocalDate latest) {
    }

    DateRange dateRange(UUID user) {
        List<DateRange> rows = jdbc.query("""
                SELECT MIN(start_date), MAX(start_date) FROM trip
                WHERE owner_id = UUID_TO_BIN(?) AND deleted_at IS NULL
                  AND status IN ('ACTIVE','ARCHIVED')
                """, (rs, index) -> new DateRange(
                        rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate(),
                        rs.getDate(2) == null ? null : rs.getDate(2).toLocalDate()), user.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    void saveSummary(UUID user, int version, int sampleCount, boolean eligible,
            String summaryJson, String narrative, Instant now) {
        jdbc.update("""
                INSERT INTO preference_summary
                  (id, user_id, summary_version, sample_count, eligible, summary_json, generated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), user.toString(), version, sampleCount, eligible,
                summaryJson, Timestamp.from(now));
    }

    int nextSummaryVersion(UUID user) {
        Integer version = jdbc.queryForObject("""
                SELECT COALESCE(MAX(summary_version), 0) + 1 FROM preference_summary WHERE user_id = UUID_TO_BIN(?)
                """, Integer.class, user.toString());
        return version == null ? 1 : version;
    }

    record VersionRow(UUID id, String planJson, String schemaVersion, String modelVersion, String promptVersion) {
    }

    VersionRow findCurrentVersion(UUID trip) {
        List<VersionRow> rows = jdbc.query("""
                SELECT BIN_TO_UUID(v.id), s.plan_json, v.schema_version, v.model_version, v.prompt_version
                FROM trip_version v JOIN plan_snapshot s ON s.trip_version_id = v.id
                WHERE v.trip_id = UUID_TO_BIN(?) ORDER BY v.version_no DESC LIMIT 1
                """, (rs, index) -> new VersionRow(
                        UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)), trip.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    record VersionMetaRow(UUID id, int versionNo, String versionStatus, Instant createdAt, String schemaVersion) {
    }

    List<VersionMetaRow> findVersionMetas(UUID owner, UUID trip) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(v.id), v.version_no, v.version_status, v.created_at, v.schema_version
                FROM trip_version v JOIN trip t ON t.id = v.trip_id
                WHERE v.trip_id = UUID_TO_BIN(?) AND t.owner_id = UUID_TO_BIN(?) AND t.deleted_at IS NULL
                ORDER BY v.version_no ASC
                """, (rs, index) -> new VersionMetaRow(
                        UUID.fromString(rs.getString(1)), rs.getInt(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getString(5)),
                trip.toString(), owner.toString());
    }

    String findPlanJson(UUID owner, UUID trip, UUID version) {
        List<String> rows = jdbc.query("""
                SELECT s.plan_json FROM trip_version v
                JOIN trip t ON t.id = v.trip_id
                JOIN plan_snapshot s ON s.trip_version_id = v.id
                WHERE v.id = UUID_TO_BIN(?) AND v.trip_id = UUID_TO_BIN(?)
                  AND t.owner_id = UUID_TO_BIN(?) AND t.deleted_at IS NULL
                """, (rs, index) -> rs.getString(1),
                version.toString(), trip.toString(), owner.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    void copyTripAsDraft(UUID source, UUID target, UUID owner, String title,
            LocalDate startDate, LocalDate endDate, Instant now) {
        jdbc.update("""
                INSERT INTO trip (id, owner_id, title, start_date, end_date, status, created_at, updated_at)
                SELECT UUID_TO_BIN(?), UUID_TO_BIN(?), ?, start_date, end_date, 'DRAFT', ?, ?
                FROM trip WHERE id = UUID_TO_BIN(?)
                """, target.toString(), owner.toString(), title, Timestamp.from(now), Timestamp.from(now),
                source.toString());
        jdbc.update("""
                INSERT INTO trip_city (id, trip_id, city_id, sequence_no, created_at)
                SELECT UUID_TO_BIN(?), UUID_TO_BIN(?), city_id, sequence_no, ?
                FROM trip_city WHERE trip_id = UUID_TO_BIN(?)
                """, UUID.randomUUID().toString(), target.toString(), Timestamp.from(now), source.toString());
    }

    void insertVersion(UUID versionId, UUID trip, UUID owner, String planJson, String schemaVersion,
            String modelVersion, String promptVersion, Instant now) {
        byte[] digest = sha256(planJson);
        jdbc.update("""
                INSERT INTO trip_version
                  (id, trip_id, version_no, etag, content_hash, version_status,
                   schema_version, model_version, prompt_version, created_by, created_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 1, ?, ?, 'DRAFT', ?, ?, ?, UUID_TO_BIN(?), ?)
                """, versionId.toString(), trip.toString(), digest, digest,
                schemaVersion == null ? "s24-reuse-v1" : schemaVersion, modelVersion, promptVersion,
                owner.toString(), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO plan_snapshot (id, trip_version_id, plan_json, created_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)
                """, UUID.randomUUID().toString(), versionId.toString(), planJson, Timestamp.from(now));
        jdbc.update("UPDATE trip SET current_version_id = UUID_TO_BIN(?) WHERE id = UUID_TO_BIN(?)",
                versionId.toString(), trip.toString());
    }

    private static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 读取用户当前生效的偏好（每个偏好键只取最新版本），并按证据流水的观测时间折算出有效权重。
     *
     * <p>有效权重 = Σ(weight_delta × 0.5^(距观测天数 / 半衰期))；没有证据行的历史数据回落到
     * 存储权重，由服务层按 {@code last_used_at} 折算。有效权重可直接由证据重放复现。
     */
    List<PreferenceItemRow> findActivePreferences(UUID user, int halfLifeDays, boolean decayEnabled) {
        String delta = decayEnabled
                ? "e.weight_delta * POW(0.5, GREATEST(0, TIMESTAMPDIFF(SECOND, e.observed_at, "
                        + "UTC_TIMESTAMP(6))) / 86400.0 / ?)"
                : "e.weight_delta";
        List<Object> args = new java.util.ArrayList<>();
        if (decayEnabled) {
            args.add(halfLifeDays);
        }
        args.add(user.toString());
        args.add(user.toString());
        return jdbc.query("""
                SELECT p.preference_key, p.value_json, p.source_type, p.confidence, p.weight, p.last_used_at,
                       COALESCE(SUM(%s), 0) AS evidence_weight
                FROM user_preference p
                LEFT JOIN preference_evidence e ON e.preference_id = p.id
                JOIN (SELECT preference_key, MAX(version_no) AS version_no
                        FROM user_preference
                        WHERE user_id = UUID_TO_BIN(?) AND status = 'ACTIVE'
                        GROUP BY preference_key) latest
                  ON latest.preference_key = p.preference_key AND latest.version_no = p.version_no
                WHERE p.user_id = UUID_TO_BIN(?) AND p.status = 'ACTIVE'
                GROUP BY p.id, p.preference_key, p.value_json, p.source_type, p.confidence,
                         p.weight, p.last_used_at
                ORDER BY p.weight DESC, p.last_used_at DESC, p.preference_key ASC
                """.formatted(delta), (rs, index) -> new PreferenceItemRow(rs.getString(1), rs.getString(2),
                rs.getString(3),
                rs.getBigDecimal(4) == null ? null : rs.getBigDecimal(4).doubleValue(),
                rs.getInt(5), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                rs.getBigDecimal(7) == null ? 0d : rs.getBigDecimal(7).doubleValue()),
                args.toArray());
    }

    record PreferenceItemRow(String key, String valueJson, String sourceType, Double confidence,
            int weight, Instant lastUsedAt, double evidenceWeight) {
    }

    /**
     * 追加一条偏好版本，返回新版本 ID。历史版本保留不删，召回时只取每个键的最新版本。
     */
    String upsertPreference(UUID user, String key, String valueJson, String sourceType,
            double confidence, int weight, Instant now) {
        Integer current = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no), 0) FROM user_preference
                WHERE user_id = UUID_TO_BIN(?) AND preference_key = ?
                """, Integer.class, user.toString(), key);
        int next = current == null ? 1 : current + 1;
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO user_preference
                  (id, user_id, preference_key, version_no, value_json, source_type,
                   confidence, weight, last_used_at, status)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, id, user.toString(), key, next, valueJson, sourceType,
                confidence, weight, Timestamp.from(now));
        return id;
    }

    /**
     * 追加一条偏好证据。{@code (preference_id, evidence_hash)} 唯一，重复证据按幂等忽略。
     */
    void insertEvidence(UUID preferenceId, String sourceType, UUID sourceRefId, byte[] evidenceHash,
            Instant observedAt, int weightDelta, String evidenceJson, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO preference_evidence
                      (id, preference_id, source_type, source_ref_id, evidence_hash, observed_at,
                       weight_delta, evidence_json, created_at)
                    VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, UUID_TO_BIN(?), ?, ?, ?, CAST(? AS JSON), ?)
                    """, UUID.randomUUID().toString(), preferenceId.toString(), sourceType,
                    sourceRefId.toString(), evidenceHash, Timestamp.from(observedAt), weightDelta,
                    evidenceJson, Timestamp.from(now));
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // 同一偏好版本内重复证据：幂等忽略
        }
    }

    void clearPreferences(UUID user, Instant now) {
        jdbc.update("""
                UPDATE user_preference SET status = 'CLEARED', cleared_at = ?
                WHERE user_id = UUID_TO_BIN(?) AND status = 'ACTIVE'
                """, Timestamp.from(now), user.toString());
    }
}
