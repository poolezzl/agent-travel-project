package com.agenttravel.backend.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.agenttravel.backend.common.RequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the S19 Python orchestrator stage endpoint and returns its envelope as a worker result.
 *
 * <p>The request carries the immutable task request plus the already persisted upstream stage
 * results. No prompt, credential or full private itinerary is logged here.
 */
@Component
@Primary
@ConditionalOnExpression("'${app.agent-service.base-url:}' != ''")
public class PythonAgentStepExecutor implements AgentStepExecutor {

    private static final Map<String, List<String>> PRIOR_STEPS = Map.of(
            "parse", List.of(),
            "collect", List.of("parse"),
            "schedule", List.of("parse", "collect"),
            "route", List.of("parse", "collect", "schedule"),
            "assemble", List.of("parse", "collect", "schedule", "route"));

    private final TaskRepository repository;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public PythonAgentStepExecutor(TaskRepository repository, ObjectMapper objectMapper,
            AgentServiceProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.client = RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .requestFactory(factory)
                .build();
    }

    @Override
    public TaskModels.WorkerResult execute(UUID taskId, String stepName, int attemptNo) {
        List<String> priorSteps = PRIOR_STEPS.get(stepName);
        if (priorSteps == null) {
            throw new AgentServiceException("UNKNOWN_STEP");
        }
        String requestJson = repository.taskRequestJson(taskId);
        if (requestJson == null) {
            throw new AgentServiceException("TASK_REQUEST_MISSING");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("task_id", taskId.toString());
        body.put("trace_id", traceId());
        body.put("request_id", "s19:" + taskId + ":" + stepName + ":" + attemptNo);
        body.put("generated_at", Instant.now().toString());
        body.put("step", stepName);
        body.put("attempt_no", attemptNo);
        ObjectNode prior = body.putObject("prior");
        try {
            body.set("request", objectMapper.readTree(requestJson));
            for (String priorStep : priorSteps) {
                String resultJson = repository.latestResultJson(taskId, priorStep);
                if (resultJson != null) {
                    prior.set(priorStep, objectMapper.readTree(resultJson));
                }
            }
        } catch (JsonProcessingException exception) {
            throw new AgentServiceException("TASK_REQUEST_INVALID");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> response;
        try {
            response = client.post()
                    .uri("/v1/planning/steps/{step}", stepName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException exception) {
            throw new AgentServiceException("AGENT_SERVICE_UNAVAILABLE");
        }
        if (response == null || response.get("status") == null) {
            throw new AgentServiceException("AGENT_SERVICE_DIRTY_RESPONSE");
        }
        return new TaskModels.WorkerResult(UUID.randomUUID(), String.valueOf(response.get("status")),
                response, strings(response.get("degraded_fields")), agentId());
    }

    @Override
    public String agentId() {
        return "python-orchestrator";
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private String traceId() {
        RequestContext.Context context = RequestContext.get();
        return context == null || context.traceId() == null ? "worker-" + UUID.randomUUID() : context.traceId();
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public static class AgentServiceException extends RuntimeException {

        private final String code;

        public AgentServiceException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
