package com.agenttravel.backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(named = "S11_REDIS_IT", matches = "true")
@SuppressWarnings("unchecked")
class S11RedisIntegrationTests {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private String key;

    @BeforeEach
    void setUp() {
        int port = Integer.parseInt(System.getenv().getOrDefault("S11_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1", port);
        configuration.setDatabase(15);
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        key = "queue:v1:task:" + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        try {
            redis.delete(key);
        } finally {
            connectionFactory.destroy();
        }
    }

    @Test
    void realRedisDatabaseAndNamespaceRoundTrip() {
        redis.opsForValue().set(key, "synthetic");

        assertThat(redis.opsForValue().get(key)).isEqualTo("synthetic");
        assertThat(key).startsWith("queue:v1:task:").doesNotStartWith("cache:").doesNotStartWith("rate:");
    }

    @Test
    void realQueuePublishesAndReadsTheSequenceNumber() {
        RedisTaskQueue queue = new RedisTaskQueue(redis);
        UUID taskId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        String streamKey = RedisTaskQueue.key(taskId);
        try {
            queue.publish(new TaskModels.QueueMessage(taskId, outboxId, "TASK_QUEUED", "QUEUED", 42L));

            List<TaskModels.QueueRecord> records = queue.poll(taskId, "s22-it-seq", "consumer-a", 10);

            assertThat(records).hasSize(1);
            assertThat(records.get(0).message().outboxId()).isEqualTo(outboxId);
            assertThat(records.get(0).message().sequenceNo()).isEqualTo(42L);
        } finally {
            redis.opsForStream().destroyGroup(streamKey, "s22-it-seq");
            redis.delete(streamKey);
        }
    }

    @Test
    void interruptedConsumerMessageCanBeClaimedAndAcknowledged() {
        String group = "s11-it-group";
        String crashedConsumer = "s11-it-crashed";
        String recoveryConsumer = "s11-it-recovery";
        RecordId id = redis.opsForStream().add(key, Map.of("task_id", UUID.randomUUID().toString(),
                "event_type", "TASK_QUEUED"));
        redis.opsForStream().createGroup(key, ReadOffset.from("0"), group);
        try {
            List<MapRecord<String, Object, Object>> firstRead = redis.opsForStream().read(
                    Consumer.from(group, crashedConsumer),
                    StreamReadOptions.empty().count(1).block(Duration.ofSeconds(1)),
                    StreamOffset.create(key, ReadOffset.lastConsumed()));
            assertThat(firstRead).hasSize(1);
            assertThat(firstRead.get(0).getId()).isEqualTo(id);

            PendingMessagesSummary interrupted = redis.opsForStream().pending(key, group);
            assertThat(interrupted.getTotalPendingMessages()).isEqualTo(1);

            List<MapRecord<String, Object, Object>> reclaimed = redis.opsForStream().claim(key, group,
                    recoveryConsumer, Duration.ZERO, id);
            assertThat(reclaimed).hasSize(1);
            assertThat(reclaimed.get(0).getId()).isEqualTo(id);

            redis.opsForStream().acknowledge(key, group, id.getValue());
            assertThat(redis.opsForStream().pending(key, group).getTotalPendingMessages()).isZero();
        } finally {
            redis.opsForStream().destroyGroup(key, group);
        }
    }
}
