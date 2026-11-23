package com.agenttravel.backend.traveltools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
class ExportService {
    private static final int MAX_ATTEMPTS = 3;
    private static final String JOB = """
            SELECT BIN_TO_UUID(e.id),BIN_TO_UUID(e.trip_version_id),BIN_TO_UUID(e.user_id),e.export_format,
                e.status,e.expires_at,e.attempt_count,o.object_key,o.size_bytes,o.sha256,
                COALESCE(o.owner_id=e.user_id AND o.status='AVAILABLE' AND o.storage_backend='LOCAL',FALSE)
            FROM export_job e JOIN trip_version v ON v.id=e.trip_version_id JOIN trip t ON t.id=v.trip_id
            JOIN user_account u ON u.id=e.user_id
            LEFT JOIN object_metadata o ON o.id=e.object_metadata_id
            WHERE t.owner_id=e.user_id AND t.deleted_at IS NULL AND t.status<>'DELETED'
                AND u.status='ACTIVE' AND u.deleted_at IS NULL
                AND v.version_status IN ('PUBLISHED','SUPERSEDED')
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final AuditRepository audit;
    private final ExportFiles files;
    private final ExportRenderer renderer;
    private final SecretKeySpec signingKey;

    ExportService(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json, Clock clock, AuditRepository audit,
            ExportFiles files, ExportRenderer renderer, @Value("${app.security.jwt-secret}") String secret) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.audit = audit;
        this.files = files;
        this.renderer = renderer;
        this.signingKey = new SecretKeySpec(digest(("trip-export-v1:" + secret).getBytes(StandardCharsets.UTF_8)),
                "HmacSHA256");
    }

    View create(UUID owner, UUID trip, UUID version, ExportRenderer.Format format, String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请求编号无效");
        }
        return tx.execute(status -> {
            requireVersion(owner, trip, version, false);
            UUID id = UUID.randomUUID();
            byte[] keyHash = digest(key.getBytes(StandardCharsets.US_ASCII));
            jdbc.update("""
                    INSERT INTO export_job(id,user_id,trip_version_id,export_format,request_key_hash,available_at)
                    VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?)
                    ON DUPLICATE KEY UPDATE id=id
                    """, id.toString(), owner.toString(), version.toString(), format.name(), keyHash,
                    Timestamp.from(clock.instant()));
            Job job = find(JOB + " AND e.user_id=UUID_TO_BIN(?) AND e.request_key_hash=? FOR UPDATE OF e",
                    owner.toString(), keyHash);
            if (job == null || !job.version().equals(version) || job.format() != format) {
                throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "请求编号已用于其他导出");
            }
            if (job.id().equals(id)) {
                audit.write(owner, "EXPORT_CREATE", "EXPORT_JOB", id, "SUCCESS", null);
            }
            return view(job, false);
        });
    }

    View get(UUID owner, UUID id) {
        return view(owned(owner, id), true);
    }

    Download download(UUID owner, UUID id, long expires, String signature) {
        try {
            Job job = owned(owner, id);
            long now = clock.instant().getEpochSecond();
            if (!"SUCCEEDED".equals(job.status()) || !job.available() || job.expires() == null
                    || !job.expires().isAfter(clock.instant()) || expires > job.expires().getEpochSecond()
                    || expires <= now || expires > now + 300 || signature == null || signature.length() != 43
                    || !MessageDigest.isEqual(signature.getBytes(StandardCharsets.US_ASCII),
                            sign(job, expires).getBytes(StandardCharsets.US_ASCII))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "EXPORT_LINK_INVALID", "下载授权无效或已过期");
            }
            byte[] bytes = files.read(job.key(), job.size());
            if (!MessageDigest.isEqual(digest(bytes), job.sha())) {
                throw new IOException("导出文件校验失败");
            }
            audit.write(owner, "EXPORT_DOWNLOAD", "EXPORT_JOB", id, "SUCCESS", null);
            return new Download(job.format(), bytes);
        } catch (ApiException exception) {
            audit.write(owner, "EXPORT_DOWNLOAD", "EXPORT_JOB", id, "DENIED", exception.code());
            throw exception;
        } catch (IOException exception) {
            audit.write(owner, "EXPORT_DOWNLOAD", "EXPORT_JOB", id, "FAILURE", "RESOURCE_NOT_FOUND");
            throw missing();
        }
    }

    @Scheduled(fixedDelayString = "${app.s26.export-delay-ms:5000}", scheduler = "travelToolsScheduler")
    void scheduledWork() {
        if (workEnabled) {
            runExports();
        }
    }

    @Value("${app.s26.scheduling-enabled:true}")
    private boolean workEnabled;

    void runExports() {
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE export_job SET status='FAILED',updated_at=?
                WHERE status='RUNNING' AND available_at<=? AND attempt_count>=?
                """, Timestamp.from(now), Timestamp.from(now), MAX_ATTEMPTS);
        for (int i = 0; i < 10; i++) {
            Job job = tx.execute(status -> {
                Job selected = find(JOB + """
                         AND e.request_key_hash IS NOT NULL AND e.status IN ('PENDING','RUNNING')
                         AND e.available_at<=? AND e.attempt_count<?
                         ORDER BY e.available_at,e.id LIMIT 1 FOR UPDATE OF e SKIP LOCKED
                        """, Timestamp.from(now), MAX_ATTEMPTS);
                if (selected != null) {
                    jdbc.update("""
                            UPDATE export_job SET status='RUNNING',attempt_count=attempt_count+1,
                                available_at=?,updated_at=? WHERE id=UUID_TO_BIN(?)
                            """, Timestamp.from(clock.instant().plusSeconds(300)), Timestamp.from(clock.instant()),
                            selected.id().toString());
                }
                return selected;
            });
            if (job == null) {
                break;
            }
            render(job);
        }
    }

    private void render(Job job) {
        String key = null;
        try {
            Plan plan = jdbc.queryForObject("""
                    SELECT COALESCE(JSON_UNQUOTE(JSON_EXTRACT(p.plan_json,'$.trip.city_name')),t.title),p.plan_json
                    FROM plan_snapshot p JOIN trip_version v ON v.id=p.trip_version_id JOIN trip t ON t.id=v.trip_id
                    WHERE p.trip_version_id=UUID_TO_BIN(?)
                    """, (rs, n) -> new Plan(rs.getString(1), rs.getString(2)), job.version().toString());
            byte[] bytes = renderer.render(job.format(), plan.title(), json.readTree(plan.json()),
                    job.version().toString(), job.id().toString(), clock.instant());
            key = files.store(job.format(), bytes);
            String stored = key;
            boolean published = Boolean.TRUE.equals(tx.execute(status -> {
                Job current = find(JOB + " AND e.id=UUID_TO_BIN(?) FOR UPDATE OF e", job.id().toString());
                if (current == null || !"RUNNING".equals(current.status()) || current.attempts() != job.attempts() + 1) {
                    return false;
                }
                Instant now = clock.instant();
                Timestamp expires = Timestamp.from(now.plusSeconds(86400));
                UUID objectId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO object_metadata(id,owner_id,object_key,storage_backend,mime_type,size_bytes,
                            sha256,status,expires_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,'LOCAL',?,?,?,'AVAILABLE',?)
                        """, objectId.toString(), job.owner().toString(), stored, job.format().mime,
                        bytes.length, digest(bytes), expires);
                jdbc.update("""
                        UPDATE export_job SET status='SUCCEEDED',object_metadata_id=UUID_TO_BIN(?),
                            expires_at=?,completed_at=?,updated_at=? WHERE id=UUID_TO_BIN(?)
                        """, objectId.toString(), expires, Timestamp.from(now), Timestamp.from(now), job.id().toString());
                return true;
            }));
            if (!published) {
                files.delete(key);
            }
        } catch (IOException | RuntimeException exception) {
            // 提交结果不明时保留文件，由清理任务核对数据库引用。
            LoggerFactory.getLogger(getClass()).warn("导出处理失败，任务编号 {}", job.id());
            int attempt = job.attempts() + 1;
            Instant now = clock.instant();
            jdbc.update("""
                    UPDATE export_job SET status=?,updated_at=?,available_at=?
                    WHERE id=UUID_TO_BIN(?) AND status='RUNNING' AND attempt_count=?
                    """, attempt >= MAX_ATTEMPTS ? "FAILED" : "PENDING", Timestamp.from(now),
                    Timestamp.from(now.plusSeconds(30L * attempt)), job.id().toString(), attempt);
        }
    }

    @Scheduled(fixedDelayString = "${app.s26.cleanup-delay-ms:3600000}", scheduler = "travelToolsScheduler")
    void scheduledCleanup() {
        if (workEnabled) {
            cleanup();
        }
    }

    void cleanup() {
        Instant now = clock.instant();
        Timestamp cutoff = Timestamp.from(now);
        jdbc.update("""
                UPDATE export_job SET status='EXPIRED',download_token_hash=NULL,updated_at=?
                WHERE status='SUCCEEDED' AND request_key_hash IS NOT NULL AND expires_at<=?
                """, cutoff, cutoff);
        jdbc.update("""
                UPDATE object_metadata o JOIN export_job e ON e.object_metadata_id=o.id
                SET o.status='EXPIRED',o.updated_at=? WHERE e.status='EXPIRED' AND e.request_key_hash IS NOT NULL
                    AND o.status='AVAILABLE' AND o.owner_id=e.user_id AND o.storage_backend='LOCAL'
                """, cutoff);
        for (String key : jdbc.queryForList("""
                SELECT DISTINCT o.object_key FROM object_metadata o JOIN export_job e ON e.object_metadata_id=o.id
                WHERE o.status='EXPIRED' AND e.request_key_hash IS NOT NULL
                    AND o.storage_backend='LOCAL' AND o.owner_id=e.user_id
                """, String.class)) {
            try {
                files.delete(key);
                jdbc.update("UPDATE object_metadata SET status='DELETED',updated_at=? WHERE object_key=? AND status='EXPIRED'",
                        cutoff, key);
            } catch (IOException exception) {
                LoggerFactory.getLogger(getClass()).warn("过期导出文件清理失败");
            }
        }
        try {
            for (String key : files.olderThan(now.minusSeconds(86400))) {
                if (Boolean.FALSE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM object_metadata WHERE object_key=? AND status<>'DELETED')
                        """, Boolean.class, key))) {
                    files.delete(key);
                }
            }
        } catch (IOException exception) {
            LoggerFactory.getLogger(getClass()).warn("孤立导出文件清理失败");
        }
    }

    void requireVersion(UUID owner, UUID trip, UUID version, boolean current) {
        Boolean owned = jdbc.query("""
                SELECT t.current_version_id=v.id FROM trip t JOIN trip_version v ON v.trip_id=t.id
                JOIN plan_snapshot p ON p.trip_version_id=v.id
                WHERE t.id=UUID_TO_BIN(?) AND v.id=UUID_TO_BIN(?) AND t.owner_id=UUID_TO_BIN(?)
                    AND t.deleted_at IS NULL AND t.status<>'DELETED'
                    AND v.version_status IN ('PUBLISHED','SUPERSEDED') FOR UPDATE OF t
                """, rs -> rs.next() ? rs.getBoolean(1) : null, trip.toString(), version.toString(), owner.toString());
        if (owned == null) {
            throw missing();
        }
        if (current && !owned) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", "行程版本已更新");
        }
    }

    private Job owned(UUID owner, UUID id) {
        Job job = find(JOB + " AND e.id=UUID_TO_BIN(?) AND e.user_id=UUID_TO_BIN(?)", id.toString(), owner.toString());
        if (job == null) {
            throw missing();
        }
        return job;
    }

    private Job find(String sql, Object... params) {
        return jdbc.query(sql, rs -> {
            if (!rs.next()) {
                return null;
            }
            Timestamp expires = rs.getTimestamp(6);
            return new Job(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                    UUID.fromString(rs.getString(3)), ExportRenderer.Format.valueOf(rs.getString(4)),
                    rs.getString(5), expires == null ? null : expires.toInstant(), rs.getInt(7), rs.getString(8),
                    rs.getLong(9), rs.getBytes(10), rs.getBoolean(11));
        }, params);
    }

    private View view(Job job, boolean link) {
        String state = job.status();
        String url = null;
        Instant now = clock.instant();
        if ("SUCCEEDED".equals(state) && job.expires() != null && !job.expires().isAfter(now)) {
            state = "EXPIRED";
        }
        if (link && "SUCCEEDED".equals(state) && job.available() && job.expires() != null) {
            long expires = Math.min(now.getEpochSecond() + 300, job.expires().getEpochSecond());
            if (expires > now.getEpochSecond()) {
                url = "/api/v1/exports/" + job.id() + "/download?expires=" + expires + "&signature=" + sign(job, expires);
            }
        }
        return new View(job.id(), job.version(), job.format(), state, job.expires(), url);
    }

    private String sign(Job job, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            String input = job.id() + ":" + job.owner() + ":" + job.key() + ":" + expires;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private record Job(UUID id, UUID version, UUID owner, ExportRenderer.Format format, String status,
            Instant expires, int attempts, String key, long size, byte[] sha, boolean available) {
    }

    private record Plan(String title, String json) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record View(@JsonProperty("export_id") UUID exportId, @JsonProperty("version_id") UUID versionId,
            ExportRenderer.Format format, String status, @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("download_url") String downloadUrl) {
    }

    record Download(ExportRenderer.Format format, byte[] bytes) {
    }
}
