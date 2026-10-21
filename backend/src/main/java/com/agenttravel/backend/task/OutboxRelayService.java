package com.agenttravel.backend.task;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class OutboxRelayService {

    private final TaskRepository repository;
    private final TaskQueue queue;
    private final TaskProperties properties;
    private final S11Metrics metrics;
    private final TaskTerminationService termination;
    private final Map<UUID, Long> nextReplayAt = new ConcurrentHashMap<>();

    public OutboxRelayService(TaskRepository repository, TaskQueue queue, TaskProperties properties,
            S11Metrics metrics, TaskTerminationService termination) {
        this.repository = repository;
        this.queue = queue;
        this.properties = properties;
        this.metrics = metrics;
        this.termination = termination;
    }

    @Transactional
    public void relayPending() {
        for (TaskRepository.OutboxRow row : repository.lockPendingOutbox(properties.getOutboxBatchSize(),
                Instant.now())) {
            try {
                queue.publish(row.message());
                repository.markOutboxPublished(row.id(), Instant.now());
                metrics.outboxPublished();
            } catch (RuntimeException exception) {
                boolean dead = row.attemptCount() + 1 >= properties.getOutboxMaxAttempts();
                repository.markOutboxFailed(row.id(), row.attemptCount(), properties.getOutboxMaxAttempts(),
                        Instant.now().plusSeconds(Math.min(30, row.attemptCount() + 1L)),
                        exception.getClass().getSimpleName());
                if (dead) {
                    metrics.outboxDead();
                    termination.failAndRefund(row.taskId(), "OUTBOX_PUBLISH_FAILED");
                }
            }
        }
    }

    public void replayPublishedForRestartedQueue() {
        long now = System.currentTimeMillis();
        long intervalMs = properties.getReplayIntervalSeconds() * 1000;
        repository.publishedForQueuedTasks(
                Instant.now().minusSeconds(properties.getReplayAfterSeconds()),
                properties.getOutboxBatchSize()).forEach(row -> {
                    long dueAt = nextReplayAt.getOrDefault(row.taskId(), 0L);
                    if (now < dueAt) {
                        return;
                    }
                    nextReplayAt.put(row.taskId(), now + intervalMs);
                    try {
                        queue.publish(row.message());
                        metrics.outboxPublished();
                    } catch (RuntimeException ignored) {
                        // 队列恢复后由下一轮再次重放。
                    }
                });
    }
}
