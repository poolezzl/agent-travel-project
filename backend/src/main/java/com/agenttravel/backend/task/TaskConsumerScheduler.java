package com.agenttravel.backend.task;

import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TaskConsumerScheduler {

    private final TaskRepository repository;
    private final TaskQueue queue;
    private final TaskWorkerService worker;
    private final TaskProperties properties;

    public TaskConsumerScheduler(TaskRepository repository, TaskQueue queue, TaskWorkerService worker,
            TaskProperties properties) {
        this.repository = repository;
        this.queue = queue;
        this.worker = worker;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.s11.consumer-interval-ms:250}")
    public void consume() {
        if (!properties.isConsumerEnabled()) {
            return;
        }
        repository.expireLeases(Instant.now());
        List<TaskRepository.TaskRow> tasks = repository.activeTasks(properties.getConsumerBatchSize());
        for (TaskRepository.TaskRow task : tasks) {
            if (task.status() == TaskStatus.QUEUED) {
                consumeQueued(task.id());
            } else {
                worker.advance(task.id());
            }
        }
    }

    private void consumeQueued(java.util.UUID taskId) {
        List<TaskModels.QueueRecord> records = queue.claim(taskId, properties.getWorkerGroup(), worker.workerId(),
                properties.leaseDuration(), properties.getConsumerBatchSize());
        if (records.isEmpty()) {
            records = queue.poll(taskId, properties.getWorkerGroup(), worker.workerId(),
                    properties.getConsumerBatchSize());
        }
        for (TaskModels.QueueRecord record : records) {
            try {
                worker.advance(taskId);
                queue.acknowledge(taskId, properties.getWorkerGroup(), record.recordId());
            } catch (RuntimeException ignored) {
                // 未确认消息由下一次领取或租约回收继续处理。
            }
        }
    }
}
