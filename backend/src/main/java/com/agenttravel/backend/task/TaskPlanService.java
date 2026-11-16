package com.agenttravel.backend.task;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Read model for the S19 final plan (PENDING-S14-01 closed by this endpoint). */
@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TaskPlanService {

    private final TaskRepository repository;
    private final TaskPlanRepository planRepository;
    private final ObjectMapper objectMapper;

    public TaskPlanService(TaskRepository repository, TaskPlanRepository planRepository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.planRepository = planRepository;
        this.objectMapper = objectMapper;
    }

    public TaskModels.PlanView get(UUID userId, String role, UUID taskId) {
        TaskRepository.TaskRow task = repository.findTask(taskId);
        if (task == null || (!task.ownerId().equals(userId) && !"ADMIN".equals(role))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONTEXT_NOT_FOUND", "任务不存在");
        }
        if (task.status() == TaskStatus.AWAITING_CONFIRMATION) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFIRMATION_REQUIRED",
                    "任务存在待确认冲突，确认前不创建版本");
        }
        if (task.status() != TaskStatus.SUCCEEDED && task.status() != TaskStatus.PARTIAL) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_NOT_READY", "任务尚未生成可读取的行程版本");
        }
        TaskPlanRepository.PlanRow row = planRepository.findPlanByTask(taskId);
        if (row == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_NOT_READY", "任务尚未生成可读取的行程版本");
        }
        Map<String, Object> plan = readMap(row.planJson());
        Map<String, Object> summary = readMap(row.summaryJson());
        return new TaskModels.PlanView(row.taskId(), row.taskStatus(), row.tripId(), row.versionId(),
                row.versionNo(), row.etag(), row.contentHash(), row.schemaVersion(), row.createdAt(),
                plan, summary, sources(plan), degraded(plan));
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PLAN_CORRUPTED", "行程版本内容损坏");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sources(Map<String, Object> plan) {
        if (!(plan.get("sources") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private List<String> degraded(Map<String, Object> plan) {
        if (!(plan.get("degraded_fields") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
