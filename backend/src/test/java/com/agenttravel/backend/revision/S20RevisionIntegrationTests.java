package com.agenttravel.backend.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.points.PointsService;
import com.agenttravel.backend.task.CanonicalJson;
import com.agenttravel.backend.task.OutboxRelayService;
import com.agenttravel.backend.task.TaskModels;
import com.agenttravel.backend.task.TaskPlanRepository;
import com.agenttravel.backend.task.TaskPlanService;
import com.agenttravel.backend.task.TaskQueue;
import com.agenttravel.backend.task.TaskService;
import com.agenttravel.backend.task.TaskStatus;
import com.agenttravel.backend.task.TaskWorkerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "S20_IT", matches = "true")
@SpringBootTest(properties = {
        "app.security.jwt-secret=test-only-jwt-signing-key-32-bytes",
        "spring.datasource.url=${S20_DB_URL}",
        "spring.datasource.username=${S20_DB_USER}",
        "spring.datasource.password=${S20_DB_PASSWORD:}",
        "app.agent-service.base-url=${S20_AGENT_URL}",
        "spring.data.redis.port=${S20_REDIS_PORT:6379}",
        "app.s11.relay-enabled=false",
        "app.s11.consumer-enabled=false",
        "app.s11.heartbeat-scheduler-enabled=false",
        "app.s11.sse-scheduler-enabled=false"
})
class S20RevisionIntegrationTests {

    @Autowired private RevisionService service;
    @Autowired private RevisionRepository revisions;
    @Autowired private TaskPlanRepository plans;
    @Autowired private PointsService points;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private TaskService tasks;
    @Autowired private TaskPlanService taskPlans;
    @Autowired private TaskWorkerService worker;
    @Autowired private OutboxRelayService relay;
    @Autowired private TaskQueue queue;

    @Test
    void actualPythonAndMysqlPreserveOtherDaysAndSettleConcurrentConfirmationOnce() throws Exception {
        UUID user = fundedUser();
        UUID trip = UUID.randomUUID();
        UUID base = UUID.randomUUID();
        var fixture = mapper.readTree(Files.readString(Path.of("../contracts/examples/s19/plan-ok.json")));
        var canonical = new CanonicalJson();
        String json = canonical.write(mapper.convertValue(fixture, java.util.Map.class));
        byte[] contentHash = TaskPlanRepository.sha256(json);
        Instant now = Instant.now();
        plans.insertTrip(trip, user, "S20", LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21), now);
        plans.insertVersion(base, trip, 1, TaskPlanRepository.sha256(base.toString()), contentHash,
                "s19-plan-v1", user, now);
        plans.insertPlanSnapshot(UUID.randomUUID(), base, json, "{}", now);
        plans.setTripCurrentVersion(trip, base, now);
        var original = revisions.findVersion(trip, base, user, false);

        var answer = service.preview(user, trip, request(base, null, "故宫票价多少？", "read", false));
        assertThat(answer.answer()).contains("60.00");
        String message = "删除第二天的中国国家博物馆";
        assertThat(service.confirm(user, trip, request(base, original.etag(), message, "decline", false))
                .versionId()).isNull();
        assertThat(points.balance(user).balancePoints()).isEqualTo(100);
        var impossible = request(base, original.etag(), "第二天增加不存在的景点", "failure", true);
        assertThatThrownBy(() -> service.confirm(user, trip, impossible)).isInstanceOf(ApiException.class);
        assertThat(points.balance(user).balancePoints()).isEqualTo(100);

        var accepted = request(base, original.etag(), message, "accepted", true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.confirm(user, trip, accepted));
            var second = pool.submit(() -> service.confirm(user, trip, accepted));
            var a = first.get(45, TimeUnit.SECONDS);
            var b = second.get(45, TimeUnit.SECONDS);
            assertThat(a.versionId()).isEqualTo(b.versionId());
            assertThat(a.reused()).isNotEqualTo(b.reused());
            assertThat(a.affectedDays()).containsExactlyElementsOf(b.affectedDays());
            assertThat(a.changes()).containsExactlyElementsOf(b.changes());
            var revised = mapper.readTree(revisions.findVersion(trip, a.versionId(), user, false).planJson());
            assertThat(revised.path("days").get(0)).isEqualTo(fixture.path("days").get(0));
            assertThat(revised.path("days").get(1).path("segments")).isEmpty();
            assertThat(points.balance(user).balancePoints()).isEqualTo(98);
            assertThatThrownBy(() -> service.confirm(user, trip,
                    request(base, original.etag(), message, "different", true)))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo("REVISION_BASE_MISMATCH"));
            var rolledBack = service.rollback(user, trip, base, a.etag());
            assertThat(rolledBack.versionId()).isNotEqualTo(base);
            assertThat(rolledBack.parentVersionId()).isEqualTo(a.versionId());
            assertThat(rolledBack.contentHash()).isEqualTo(original.contentHash());
            assertThat(points.balance(user).balancePoints()).isEqualTo(98);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_ledger WHERE user_id=UUID_TO_BIN(?)", Integer.class, user.toString()))
                .isEqualTo(2);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "S20_REDIS_IT", matches = "true")
    void queuedGenerationReturnsThreePlansAndChargesServerCountOnce() {
        UUID user = fundedUser();
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .with(TemporalAdjusters.next(DayOfWeek.FRIDAY));
        var request = new TaskModels.CreateTaskRequest("GENERATION", null, null, 3, Map.of(
                "city", "北京", "start_date", start.toString(), "end_date", start.plusDays(2).toString(),
                "must_visit", List.of("故宫博物院"), "transport", "混合", "plan_count", 1));
        var task = tasks.create(user, request, "variants");
        assertThat(task.reservedPoints()).isEqualTo(25);
        assertThat(tasks.create(user, request, "variants").taskId()).isEqualTo(task.taskId());
        relay.relayPending();
        var records = queue.poll(task.taskId(), "s20", "test-consumer", 1);
        assertThat(records).hasSize(1);
        for (int i = 0; i < 5; i++) {
            assertThat(worker.advance(task.taskId())).isTrue();
        }
        queue.acknowledge(task.taskId(), "s20", records.getFirst().recordId());
        assertThat(tasks.get(user, "USER", task.taskId()).status())
                .isIn(TaskStatus.SUCCEEDED, TaskStatus.PARTIAL);
        var plan = taskPlans.get(user, "USER", task.taskId());
        assertThat((List<?>) plan.summary().get("variants")).hasSize(3);
        assertThat(points.balance(user).balancePoints()).isEqualTo(75);
    }

    private UUID fundedUser() {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_account(id,username,password_hash,role,status)
                VALUES(UUID_TO_BIN(?),?,'synthetic-hash','USER','ACTIVE')
                """, user.toString(), "s20-" + user);
        jdbc.update("""
                INSERT INTO points_account(id,user_id,balance_points,state_version)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),100,0)
                """, UUID.randomUUID().toString(), user.toString());
        return user;
    }

    private static RevisionModels.RevisionCommand request(UUID base, String etag, String text, String key,
            boolean confirmed) {
        return new RevisionModels.RevisionCommand(base, etag, null, List.of(), null, null, null, null, confirmed, key,
                text, null, null, null, null, null, null, List.of());
    }
}
