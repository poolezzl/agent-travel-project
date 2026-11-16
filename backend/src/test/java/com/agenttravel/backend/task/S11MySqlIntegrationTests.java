package com.agenttravel.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.agenttravel.backend.auth.JwtTokenService;
import com.agenttravel.backend.common.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@EnabledIfEnvironmentVariable(named = "S11_IT", matches = "true")
@SpringBootTest(properties = {
        "app.security.jwt-secret=test-only-jwt-signing-key-32-bytes",
        "app.s11.relay-enabled=false",
        "app.s11.consumer-enabled=false",
        "app.s11.heartbeat-scheduler-enabled=false",
        "app.s11.sse-scheduler-enabled=false"
})
@AutoConfigureMockMvc
class S11MySqlIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskWorkerClaimService claimService;
    @Autowired
    private MockAgentExecutor mockAgent;
    @Autowired
    private TaskTerminationService termination;
    @Autowired
    private OutboxRelayService relay;
    @Autowired
    private TaskRepository repository;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private TaskQueue taskQueue;

    private UUID userId;
    private UUID accountId;
    private String login;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        login = "s11-it-" + userId;
        jdbc.update("""
                INSERT INTO user_account(id,username,password_hash,role,status)
                VALUES(UUID_TO_BIN(?),?,'synthetic-hash','USER','ACTIVE')
                """, userId.toString(), login);
        jdbc.update("""
                INSERT INTO points_account(id,user_id,balance_points,state_version)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),100,0)
                """, accountId.toString(), userId.toString());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_event WHERE actor_user_id=UUID_TO_BIN(?)", userId.toString());
        jdbc.update("DELETE FROM points_ledger WHERE user_id=UUID_TO_BIN(?)", userId.toString());
        jdbc.update("UPDATE planning_task SET quote_id=NULL WHERE owner_id=UUID_TO_BIN(?)", userId.toString());
        jdbc.update("UPDATE points_quote SET task_id=NULL WHERE user_id=UUID_TO_BIN(?)", userId.toString());
        jdbc.update("DELETE FROM points_quote WHERE user_id=UUID_TO_BIN(?)", userId.toString());
        jdbc.update("DELETE FROM task_event WHERE task_id IN(SELECT id FROM planning_task WHERE owner_id=UUID_TO_BIN(?))",
                userId.toString());
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id IN(SELECT id FROM planning_task WHERE owner_id=UUID_TO_BIN(?))",
                userId.toString());
        jdbc.update("UPDATE task_step SET output_result_id=NULL WHERE task_id IN(SELECT id FROM planning_task WHERE owner_id=UUID_TO_BIN(?))",
                userId.toString());
        jdbc.update("DELETE FROM agent_result WHERE task_id IN(SELECT id FROM planning_task WHERE owner_id=UUID_TO_BIN(?))",
                userId.toString());
        jdbc.update("DELETE FROM task_step WHERE task_id IN(SELECT id FROM planning_task WHERE owner_id=UUID_TO_BIN(?))",
                userId.toString());
        jdbc.update("DELETE FROM planning_task WHERE owner_id=UUID_TO_BIN(?)", userId.toString());
        jdbc.update("""
                DELETE FROM plan_snapshot WHERE trip_version_id IN(
                  SELECT id FROM trip_version WHERE created_by=UUID_TO_BIN(?)
                )
                """, userId.toString());
        jdbc.update("UPDATE trip SET current_version_id=NULL WHERE owner_id=UUID_TO_BIN(?)",
                userId.toString());
        jdbc.update("DELETE FROM trip_version WHERE created_by=UUID_TO_BIN(?)", userId.toString());
        jdbc.update("DELETE FROM trip WHERE owner_id=UUID_TO_BIN(?)", userId.toString());
        jdbc.update("DELETE FROM user_session WHERE user_id=UUID_TO_BIN(?)", userId.toString());
        jdbc.update("DELETE FROM points_account WHERE user_id=UUID_TO_BIN(?)", userId.toString());
        jdbc.update("DELETE FROM user_account WHERE id=UUID_TO_BIN(?)", userId.toString());
    }

    @Test
    void createTaskReserveAndOutboxAreAtomicAndWorkerUsesShortTransactions() throws Exception {
        TaskModels.TaskView created = createTask(null, null, "GENERATION");

        assertThat(created.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(created.reservedPoints()).isEqualTo(10);
        assertThat(balance()).isEqualTo(90);
        assertThat(jdbc.queryForObject("SELECT status FROM points_quote WHERE task_id=UUID_TO_BIN(?)",
                String.class, created.taskId().toString())).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_ledger WHERE task_id=UUID_TO_BIN(?)",
                Integer.class, created.taskId().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_event
                WHERE aggregate_id=UUID_TO_BIN(?) AND publish_status='PENDING'
                """, Integer.class, created.taskId().toString())).isEqualTo(1);

        runMockWorkflow(created.taskId());

        // S19 assemble commits an immutable version from the mock plan and confirms the reserve.
        assertThat(repository.findTask(created.taskId()).status()).isEqualTo(TaskStatus.PARTIAL);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_version WHERE trip_id=(SELECT trip_id FROM planning_task WHERE id=UUID_TO_BIN(?))",
                Integer.class, created.taskId().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_snapshot",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT sequence_no) FROM task_event WHERE task_id=UUID_TO_BIN(?)",
                Integer.class, created.taskId().toString())).isGreaterThanOrEqualTo(10);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_step WHERE task_id=UUID_TO_BIN(?) AND status IN('SUCCEEDED','PARTIAL')
                """, Integer.class, created.taskId().toString())).isEqualTo(5);
        assertThat(balance()).isEqualTo(90);

        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_session(id,user_id,refresh_token_hash,issued_at,expires_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?)
                """, sessionId.toString(), userId.toString(), bytes(3), Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().plusSeconds(600)));
        String token = tokens.createAccessToken(userId, "USER", sessionId, Instant.now().getEpochSecond());
        mockMvc.perform(get("/api/v1/tasks/{taskId}/plan", created.taskId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.version_no").value(1))
                .andExpect(jsonPath("$.plan.plan_version").value("s19-plan-v1"));

        TaskRepository.TaskRow beforeCancel = repository.findTask(created.taskId());
        TaskModels.TaskView cancelled = taskService.cancel(userId, "USER", created.taskId(),
                new TaskModels.CancelTaskRequest(beforeCancel.stateVersion()), "cancel-it");
        assertThat(cancelled.status()).isEqualTo(TaskStatus.PARTIAL);
        assertThat(balance()).isEqualTo(90);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_ledger WHERE user_id=UUID_TO_BIN(?)",
                Integer.class, userId.toString())).isEqualTo(2);
    }

    @Test
    void insufficientPointsRollsBackTaskQuoteReserveAndOutbox() {
        jdbc.update("UPDATE points_account SET balance_points=5 WHERE user_id=UUID_TO_BIN(?)", userId.toString());

        assertThatThrownBy(() -> taskService.create(userId,
                new TaskModels.CreateTaskRequest("GENERATION", null, null, 1, Map.of("city", "北京")),
                "rollback-key"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("INSUFFICIENT_POINTS");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM planning_task WHERE owner_id=UUID_TO_BIN(?)",
                Integer.class, userId.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_quote WHERE user_id=UUID_TO_BIN(?)",
                Integer.class, userId.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_ledger WHERE user_id=UUID_TO_BIN(?)",
                Integer.class, userId.toString())).isZero();
        assertThat(balance()).isEqualTo(5);
    }

    @Test
    void failedExecutorResultDoesNotAdvanceTaskAndNextClaimUsesHigherAttempt() {
        TaskModels.TaskView created = createTask(null, null, "GENERATION");
        TaskWorkerClaimService.ClaimResult first = claimService.claim(created.taskId(), "it-worker");
        assertThat(first.hasWork()).isTrue();
        TaskWorkerClaimService.Claim firstClaim = first.claim();

        assertThat(claimService.commit(firstClaim, new TaskModels.WorkerResult(UUID.randomUUID(), "failed",
                Map.of("error_code", "MOCK_FAILED"), List.of("source_unavailable")), "it-trace")).isFalse();

        TaskRepository.TaskRow task = repository.findTask(created.taskId());
        assertThat(task.status()).isEqualTo(TaskStatus.PARSING);
        TaskRepository.StepRow failedStep = repository.latestStep(created.taskId(), firstClaim.stepName());
        assertThat(failedStep.status()).isEqualTo("FAILED");
        assertThat(failedStep.attemptNo()).isEqualTo(1);
        assertThat(failedStep.outputResultId()).isNotNull();

        TaskWorkerClaimService.ClaimResult retry = claimService.claim(created.taskId(), "it-worker");
        assertThat(retry.hasWork()).isTrue();
        assertThat(retry.claim().attemptNo()).isEqualTo(2);
        assertThat(repository.findTask(created.taskId()).status()).isEqualTo(TaskStatus.PARSING);
    }

    @Test
    void taskTripAndBaseVersionMustBelongToAuthenticatedOwner() {
        UUID tripId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO trip(id,owner_id,title,start_date,end_date,status)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?, ?,?,'DRAFT')
                """, tripId.toString(), userId.toString(), "集成测试行程", LocalDate.now(),
                LocalDate.now().plusDays(2));
        jdbc.update("""
                INSERT INTO trip_version(
                  id,trip_id,version_no,etag,content_hash,version_status,schema_version,created_by
                ) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),1,?,?,'DRAFT','agent-contract-v1',UUID_TO_BIN(?))
                """, versionId.toString(), tripId.toString(), bytes(1), bytes(2), userId.toString());

        TaskModels.TaskView created = createTask(tripId, versionId, "REVISION");
        assertThat(created.status()).isEqualTo(TaskStatus.QUEUED);

        UUID otherUser = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_account(id,username,password_hash,role,status)
                VALUES(UUID_TO_BIN(?),?,'synthetic-hash','USER','ACTIVE')
                """, otherUser.toString(), "other-" + otherUser);
        try {
            assertThatThrownBy(() -> taskService.create(otherUser,
                    new TaskModels.CreateTaskRequest("REVISION", tripId, versionId, 1, Map.of("city", "北京")),
                    "foreign-key"))
                    .isInstanceOf(ApiException.class)
                    .extracting(exception -> ((ApiException) exception).code())
                    .isEqualTo("CONTEXT_NOT_FOUND");
        } finally {
            jdbc.update("DELETE FROM points_account WHERE user_id=UUID_TO_BIN(?)", otherUser.toString());
            jdbc.update("DELETE FROM user_account WHERE id=UUID_TO_BIN(?)", otherUser.toString());
        }
    }

    @Test
    void permanentOutboxFailureEndsTaskAndRefundsOnce() {
        TaskModels.TaskView created = createTask(null, null, "GENERATION");
        TaskModels.QueueMessage message = new TaskModels.QueueMessage(created.taskId(), UUID.randomUUID(),
                "TASK_QUEUED", "QUEUED");
        doThrow(new IllegalStateException("同步失败")).when(taskQueue).publish(any());

        for (int attempt = 0; attempt < 5; attempt++) {
            jdbc.update("UPDATE outbox_event SET available_at=? WHERE aggregate_id=UUID_TO_BIN(?)",
                    Timestamp.from(Instant.now().minusSeconds(1)), created.taskId().toString());
            relay.relayPending();
            if ("REFUNDED".equals(repository.findTask(created.taskId()).status().name())) {
                break;
            }
        }

        assertThat(repository.findTask(created.taskId()).status()).isEqualTo(TaskStatus.REFUNDED);
        assertThat(balance()).isEqualTo(100);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM points_ledger WHERE user_id=UUID_TO_BIN(?) AND entry_type='REFUND'
                """, Integer.class, userId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT publish_status FROM outbox_event WHERE aggregate_id=UUID_TO_BIN(?)
                """, String.class, created.taskId().toString())).isEqualTo("DEAD");
        assertThat(message.eventType()).isEqualTo("TASK_QUEUED");
    }

    @Test
    void sseReplaysAfterLastEventIdAndClosesOnlyAfterTerminalEvents() throws Exception {
        TaskModels.TaskView created = createTask(null, null, "GENERATION");
        TaskRepository.TaskRow beforeCancel = repository.findTask(created.taskId());
        taskService.cancel(userId, "USER", created.taskId(), new TaskModels.CancelTaskRequest(
                beforeCancel.stateVersion()), "sse-cancel");

        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_session(id,user_id,refresh_token_hash,issued_at,expires_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?)
                """, sessionId.toString(), userId.toString(), bytes(3), Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().plusSeconds(600)));
        String token = tokens.createAccessToken(userId, "USER", sessionId, Instant.now().getEpochSecond());
        MvcResult initial = mockMvc.perform(get("/api/v1/tasks/{taskId}/events", created.taskId())
                        .header("Authorization", "Bearer " + token)
                        .header("Last-Event-ID", "1")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult result = initial.getRequest().isAsyncStarted() ? mockMvc.perform(asyncDispatch(initial)).andReturn()
                : initial;
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("id:2").contains("task-progress").doesNotContain("北京")
                .doesNotContain("preferences");
        assertThat(repository.findTask(created.taskId()).status()).isEqualTo(TaskStatus.REFUNDED);
    }

    private TaskModels.TaskView createTask(UUID tripId, UUID versionId, String type) {
        return taskService.create(userId, new TaskModels.CreateTaskRequest(type, tripId, versionId, 1,
                Map.of("city", "北京", "days", 3, "preferences", "少走路")), "create-" + UUID.randomUUID());
    }

    private void runMockWorkflow(UUID taskId) {
        for (int iteration = 0; iteration < 20; iteration++) {
            TaskWorkerClaimService.ClaimResult result = claimService.claim(taskId, "it-worker");
            if (!result.hasWork()) {
                if (result.terminal()) {
                    break;
                }
                continue;
            }
            TaskWorkerClaimService.Claim claim = result.claim();
            TaskModels.WorkerResult output = mockAgent.execute(taskId, claim.stepName(), claim.attemptNo());
            claimService.commit(claim, output, "integration-trace");
            if ("assemble".equals(claim.stepName())) {
                break;
            }
        }
    }

    private long balance() {
        return jdbc.queryForObject("SELECT balance_points FROM points_account WHERE user_id=UUID_TO_BIN(?)",
                Long.class, userId.toString());
    }

    private byte[] bytes(int value) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) value;
        return bytes;
    }
}
