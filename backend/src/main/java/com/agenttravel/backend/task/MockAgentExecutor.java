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
public class MockAgentExecutor implements AgentStepExecutor {

    private final TaskProperties properties;

    public MockAgentExecutor(TaskProperties properties) {
        this.properties = properties;
    }

    @Override
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
        if ("assemble".equals(stepName)) {
            result.put("status", "partial");
            result.put("message", "模拟执行器生成的示例方案，需要配置 Python 编排服务后才能得到真实结果");
            result.put("degraded_fields", List.of("simulated"));
            result.put("plan", simulatedPlan(retrievedAt));
            return new TaskModels.WorkerResult(UUID.randomUUID(), "partial", result,
                    List.of("simulated"), agentId());
        }
        return new TaskModels.WorkerResult(UUID.randomUUID(), "ok", result, List.of(), agentId());
    }

    private Map<String, Object> simulatedPlan(Instant now) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("plan_version", "s19-plan-v1");
        plan.put("generated_at", now.toString());
        plan.put("sample_only", true);
        plan.put("notice", "模拟执行器生成的示例方案，不代表真实票价、开放时间或位置。");
        plan.put("trip", Map.of(
                "city_id", "beijing",
                "city_name", "北京",
                "start_date", "2026-11-20",
                "end_date", "2026-11-22",
                "day_count", 3,
                "party_size", 2,
                "transport_preference", "混合"));
        plan.put("summary", Map.of(
                "point_count", 0,
                "total_distance_m", 0,
                "total_transport_min", 0,
                "estimated_route_legs", 0,
                "estimated_cost", Map.of("currency", "CNY", "amount_minor", 0, "basis", "known_ticket_rules"),
                "source_completeness", 0.0,
                "degraded", true,
                "stale_fields", List.of(),
                "unknown_fields", List.of("days")));
        plan.put("days", List.of(
                simulatedDay("2026-11-20", "周五"),
                simulatedDay("2026-11-21", "周六"),
                simulatedDay("2026-11-22", "周日")));
        plan.put("quality", Map.of(
                "status", "warn",
                "checks", List.of(Map.of(
                        "code", "SIMULATED",
                        "status", "warn",
                        "message", "模拟执行器未调用真实子 Agent",
                        "fields", List.of()))));
        plan.put("conflicts", List.of());
        plan.put("confirmation", Map.of("required", false));
        plan.put("sources", List.of());
        plan.put("degraded_fields", List.of("simulated"));
        return plan;
    }

    private Map<String, Object> simulatedDay(String date, String weekday) {
        return Map.of(
                "date", date,
                "weekday", weekday,
                "city_id", "beijing",
                "weather", Map.of("status", "unknown", "date", date),
                "segments", List.of(),
                "meals", List.of(),
                "lodging", Map.of("date", date, "candidates", List.of()),
                "reservations", List.of(),
                "warnings", List.of("模拟执行器未调用真实子 Agent"));
    }
}
