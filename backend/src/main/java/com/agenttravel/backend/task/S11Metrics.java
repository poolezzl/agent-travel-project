package com.agenttravel.backend.task;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class S11Metrics {

    private final Counter tasksCreated;
    private final Counter tasksCancelled;
    private final Counter outboxPublished;
    private final Counter outboxDead;
    private final Counter staleResults;
    private final Counter leasesRecovered;
    private final Counter sseReplayed;
    private final Counter sseClosed;

    public S11Metrics(MeterRegistry registry) {
        tasksCreated = registry.counter("s11.tasks.created");
        tasksCancelled = registry.counter("s11.tasks.cancelled");
        outboxPublished = registry.counter("s11.outbox.published");
        outboxDead = registry.counter("s11.outbox.dead");
        staleResults = registry.counter("s11.worker.stale-results");
        leasesRecovered = registry.counter("s11.worker.leases-recovered");
        sseReplayed = registry.counter("s11.sse.replayed-events");
        sseClosed = registry.counter("s11.sse.terminal-closes");
    }

    public void taskCreated() {
        tasksCreated.increment();
    }

    public void taskCancelled() {
        tasksCancelled.increment();
    }

    public void outboxPublished() {
        outboxPublished.increment();
    }

    public void outboxDead() {
        outboxDead.increment();
    }

    public void staleResult() {
        staleResults.increment();
    }

    public void leaseRecovered() {
        leasesRecovered.increment();
    }

    public void sseReplayed(long count) {
        sseReplayed.increment(count);
    }

    public void sseClosed() {
        sseClosed.increment();
    }

    public double count(String name) {
        return switch (name) {
            case "tasks-created" -> tasksCreated.count();
            case "tasks-cancelled" -> tasksCancelled.count();
            case "outbox-published" -> outboxPublished.count();
            case "outbox-dead" -> outboxDead.count();
            case "stale-results" -> staleResults.count();
            case "leases-recovered" -> leasesRecovered.count();
            case "sse-replayed" -> sseReplayed.count();
            case "sse-closed" -> sseClosed.count();
            default -> throw new IllegalArgumentException("未知指标");
        };
    }
}
