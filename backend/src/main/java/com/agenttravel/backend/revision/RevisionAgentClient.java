package com.agenttravel.backend.revision;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.task.AgentServiceProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
class RevisionAgentClient {

    private final RestClient client;

    RevisionAgentClient(AgentServiceProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        Duration configured = properties.getReadTimeout();
        Duration limit = Duration.ofSeconds(30);
        factory.setReadTimeout(configured.isPositive() && configured.compareTo(limit) < 0 ? configured : limit);
        client = properties.usesPythonOrchestrator()
                ? RestClient.builder().baseUrl(properties.getBaseUrl().replaceFirst("/+$", ""))
                        .requestFactory(factory).build()
                : null;
    }

    Proposal propose(UUID base, JsonNode plan, String request, List<String> locks) {
        Proposal proposal = call("invoke", Map.of("base_version_id", base.toString(), "current_plan", plan,
                "revision_request", request, "locked_paths", locks), Proposal.class);
        if ("failed".equals(proposal.status()) || !base.toString().equals(proposal.baseVersionId())
                || proposal.intent() == null) {
            throw unavailable();
        }
        return proposal;
    }

    JsonNode apply(JsonNode plan, Proposal proposal) {
        JsonNode result = call("apply", Map.of("current_plan", plan, "changes", proposal.changes(),
                "affected_days", proposal.scope().days(), "affected_agents", proposal.scope().agents()), JsonNode.class);
        if ("failed".equals(result.path("status").asText())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVISION_PLAN_UNAVAILABLE",
                    "局部调整未通过可行性检查，请调整修改要求");
        }
        return result.path("plan");
    }

    private <T> T call(String operation, Map<String, ?> input, Class<T> type) {
        if (client == null) {
            throw unavailable();
        }
        try {
            T result = client.post().uri("/v1/agents/revision/{operation}", operation)
                    .body(Map.of("input", input)).retrieve().body(type);
            if (result == null) {
                throw unavailable();
            }
            return result;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "REVISION_AGENT_UNAVAILABLE", "修正服务暂不可用");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Proposal(String status, String intent, @JsonProperty("base_version_id") String baseVersionId,
            String answer, @JsonProperty("clarification_question") String question, String summary,
            @JsonProperty("impact_scope") Scope scope, List<RevisionModels.AtomicChange> changes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Scope(@JsonProperty("affected_days") List<Integer> days,
            @JsonProperty("affected_agents") List<String> agents) {
    }
}
