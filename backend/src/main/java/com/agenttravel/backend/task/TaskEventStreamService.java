package com.agenttravel.backend.task;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;

import com.agenttravel.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TaskEventStreamService {

    private final TaskRepository repository;
    private final TaskService taskService;
    private final TaskProperties properties;
    private final S11Metrics metrics;
    private final LongFunction<SseEmitter> emitterFactory;
    private final Map<UUID, Subscriber> subscribers = new ConcurrentHashMap<>();

    @Autowired
    public TaskEventStreamService(TaskRepository repository, TaskService taskService, TaskProperties properties,
            S11Metrics metrics) {
        this(repository, taskService, properties, metrics, SseEmitter::new);
    }

    TaskEventStreamService(TaskRepository repository, TaskService taskService, TaskProperties properties,
            S11Metrics metrics, LongFunction<SseEmitter> emitterFactory) {
        this.repository = repository;
        this.taskService = taskService;
        this.properties = properties;
        this.metrics = metrics;
        this.emitterFactory = emitterFactory;
    }

    public SseEmitter subscribe(UUID userId, String role, UUID taskId, long afterSequence) {
        TaskRepository.TaskRow task = taskService.requireVisible(userId, role, taskId);
        if (afterSequence < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "事件序号不能为负数");
        }
        if (subscribers.size() >= properties.getSseMaxSubscribers()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "进度连接数已达上限");
        }
        SseEmitter emitter = emitterFactory.apply(properties.getSseTimeoutMs());
        UUID subscriberId = UUID.randomUUID();
        Subscriber subscriber = new Subscriber(subscriberId, task.id(), emitter, new AtomicLong(afterSequence));
        subscribers.put(subscriberId, subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriberId));
        emitter.onTimeout(() -> subscribers.remove(subscriberId));
        emitter.onError(error -> subscribers.remove(subscriberId));
        poll(subscriber);
        return emitter;
    }

    @Scheduled(fixedDelayString = "${app.s11.sse-poll-interval-ms:500}")
    public void pollAll() {
        if (!properties.isSseSchedulerEnabled()) {
            return;
        }
        subscribers.values().forEach(this::poll);
    }

    int subscriberCount() {
        return subscribers.size();
    }

    private void poll(Subscriber subscriber) {
        synchronized (subscriber.lock) {
            if (subscriber.closed.get()) {
                return;
            }
            try {
                long cursor = subscriber.lastSequence.get();
                List<TaskModels.TaskEventView> events = repository.eventsAfter(subscriber.taskId, cursor,
                        properties.getSseBatchSize());
                for (TaskModels.TaskEventView event : events) {
                    subscriber.emitter.send(SseEmitter.event()
                            .id(Long.toString(event.sequenceNo()))
                            .name("task-progress")
                            .data(event));
                    subscriber.lastSequence.set(event.sequenceNo());
                }
                if (!events.isEmpty()) {
                    metrics.sseReplayed(events.size());
                }
                TaskRepository.TaskRow task = repository.findTask(subscriber.taskId);
                if (task != null && task.status().terminal()
                        && repository.eventsAfter(subscriber.taskId, subscriber.lastSequence.get(), 1).isEmpty()) {
                    subscriber.close();
                    metrics.sseClosed();
                }
            } catch (IOException | RuntimeException exception) {
                subscribers.remove(subscriber.id);
                subscriber.emitter.completeWithError(exception);
            }
        }
    }

    private static final class Subscriber {
        private final UUID id;
        private final UUID taskId;
        private final SseEmitter emitter;
        private final AtomicLong lastSequence;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Object lock = new Object();

        private Subscriber(UUID id, UUID taskId, SseEmitter emitter, AtomicLong lastSequence) {
            this.id = id;
            this.taskId = taskId;
            this.emitter = emitter;
            this.lastSequence = lastSequence;
        }

        private void close() {
            if (closed.compareAndSet(false, true)) {
                emitter.complete();
            }
        }
    }
}
