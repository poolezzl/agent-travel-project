package com.agenttravel.backend.task;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
@SuppressWarnings("unchecked")
public class RedisTaskQueue implements TaskQueue {

    private final StringRedisTemplate redis;

    public RedisTaskQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void publish(TaskModels.QueueMessage message) {
        StreamOperations<String, Object, Object> streams = redis.opsForStream();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("task_id", message.taskId().toString());
        fields.put("outbox_id", message.outboxId().toString());
        fields.put("event_type", message.eventType());
        fields.put("status", message.status());
        fields.put("sequence_no", Long.toString(message.sequenceNo()));
        streams.add(key(message.taskId()), fields);
    }

    @Override
    public List<TaskModels.QueueRecord> poll(UUID taskId, String group, String consumer, int count) {
        String key = key(taskId);
        ensureGroup(key, group);
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(Consumer.from(group, consumer),
                StreamReadOptions.empty().count(count), StreamOffset.create(key, ReadOffset.lastConsumed()));
        return records == null ? List.of() : records.stream().map(this::record).toList();
    }

    @Override
    public List<TaskModels.QueueRecord> claim(UUID taskId, String group, String consumer, Duration minIdle, int count) {
        String key = key(taskId);
        ensureGroup(key, group);
        PendingMessages pending = redis.opsForStream().pending(key, group, Range.unbounded(), count);
        List<RecordId> ids = pending.stream()
                .filter(message -> message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0)
                .map(PendingMessage::getId)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().claim(key, group, consumer, minIdle,
                ids.toArray(RecordId[]::new));
        return records == null ? List.of() : records.stream().map(this::record).toList();
    }

    @Override
    public void acknowledge(UUID taskId, String group, String recordId) {
        redis.opsForStream().acknowledge(key(taskId), group, recordId);
    }

    public static String key(UUID taskId) {
        return "queue:v1:task:" + taskId;
    }

    private void ensureGroup(String key, String group) {
        try {
            redis.opsForStream().createGroup(key, ReadOffset.from("0"), group);
        } catch (RuntimeException exception) {
            if (!contains(exception, "BUSYGROUP")) {
                throw exception;
            }
        }
    }

    private TaskModels.QueueRecord record(MapRecord<String, Object, Object> record) {
        Map<Object, Object> fields = record.getValue();
        TaskModels.QueueMessage message = new TaskModels.QueueMessage(
                UUID.fromString(String.valueOf(fields.get("task_id"))),
                UUID.fromString(String.valueOf(fields.get("outbox_id"))),
                String.valueOf(fields.get("event_type")),
                String.valueOf(fields.get("status")),
                sequenceOf(fields.get("sequence_no")));
        return new TaskModels.QueueRecord(record.getId().getValue(), message);
    }

    private long sequenceOf(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private boolean contains(Throwable error, String text) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
