package com.agenttravel.backend.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TaskRepository {

    private static final String TASK_COLUMNS = """
            SELECT BIN_TO_UUID(id),BIN_TO_UUID(owner_id),
              CASE WHEN quote_id IS NULL THEN NULL ELSE BIN_TO_UUID(quote_id) END,
              task_type,status,state_version,reserved_points,operation_deadline_at,
              confirmation_deadline_at,created_at,updated_at
            FROM planning_task
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TaskRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insertTask(UUID taskId, UUID ownerId, UUID tripId, UUID baseVersionId, String taskType,
            String idempotencyKey, byte[] requestHash, String requestJson, Instant operationDeadlineAt) {
        jdbc.update("""
                INSERT INTO planning_task(
                  id,owner_id,trip_id,base_version_id,task_type,status,idempotency_key,request_hash,
                  request_json,operation_deadline_at,state_version,created_at,updated_at
                ) VALUES(
                  UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?, 'CREATED',?,?,
                  CAST(? AS JSON),?,0,?,?
                )
                """,
                taskId.toString(), ownerId.toString(), uuid(tripId), uuid(baseVersionId), taskType,
                idempotencyKey, requestHash, requestJson, Timestamp.from(operationDeadlineAt),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    public TaskRow findTask(UUID taskId) {
        List<TaskRow> rows = jdbc.query(TASK_COLUMNS + " WHERE id=UUID_TO_BIN(?)",
                this::taskRow, taskId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public TaskRow findTaskForUpdate(UUID taskId) {
        List<TaskRow> rows = jdbc.query(TASK_COLUMNS + " WHERE id=UUID_TO_BIN(?) FOR UPDATE",
                this::taskRow, taskId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public TaskRow findTaskByIdempotency(UUID ownerId, String idempotencyKey) {
        List<TaskRow> rows = jdbc.query(TASK_COLUMNS + " WHERE owner_id=UUID_TO_BIN(?) AND idempotency_key=?",
                this::taskRow, ownerId.toString(), idempotencyKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean ownsTrip(UUID tripId, UUID ownerId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip
                WHERE id=UUID_TO_BIN(?) AND owner_id=UUID_TO_BIN(?) AND deleted_at IS NULL
                """, Integer.class, tripId.toString(), ownerId.toString());
        return count != null && count > 0;
    }

    public boolean ownsBaseVersion(UUID baseVersionId, UUID tripId, UUID ownerId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM trip_version v JOIN trip t ON t.id=v.trip_id
                WHERE v.id=UUID_TO_BIN(?) AND v.trip_id=UUID_TO_BIN(?) AND t.owner_id=UUID_TO_BIN(?)
                  AND t.deleted_at IS NULL
                """, Integer.class, baseVersionId.toString(), tripId.toString(), ownerId.toString());
        return count != null && count > 0;
    }

    public byte[] requestHash(UUID taskId) {
        return jdbc.queryForObject("SELECT request_hash FROM planning_task WHERE id=UUID_TO_BIN(?)",
                byte[].class, taskId.toString());
    }

    public int transitionStatus(UUID taskId, TaskStatus from, TaskStatus to, Long expectedVersion, Instant now) {
        String versionClause = expectedVersion == null ? "" : " AND state_version=?";
        List<Object> arguments = new ArrayList<>();
        arguments.add(to.name());
        arguments.add(Timestamp.from(now));
        arguments.add(taskId.toString());
        arguments.add(from.name());
        if (expectedVersion != null) {
            arguments.add(expectedVersion);
        }
        return jdbc.update("""
                UPDATE planning_task
                SET status=?,state_version=state_version+1,updated_at=?
                WHERE id=UUID_TO_BIN(?) AND status=?
                """ + versionClause, arguments.toArray());
    }

    public long appendEvent(UUID taskId, String eventType, TaskModels.EventData eventData) {
        jdbc.queryForObject("SELECT id FROM planning_task WHERE id=UUID_TO_BIN(?) FOR UPDATE",
                byte[].class, taskId.toString());
        Long latest = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence_no),0) FROM task_event WHERE task_id=UUID_TO_BIN(?)",
                Long.class, taskId.toString());
        long sequence = (latest == null ? 0 : latest) + 1;
        jdbc.update("""
                INSERT INTO task_event(id,task_id,sequence_no,event_type,event_json)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,CAST(? AS JSON))
                """,
                UUID.randomUUID().toString(), taskId.toString(), sequence, eventType, json(eventData));
        return sequence;
    }

    public void insertOutbox(UUID taskId, String eventType, String idempotencyKey,
            TaskModels.QueueMessage message, Instant availableAt) {
        jdbc.update("""
                INSERT INTO outbox_event(
                  id,aggregate_type,aggregate_id,event_type,idempotency_key,payload_json,available_at
                ) VALUES(UUID_TO_BIN(?),'TASK',UUID_TO_BIN(?),?,?,CAST(? AS JSON),?)
                """,
                message.outboxId().toString(), taskId.toString(), eventType, idempotencyKey, json(message),
                Timestamp.from(availableAt));
    }

    public List<OutboxRow> lockPendingOutbox(int limit, Instant now) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(id),BIN_TO_UUID(aggregate_id),event_type,payload_json,attempt_count
                FROM outbox_event
                WHERE aggregate_type='TASK' AND publish_status='PENDING' AND available_at<=?
                ORDER BY available_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> new OutboxRow(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                        rs.getString(3), parseMessage(rs.getString(4)), rs.getInt(5)),
                Timestamp.from(now), limit);
    }

    public void markOutboxPublished(UUID outboxId, Instant now) {
        jdbc.update("""
                UPDATE outbox_event
                SET publish_status='PUBLISHED',attempt_count=attempt_count+1,published_at=?,last_error=NULL
                WHERE id=UUID_TO_BIN(?) AND publish_status='PENDING'
                """, Timestamp.from(now), outboxId.toString());
    }

    public void markOutboxFailed(UUID outboxId, int currentAttempt, int maxAttempts, Instant retryAt,
            String safeError) {
        boolean dead = currentAttempt + 1 >= maxAttempts;
        jdbc.update("""
                UPDATE outbox_event
                SET attempt_count=attempt_count+1,publish_status=?,available_at=?,dead_at=?,last_error=?
                WHERE id=UUID_TO_BIN(?) AND publish_status='PENDING'
                """, dead ? "DEAD" : "PENDING", Timestamp.from(retryAt),
                dead ? Timestamp.from(Instant.now()) : null, limit(safeError, 1000), outboxId.toString());
    }

    public List<OutboxRow> publishedForQueuedTasks(Instant publishedBefore, int limit) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(o.id),BIN_TO_UUID(o.aggregate_id),o.event_type,o.payload_json,o.attempt_count
                FROM outbox_event o
                JOIN planning_task t ON t.id=o.aggregate_id
                WHERE o.aggregate_type='TASK' AND o.publish_status='PUBLISHED'
                  AND o.published_at<=? AND t.status='QUEUED'
                ORDER BY o.published_at
                LIMIT ?
                """,
                (rs, rowNum) -> new OutboxRow(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                        rs.getString(3), parseMessage(rs.getString(4)), rs.getInt(5)),
                Timestamp.from(publishedBefore), limit);
    }

    public List<TaskRow> activeTasks(int limit) {
        return jdbc.query(TASK_COLUMNS + """
                 WHERE status IN(
                   'QUEUED','PARSING','COLLECTING','SCHEDULING','ROUTING','ASSEMBLING'
                 )
                 ORDER BY updated_at
                 LIMIT ?
                """, this::taskRow, limit);
    }

    public List<TaskModels.TaskEventView> eventsAfter(UUID taskId, long afterSequence, int limit) {
        return jdbc.query("""
                SELECT sequence_no,event_type,event_json,created_at
                FROM task_event
                WHERE task_id=UUID_TO_BIN(?) AND sequence_no>?
                ORDER BY sequence_no
                LIMIT ?
                """,
                (rs, rowNum) -> event(rs.getString(2), rs.getLong(1), rs.getString(3),
                        rs.getTimestamp(4).toInstant()),
                taskId.toString(), afterSequence, limit);
    }

    public StepRow latestStep(UUID taskId, String stepName) {
        List<StepRow> rows = jdbc.query("""
                SELECT BIN_TO_UUID(id),step_name,status,attempt_no,lease_owner,lease_expires_at,heartbeat_at,
                  CASE WHEN output_result_id IS NULL THEN NULL ELSE BIN_TO_UUID(output_result_id) END
                FROM task_step
                WHERE task_id=UUID_TO_BIN(?) AND step_name=?
                ORDER BY attempt_no DESC
                LIMIT 1
                """,
                this::stepRow, taskId.toString(), stepName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public StepRow insertRunningStep(UUID taskId, String stepName, int attemptNo, String owner, Instant now,
            Instant leaseExpiresAt) {
        UUID stepId = UUID.randomUUID();
        byte[] inputHash = hash((taskId + ":" + stepName + ":" + attemptNo).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO task_step(
                  id,task_id,step_name,status,attempt_no,lease_owner,lease_expires_at,heartbeat_at,
                  input_hash,started_at
                ) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,'RUNNING',?,?,?,?,?,?)
                """,
                stepId.toString(), taskId.toString(), stepName, attemptNo, owner, Timestamp.from(leaseExpiresAt),
                Timestamp.from(now), inputHash, Timestamp.from(now));
        return latestStep(taskId, stepName);
    }

    public int heartbeat(UUID stepId, String owner, int attemptNo, Instant heartbeatAt, Instant leaseExpiresAt) {
        return jdbc.update("""
                UPDATE task_step
                SET heartbeat_at=?,lease_expires_at=?,updated_at=?
                WHERE id=UUID_TO_BIN(?) AND status='RUNNING' AND attempt_no=? AND lease_owner=?
                """,
                Timestamp.from(heartbeatAt), Timestamp.from(leaseExpiresAt), Timestamp.from(heartbeatAt),
                stepId.toString(), attemptNo, owner);
    }

    public int completeStep(UUID stepId, String owner, int attemptNo, String outcome, UUID resultId, Instant now) {
        return jdbc.update("""
                UPDATE task_step
                SET status=?,output_result_id=UUID_TO_BIN(?),finished_at=?,updated_at=?,lease_owner=NULL,
                  lease_expires_at=NULL,heartbeat_at=?
                WHERE id=UUID_TO_BIN(?) AND status='RUNNING' AND attempt_no=? AND lease_owner=?
                  AND lease_expires_at>?
                """,
                outcome, resultId.toString(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                stepId.toString(), attemptNo, owner, Timestamp.from(now));
    }

    public int failStep(UUID stepId, String owner, int attemptNo, Instant now) {
        return jdbc.update("""
                UPDATE task_step
                SET status='FAILED',finished_at=?,updated_at=?,lease_owner=NULL,lease_expires_at=NULL
                WHERE id=UUID_TO_BIN(?) AND status='RUNNING' AND attempt_no=? AND lease_owner=?
                """,
                Timestamp.from(now), Timestamp.from(now), stepId.toString(), attemptNo, owner);
    }

    public int expireStep(UUID stepId, Instant now) {
        return jdbc.update("""
                UPDATE task_step
                SET status='FAILED',finished_at=?,updated_at=?,lease_owner=NULL,lease_expires_at=NULL
                WHERE id=UUID_TO_BIN(?) AND status='RUNNING' AND lease_expires_at<=?
                """, Timestamp.from(now), Timestamp.from(now), stepId.toString(), Timestamp.from(now));
    }

    public int expireLeases(Instant now) {
        return jdbc.update("""
                UPDATE task_step
                SET status='FAILED',finished_at=?,updated_at=?,lease_owner=NULL,lease_expires_at=NULL
                WHERE status='RUNNING' AND lease_expires_at<=?
                """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    public boolean outputFresh(UUID stepId, Instant now) {
        List<String> rows = jdbc.query("""
                SELECT r.result_json
                FROM task_step s JOIN agent_result r ON r.id=s.output_result_id
                WHERE s.id=UUID_TO_BIN(?)
                """,
                (rs, rowNum) -> rs.getString(1), stepId.toString());
        if (rows.isEmpty() || rows.get(0) == null) {
            return false;
        }
        try {
            Map<String, Object> result = objectMapper.readValue(rows.get(0), new TypeReference<>() {
            });
            if (!(result.get("source") instanceof Map<?, ?> source)
                    || !"FRESH".equals(String.valueOf(source.get("freshness_status")))
                    || source.get("valid_until") == null) {
                return false;
            }
            return Instant.parse(String.valueOf(source.get("valid_until"))).isAfter(now);
        } catch (Exception exception) {
            return false;
        }
    }

    public UUID insertAgentResult(UUID taskId, UUID stepId, int attemptNo, String agentId, String requestId,
            String idempotencyKey, byte[] requestHash, String traceId, String status, Object result,
            List<String> degradedFields, Instant generatedAt) {
        UUID resultId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_result(
                  id,task_id,step_id,attempt_no,agent_id,schema_version,request_id,idempotency_key,
                  request_hash,trace_id,status,result_json,degraded_fields_json,generated_at
                ) VALUES(
                  UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,'agent-contract-v1',?,?,?,?,?,
                  CAST(? AS JSON),CAST(? AS JSON),?
                )
                """,
                resultId.toString(), taskId.toString(), stepId.toString(), attemptNo, agentId, requestId,
                idempotencyKey, requestHash, traceId, status, json(result),
                degradedFields.isEmpty() ? null : json(degradedFields), Timestamp.from(generatedAt));
        return resultId;
    }

    public UUID findAgentResultId(UUID taskId, UUID stepId, int attemptNo) {
        return jdbc.queryForObject("""
                SELECT BIN_TO_UUID(id)
                FROM agent_result
                WHERE task_id=UUID_TO_BIN(?) AND step_id=UUID_TO_BIN(?) AND attempt_no=?
                """,
                (rs, rowNum) -> UUID.fromString(rs.getString(1)),
                taskId.toString(), stepId.toString(), attemptNo);
    }

    public String taskRequestJson(UUID taskId) {
        return jdbc.queryForObject("SELECT request_json FROM planning_task WHERE id=UUID_TO_BIN(?)",
                String.class, taskId.toString());
    }

    private TaskRow taskRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TaskRow(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                rs.getString(3) == null ? null : UUID.fromString(rs.getString(3)), rs.getString(4),
                TaskStatus.valueOf(rs.getString(5)), rs.getLong(6), rs.getLong(7),
                rs.getTimestamp(8).toInstant(), timestamp(rs.getTimestamp(9)), rs.getTimestamp(10).toInstant(),
                rs.getTimestamp(11).toInstant());
    }

    private StepRow stepRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new StepRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getString(5), timestamp(rs.getTimestamp(6)), timestamp(rs.getTimestamp(7)),
                rs.getString(8) == null ? null : UUID.fromString(rs.getString(8)));
    }

    private TaskModels.TaskEventView event(String eventType, long sequence, String eventJson, Instant createdAt) {
        try {
            Map<String, Object> data = objectMapper.readValue(eventJson, new TypeReference<>() {
            });
            return new TaskModels.TaskEventView(sequence, eventType, string(data.get("status")),
                    string(data.get("step_name")), integer(data.get("attempt_no")), string(data.get("message")),
                    strings(data.get("degraded_fields")), createdAt);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("任务事件内容损坏", exception);
        }
    }

    private TaskModels.QueueMessage parseMessage(String value) {
        try {
            return objectMapper.readValue(value, TaskModels.QueueMessage.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("待发送任务消息损坏", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("任务事件无法序列化", exception);
        }
    }

    private byte[] hash(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private String uuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private Instant timestamp(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record TaskRow(UUID id, UUID ownerId, UUID quoteId, String taskType, TaskStatus status,
            long stateVersion, long reservedPoints, Instant operationDeadlineAt, Instant confirmationDeadlineAt,
            Instant createdAt, Instant updatedAt) {
    }

    public record OutboxRow(UUID id, UUID taskId, String eventType, TaskModels.QueueMessage message,
            int attemptCount) {
    }

    public record StepRow(UUID id, String stepName, String status, int attemptNo, String leaseOwner,
            Instant leaseExpiresAt, Instant heartbeatAt, UUID outputResultId) {
    }
}
