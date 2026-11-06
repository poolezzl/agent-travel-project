package com.agenttravel.backend.task;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class MockAgentExecutor {

    private final TaskProperties properties;

    public MockAgentExecutor(TaskProperties properties) {
        this.properties = properties;
    }

    public TaskModels.WorkerResult execute(UUID taskId, String stepName, int attemptNo) {
        Instant retrievedAt = Instant.now();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_type", "system");
        source.put("source_record_id", "mock:" + stepName + ":" + attemptNo);
        source.put("retrieved_at", retrievedAt.toString());
        source.put("valid_until", retrievedAt.plusSeconds(properties.getReconnectSourceFreshnessSeconds()).toString());
        source.put("freshness_status", "FRESH");
        source.put("is_estimated", true);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("simulated", true);
        result.put("summary", "模拟步骤 " + stepName + " 已完成，不代表真实旅行事实");
        result.put("source", source);
        return new TaskModels.WorkerResult(UUID.randomUUID(), "ok", result, List.of());
    }
}
