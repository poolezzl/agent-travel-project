package com.agenttravel.backend.revision;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class RevisionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RevisionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public VersionRow findVersion(UUID tripId, UUID versionId, UUID ownerId, boolean lock) {
        String sql = """
                SELECT BIN_TO_UUID(v.id),BIN_TO_UUID(v.trip_id),
                  CASE WHEN v.parent_version_id IS NULL THEN NULL ELSE BIN_TO_UUID(v.parent_version_id) END,
                  v.version_no,HEX(v.etag),HEX(v.content_hash),v.version_status,v.schema_version,
                  v.model_version,v.prompt_version,v.created_at,p.plan_json
                FROM trip_version v JOIN trip t ON t.id=v.trip_id
                  JOIN plan_snapshot p ON p.trip_version_id=v.id
                WHERE v.id=UUID_TO_BIN(?) AND v.trip_id=UUID_TO_BIN(?) AND t.owner_id=UUID_TO_BIN(?)
                """ + (lock ? " FOR UPDATE" : "");
        List<VersionRow> rows = jdbc.query(sql, (rs, n) -> new VersionRow(
                UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                rs.getString(3) == null ? null : UUID.fromString(rs.getString(3)), rs.getInt(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getTimestamp(11).toInstant(), rs.getString(12)), versionId.toString(), tripId.toString(),
                ownerId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public UUID currentVersionId(UUID tripId, UUID ownerId, boolean lock) {
        List<UUID> rows = jdbc.query("SELECT BIN_TO_UUID(current_version_id) FROM trip WHERE id=UUID_TO_BIN(?) AND owner_id=UUID_TO_BIN(?)"
                + (lock ? " FOR UPDATE" : ""), (rs, n) -> rs.getString(1) == null ? null : UUID.fromString(rs.getString(1)),
                tripId.toString(), ownerId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public VersionRow findVersionByChange(UUID tripId, UUID baseVersionId, UUID ownerId, byte[] changeHash) {
        List<VersionRow> rows = jdbc.query("""
                SELECT BIN_TO_UUID(v.id),BIN_TO_UUID(v.trip_id),
                  CASE WHEN v.parent_version_id IS NULL THEN NULL ELSE BIN_TO_UUID(v.parent_version_id) END,
                  v.version_no,HEX(v.etag),HEX(v.content_hash),v.version_status,v.schema_version,
                  v.model_version,v.prompt_version,v.created_at,p.plan_json
                FROM change_set c JOIN trip_version v ON v.id=c.trip_version_id
                  JOIN trip t ON t.id=v.trip_id JOIN plan_snapshot p ON p.trip_version_id=v.id
                WHERE v.trip_id=UUID_TO_BIN(?) AND c.base_version_id=UUID_TO_BIN(?)
                  AND c.change_hash=? AND t.owner_id=UUID_TO_BIN(?)
                ORDER BY v.created_at DESC LIMIT 1
                """, (rs, n) -> new VersionRow(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                rs.getString(3) == null ? null : UUID.fromString(rs.getString(3)), rs.getInt(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getTimestamp(11).toInstant(), rs.getString(12)), tripId.toString(), baseVersionId.toString(), changeHash,
                ownerId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int nextVersionNo(UUID tripId) {
        Integer value = jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM trip_version WHERE trip_id=UUID_TO_BIN(?)",
                Integer.class, tripId.toString());
        return value == null ? 1 : value;
    }

    public UUID insertVersion(UUID tripId, UUID parentId, int versionNo, byte[] etag, byte[] contentHash,
            String schemaVersion, String modelVersion, String promptVersion, UUID creator, String planJson,
            String changeJson, String affectedPathsJson, byte[] changeHash, String changeType, UUID baseVersionId,
            List<UUID> sourceSnapshotIds, java.time.Instant now) {
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO trip_version(id,trip_id,parent_version_id,version_no,etag,content_hash,version_status,
                  schema_version,model_version,prompt_version,created_by,created_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,'PUBLISHED',?,?,?,?,?)
                """, versionId.toString(), tripId.toString(), uuid(parentId), versionNo, etag, contentHash,
                schemaVersion, modelVersion, promptVersion, creator.toString(), Timestamp.from(now));
        jdbc.update("INSERT INTO plan_snapshot(id,trip_version_id,plan_json) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),CAST(? AS JSON))",
                UUID.randomUUID().toString(), versionId.toString(), planJson);
        jdbc.update("""
                INSERT INTO change_set(id,trip_version_id,base_version_id,change_no,change_type,affected_paths_json,
                  change_json,change_hash)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),1,?,?,CAST(? AS JSON),?)
                """, UUID.randomUUID().toString(), versionId.toString(), uuid(baseVersionId), changeType,
                affectedPathsJson, changeJson, changeHash);
        if (sourceSnapshotIds != null) {
            for (UUID sourceId : sourceSnapshotIds) {
                jdbc.update("INSERT INTO trip_version_source(trip_version_id,source_snapshot_id,field_path) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'$')",
                        versionId.toString(), sourceId.toString());
            }
        }
        return versionId;
    }

    public int advanceCurrentVersion(UUID tripId, UUID expectedVersionId, UUID newVersionId, String expectedEtag) {
        return jdbc.update("""
                UPDATE trip t JOIN trip_version v ON v.id=t.current_version_id
                SET t.current_version_id=UUID_TO_BIN(?),t.updated_at=CURRENT_TIMESTAMP(6),v.version_status='SUPERSEDED'
                WHERE t.id=UUID_TO_BIN(?) AND t.current_version_id=UUID_TO_BIN(?) AND HEX(v.etag)=?
                """, newVersionId.toString(), tripId.toString(), expectedVersionId.toString(), expectedEtag);
    }

    public String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("变更内容无法序列化", exception);
        }
    }

    private String uuid(UUID value) {
        return value == null ? null : value.toString();
    }

    public record VersionRow(UUID id, UUID tripId, UUID parentId, int versionNo, String etag, String contentHash,
            String status, String schemaVersion, String modelVersion, String promptVersion, java.time.Instant createdAt,
            String planJson) {
    }
}
