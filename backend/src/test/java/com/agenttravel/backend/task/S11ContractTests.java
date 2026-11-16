package com.agenttravel.backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class S11ContractTests {

    private final ObjectMapper json = new ObjectMapper();
    private final Path root = Path.of("..").toAbsolutePath().normalize();

    @Test
    void contractExposesOnlyTaskCommandsAndAuthenticatedProgress() throws Exception {
        String source = Files.readString(root.resolve("contracts/openapi/s11-tasks.yaml"));
        Map<String, Object> spec = new Yaml().load(source);
        Map<String, Object> paths = object(spec.get("paths"));

        assertThat(paths).containsKeys("/v1/tasks", "/v1/tasks/{taskId}",
                "/v1/tasks/{taskId}/cancel", "/v1/tasks/{taskId}/events");
        assertThat(source).contains("Idempotency-Key").contains("Last-Event-ID")
                .doesNotContain("access_token=").doesNotContain("api_key").doesNotContain("client_secret");
        Map<String, Object> create = object(object(paths.get("/v1/tasks")).get("post"));
        assertThat(list(create.get("parameters"))).isNotEmpty();
    }

    @Test
    void s19PlanReadModelIsAdditiveAndExposesNoSecrets() throws Exception {
        String source = Files.readString(root.resolve("contracts/openapi/s11-tasks.yaml"));
        Map<String, Object> spec = new Yaml().load(source);
        Map<String, Object> paths = object(spec.get("paths"));
        Map<String, Object> schemas = object(object(spec.get("components")).get("schemas"));
        Map<String, Object> planView = object(schemas.get("PlanView"));

        assertThat(paths).containsKey("/v1/tasks/{taskId}/plan");
        assertThat(list(planView.get("required"))).contains("task_id", "version_id", "plan",
                "source_metadata", "degraded_fields");
        assertThat(source).doesNotContain("access_token=").doesNotContain("api_key")
                .doesNotContain("client_secret");
    }

    @Test
    void allFrozenTaskStatesAreRepresented() throws Exception {
        Map<String, Object> spec = new Yaml().load(Files.readString(
                root.resolve("contracts/openapi/s11-tasks.yaml")));
        Map<String, Object> schemas = object(object(spec.get("components")).get("schemas"));
        Map<String, Object> properties = object(object(schemas.get("TaskView")).get("properties"));
        Map<String, Object> status = object(properties.get("status"));
        List<String> values = list(status.get("enum")).stream().map(String::valueOf).toList();

        assertThat(values).containsExactly(
                "CREATED", "BALANCE_RESERVED", "QUEUED", "PARSING", "COLLECTING", "SCHEDULING",
                "ROUTING", "ASSEMBLING", "AWAITING_CONFIRMATION", "SUCCEEDED", "PARTIAL",
                "FAILED", "CANCELLED", "REFUNDED");
    }

    @Test
    void examplesContainNoPrivatePayloadOrClientControlledEconomicFields() throws Exception {
        Map<String, Object> request = json.readValue(
                root.resolve("contracts/examples/s11/create-task-request.json").toFile(),
                new TypeReference<>() {
                });
        Map<String, Object> task = json.readValue(
                root.resolve("contracts/examples/s11/task-queued.json").toFile(),
                new TypeReference<>() {
                });

        assertThat(request).containsKeys("task_type", "plan_count", "request")
                .doesNotContainKeys("owner_id", "points", "status", "quote_id");
        assertThat(task).containsEntry("status", "QUEUED").containsEntry("reserved_points", 10);
        assertThat(Files.readString(root.resolve("contracts/examples/s11/progress-event.txt")))
                .contains("id: 3").contains("event: task-progress").doesNotContain("prompt")
                .doesNotContain("provider_response");
    }

    @Test
    void javaModelsUseTheSameSnakeCaseFieldNamesAsTheContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TaskModels.TaskView view = new TaskModels.TaskView(UUID.randomUUID(), UUID.randomUUID(), null,
                "GENERATION", TaskStatus.QUEUED, 2, 10, Instant.now(), null, Instant.now(), Instant.now(),
                false);
        TaskModels.CreateTaskRequest request = mapper.readValue(
                root.resolve("contracts/examples/s11/create-task-request.json").toFile(),
                TaskModels.CreateTaskRequest.class);
        TaskModels.EventData event = new TaskModels.EventData("QUEUED", "parse", 1, "等待", List.of());

        assertThat(mapper.writeValueAsString(view)).contains("\"task_id\"").contains("\"reserved_points\"")
                .doesNotContain("\"taskId\"").doesNotContain("\"reservedPoints\"");
        assertThat(request.taskType()).isEqualTo("GENERATION");
        assertThat(request.planCount()).isEqualTo(1);
        assertThat(mapper.writeValueAsString(event)).contains("\"step_name\"").contains("\"attempt_no\"")
                .contains("\"degraded_fields\"");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
