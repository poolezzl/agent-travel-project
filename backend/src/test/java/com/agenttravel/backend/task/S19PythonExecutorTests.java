package com.agenttravel.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class S19PythonExecutorTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsTheImmutableRequestAndPriorStagesAndReadsTheEnvelope() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        String body = mapper.writeValueAsString(Map.of(
                "schema_version", "agent-contract-v1",
                "task_id", "task-1",
                "status", "ok",
                "degraded_fields", new String[] {},
                "plan", Map.of("plan_version", "s19-plan-v1")));
        startServer(exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        TaskRepository repository = mock(TaskRepository.class);
        UUID taskId = UUID.randomUUID();
        when(repository.taskRequestJson(taskId)).thenReturn("{\"city\":\"北京\"}");
        when(repository.latestResultJson(taskId, "parse")).thenReturn("{\"status\":\"ok\"}");
        when(repository.latestResultJson(taskId, "collect")).thenReturn(null);
        when(repository.latestResultJson(taskId, "schedule")).thenReturn(null);

        PythonAgentStepExecutor executor = new PythonAgentStepExecutor(repository, mapper, properties());
        TaskModels.WorkerResult result = executor.execute(taskId, "assemble", 2);

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.agentId()).isEqualTo("python-orchestrator");
        assertThat(result.result()).containsKey("plan");
        assertThat(received.get()).contains("\"step\":\"assemble\"").contains("\"attempt_no\":2")
                .contains("\"prior\":{\"parse\":{\"status\":\"ok\"}}").contains("北京");
    }

    @Test
    void upstreamFailureHasAStableCodeAndIsNotSilentlySwallowed() throws Exception {
        startServer(exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        TaskRepository repository = mock(TaskRepository.class);
        UUID taskId = UUID.randomUUID();
        when(repository.taskRequestJson(taskId)).thenReturn("{}");

        PythonAgentStepExecutor executor = new PythonAgentStepExecutor(repository, mapper, properties());

        assertThatThrownBy(() -> executor.execute(taskId, "parse", 1))
                .isInstanceOf(PythonAgentStepExecutor.AgentServiceException.class)
                .extracting(exception -> ((PythonAgentStepExecutor.AgentServiceException) exception).code())
                .isEqualTo("AGENT_SERVICE_UNAVAILABLE");
    }

    @Test
    void unknownStepIsRejectedBeforeAnyNetworkCall() throws Exception {
        startServer(exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        TaskRepository repository = mock(TaskRepository.class);
        PythonAgentStepExecutor executor = new PythonAgentStepExecutor(repository, mapper, properties());

        assertThatThrownBy(() -> executor.execute(UUID.randomUUID(), "teleport", 1))
                .isInstanceOf(PythonAgentStepExecutor.AgentServiceException.class)
                .extracting(exception -> ((PythonAgentStepExecutor.AgentServiceException) exception).code())
                .isEqualTo("UNKNOWN_STEP");
    }

    private AgentServiceProperties properties() {
        AgentServiceProperties properties = new AgentServiceProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private void startServer(ThrowingHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (RuntimeException exception) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
        server.start();
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
