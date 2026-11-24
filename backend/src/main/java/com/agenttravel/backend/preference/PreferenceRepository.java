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

    record PlanFact(String planJson, LocalDate startDate) {
    }

    List<PlanFact> findLatestPlanFacts(UUID user) {
        return jdbc.query("""
                SELECT s.plan_json, t.start_date
                FROM trip t JOIN trip_version v ON v.id = t.current_version_id
                JOIN plan_snapshot s ON s.trip_version_id = v.id
                WHERE t.owner_id = UUID_TO_BIN(?) AND t.deleted_at IS NULL
                  AND t.status IN ('ACTIVE','ARCHIVED') AND s.plan_json IS NOT NULL
                """, (rs, index) -> new PlanFact(rs.getString(1), rs.getDate(2).toLocalDate()),
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

    List<PreferenceItemRow> findActivePreferences(UUID user, int topK) {
        return jdbc.query("""
                SELECT preference_key, value_json, confidence, weight, last_used_at
                FROM user_preference
                WHERE user_id = UUID_TO_BIN(?) AND status = 'ACTIVE'
                ORDER BY weight DESC, last_used_at DESC LIMIT ?
                """, (rs, index) -> new PreferenceItemRow(rs.getString(1), rs.getString(2),
                        rs.getBigDecimal(3) == null ? null : rs.getBigDecimal(3).doubleValue(),
                        rs.getInt(4), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()),
                user.toString(), topK);
    }

    record PreferenceItemRow(String key, String valueJson, Double confidence, int weight, Instant lastUsedAt) {
    }

    void upsertPreference(UUID user, String key, String valueJson, String sourceType,
            double confidence, int weight, Instant now) {
        Integer current = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no), 0) FROM user_preference
                WHERE user_id = UUID_TO_BIN(?) AND preference_key = ?
                """, Integer.class, user.toString(), key);
        int next = current == null ? 1 : current + 1;
        jdbc.update("""
                INSERT INTO user_preference
                  (id, user_id, preference_key, version_no, value_json, source_type,
                   confidence, weight, last_used_at, status)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, UUID.randomUUID().toString(), user.toString(), key, next, valueJson, sourceType,
                confidence, weight, Timestamp.from(now));
    }

    void clearPreferences(UUID user, Instant now) {
        jdbc.update("""
                UPDATE user_preference SET status = 'CLEARED', cleared_at = ?
                WHERE user_id = UUID_TO_BIN(?) AND status = 'ACTIVE'
                """, Timestamp.from(now), user.toString());
    }
}
