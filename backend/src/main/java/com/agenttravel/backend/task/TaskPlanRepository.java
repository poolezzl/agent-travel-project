package com.agenttravel.backend.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** S19 persistence for immutable trips, versions and the final plan snapshot. */
@Repository
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TaskPlanRepository {

    private final JdbcTemplate jdbc;

    public TaskPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertTrip(UUID tripId, UUID ownerId, String title, LocalDate startDate, LocalDate endDate,
            Instant now) {
        jdbc.update("""
                INSERT INTO trip(id,owner_id,title,start_date,end_date,status,created_at,updated_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,'ACTIVE',?,?)
                """,
                tripId.toString(), ownerId.toString(), title, startDate, endDate,
                Timestamp.from(now), Timestamp.from(now));
    }

    public int nextVersionNo(UUID tripId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM trip_version WHERE trip_id=UUID_TO_BIN(?)",
                Integer.class, tripId.toString());
        return value == null ? 1 : value;
    }

    public void insertVersion(UUID versionId, UUID tripId, int versionNo, byte[] etag, byte[] contentHash,
            String schemaVersion, UUID createdBy, Instant now) {
        jdbc.update("""
                INSERT INTO trip_version(
                  id,trip_id,parent_version_id,version_no,etag,content_hash,version_status,
                  schema_version,model_version,prompt_version,created_by,created_at
                ) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),NULL,?,?,?,'PUBLISHED',?,'orchestrator-v1','s19-prompt-v1',UUID_TO_BIN(?),?)
                """,
                versionId.toString(), tripId.toString(), versionNo, etag, contentHash, schemaVersion,
                createdBy.toString(), Timestamp.from(now));
    }

    public void insertPlanSnapshot(UUID snapshotId, UUID versionId, String planJson, String summaryJson,
            Instant now) {
        jdbc.update("""
                INSERT INTO plan_snapshot(id,trip_version_id,plan_json,summary_json,created_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),CAST(? AS JSON),CAST(? AS JSON),?)
                """,
                snapshotId.toString(), versionId.toString(), planJson, summaryJson, Timestamp.from(now));
    }

    public int setTripCurrentVersion(UUID tripId, UUID versionId, Instant now) {
        return jdbc.update("""
                UPDATE trip SET current_version_id=UUID_TO_BIN(?),updated_at=?
                WHERE id=UUID_TO_BIN(?)
                """, versionId.toString(), Timestamp.from(now), tripId.toString());
    }

    public VersionRow findVersionByHash(UUID tripId, byte[] contentHash) {
        List<VersionRow> rows = jdbc.query("""
                SELECT BIN_TO_UUID(id),version_no,HEX(etag),HEX(content_hash),schema_version
                FROM trip_version
                WHERE trip_id=UUID_TO_BIN(?) AND content_hash=?
                ORDER BY version_no DESC LIMIT 1
                """,
                (rs, rowNum) -> new VersionRow(UUID.fromString(rs.getString(1)), rs.getInt(2),
                        rs.getString(3), rs.getString(4), rs.getString(5)),
                tripId.toString(), contentHash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public PlanRow findPlanByTask(UUID taskId) {
        List<PlanRow> rows = jdbc.query("""
                SELECT BIN_TO_UUID(t.id),t.status,BIN_TO_UUID(tr.id),BIN_TO_UUID(v.id),v.version_no,
                  HEX(v.etag),HEX(v.content_hash),v.schema_version,p.plan_json,p.summary_json,v.created_at
                FROM planning_task t
                JOIN trip tr ON tr.id=t.trip_id
                JOIN trip_version v ON v.id=tr.current_version_id
                JOIN plan_snapshot p ON p.trip_version_id=v.id
                WHERE t.id=UUID_TO_BIN(?)
                """,
                (rs, rowNum) -> new PlanRow(UUID.fromString(rs.getString(1)), rs.getString(2),
                        UUID.fromString(rs.getString(3)), UUID.fromString(rs.getString(4)),
                        rs.getInt(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getTimestamp(11).toInstant()),
                taskId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record VersionRow(UUID id, int versionNo, String etag, String contentHash, String schemaVersion) {
    }

    public record PlanRow(UUID taskId, String taskStatus, UUID tripId, UUID versionId, int versionNo,
            String etag, String contentHash, String schemaVersion, String planJson, String summaryJson,
            Instant createdAt) {
    }
}
