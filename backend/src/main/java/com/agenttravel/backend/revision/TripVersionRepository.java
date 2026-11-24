package com.agenttravel.backend.revision;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only access to trip versions and their immutable plan snapshots. */
@Repository
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TripVersionRepository {

    private final JdbcTemplate jdbc;

    public TripVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TripRow findTrip(UUID tripId) {
        List<TripRow> rows = jdbc.query("""
                SELECT BIN_TO_UUID(owner_id),
                  CASE WHEN current_version_id IS NULL THEN NULL ELSE BIN_TO_UUID(current_version_id) END
                FROM trip
                WHERE id=UUID_TO_BIN(?) AND deleted_at IS NULL AND status<>'DELETED'
                """,
                (rs, rowNum) -> new TripRow(UUID.fromString(rs.getString(1)),
                        rs.getString(2) == null ? null : UUID.fromString(rs.getString(2))),
                tripId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<SummaryRow> listVersions(UUID tripId, int limit, int offset) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(v.id), v.version_no,
                  CASE WHEN v.parent_version_id IS NULL THEN NULL ELSE BIN_TO_UUID(v.parent_version_id) END,
                  v.version_status, HEX(v.etag), HEX(v.content_hash), v.schema_version, v.created_at,
                  (SELECT c.change_type FROM change_set c WHERE c.trip_version_id=v.id ORDER BY c.change_no LIMIT 1)
                FROM trip_version v
                WHERE v.trip_id=UUID_TO_BIN(?)
                ORDER BY v.version_no DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> new SummaryRow(UUID.fromString(rs.getString(1)), rs.getInt(2),
                        rs.getString(3) == null ? null : UUID.fromString(rs.getString(3)),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getTimestamp(8).toInstant(), rs.getString(9)),
                tripId.toString(), limit, offset);
    }

    public DetailRow findVersion(UUID tripId, UUID versionId) {
        List<DetailRow> rows = jdbc.query("""
                SELECT BIN_TO_UUID(v.id), v.version_no,
                  CASE WHEN v.parent_version_id IS NULL THEN NULL ELSE BIN_TO_UUID(v.parent_version_id) END,
                  v.version_status, HEX(v.etag), HEX(v.content_hash), v.schema_version, v.created_at,
                  c.change_type, c.affected_paths_json, p.plan_json, p.summary_json
                FROM trip_version v
                  LEFT JOIN plan_snapshot p ON p.trip_version_id=v.id
                  LEFT JOIN change_set c ON c.trip_version_id=v.id AND c.change_no=1
                WHERE v.id=UUID_TO_BIN(?) AND v.trip_id=UUID_TO_BIN(?)
                """,
                (rs, rowNum) -> new DetailRow(UUID.fromString(rs.getString(1)), rs.getInt(2),
                        rs.getString(3) == null ? null : UUID.fromString(rs.getString(3)),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getTimestamp(8).toInstant(), rs.getString(9), rs.getString(10),
                        rs.getString(11), rs.getString(12)),
                versionId.toString(), tripId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public record TripRow(UUID ownerId, UUID currentVersionId) {
    }

    public record SummaryRow(UUID versionId, int versionNo, UUID parentVersionId, String status, String etag,
            String contentHash, String schemaVersion, Instant createdAt, String changeType) {
    }

    public record DetailRow(UUID versionId, int versionNo, UUID parentVersionId, String status, String etag,
            String contentHash, String schemaVersion, Instant createdAt, String changeType,
            String affectedPathsJson, String planJson, String summaryJson) {
    }
}
