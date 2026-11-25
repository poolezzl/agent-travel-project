package com.agenttravel.backend.preference;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.agenttravel.backend.auth.JwtTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S24 偏好召回与偏好提取的真实 MySQL 集成验证。
 *
 * <p>仅在 {@code S24_IT=true} 且配置一次性数据库时运行；写入合成账号、行程、快照与偏好行，验证
 * TOP-K 截断、低置信度过滤、遗忘折算排序、来源类型透传、归属隔离、清空态过滤、证据流水落库、
 * 重复提取幂等与「同一偏好键只取最新版本」，结束后清理自身数据。
 */
@EnabledIfEnvironmentVariable(named = "S24_IT", matches = "true")
@SpringBootTest(properties = {
    "app.database.mode=mysql",
    "app.security.jwt-secret=s24-preference-integration-test-signing-key",
    "app.s11.enabled=true",
    "app.s11.consumer-enabled=false",
    "app.s11.relay-enabled=false"
})
@AutoConfigureMockMvc
class S24PreferenceIntegrationTests {

    private static final String PREFIX = "/api/v1/preferences/recall";
    private static final String SUMMARY = "/api/v1/preferences/summary";

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenService tokens;

    private UUID userId;

    @AfterEach
    void cleanUp() {
        if (userId == null) {
            return;
        }
        List<String> tripIds = jdbc.queryForList(
                "SELECT BIN_TO_UUID(id) FROM trip WHERE owner_id = UUID_TO_BIN(?)",
                String.class, userId.toString());
        jdbc.update("DELETE FROM preference_evidence WHERE preference_id IN "
                + "(SELECT id FROM user_preference WHERE user_id = UUID_TO_BIN(?))", userId.toString());
        jdbc.update("DELETE FROM user_preference WHERE user_id = UUID_TO_BIN(?)", userId.toString());
        jdbc.update("DELETE FROM preference_summary WHERE user_id = UUID_TO_BIN(?)", userId.toString());
        jdbc.update("UPDATE trip SET current_version_id = NULL WHERE owner_id = UUID_TO_BIN(?)",
                userId.toString());
        if (!tripIds.isEmpty()) {
            List<Object> args = new java.util.ArrayList<>();
            StringBuilder ids = new StringBuilder();
            for (String tripId : tripIds) {
                if (ids.length() > 0) {
                    ids.append(",");
                }
                ids.append("UUID_TO_BIN(?)");
                args.add(tripId);
            }
            jdbc.update("DELETE FROM plan_snapshot WHERE trip_version_id IN "
                    + "(SELECT id FROM trip_version WHERE trip_id IN (" + ids + "))", args.toArray());
            jdbc.update("DELETE FROM trip_city WHERE trip_id IN (" + ids + ")", args.toArray());
            jdbc.update("DELETE FROM trip_version WHERE trip_id IN (" + ids + ")", args.toArray());
        }
        jdbc.update("DELETE FROM trip WHERE owner_id = UUID_TO_BIN(?)", userId.toString());
        jdbc.update("DELETE FROM trip_city WHERE city_id IN "
                + "(SELECT id FROM city WHERE city_code LIKE 'alg12-%')");
        jdbc.update("DELETE FROM city WHERE city_code LIKE 'alg12-%'");
        jdbc.update("DELETE FROM user_session WHERE user_id = UUID_TO_BIN(?)", userId.toString());
        jdbc.update("DELETE FROM user_account WHERE id = UUID_TO_BIN(?)", userId.toString());
        userId = null;
    }

    @Test
    void recallFiltersDecaysAndOrdersByEffectiveWeight() throws Exception {
        String bearer = account("USER");
        seed("theme.qinzi", "HISTORY_ANALYSIS", 0.90, 30, "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 400 DAY)");
        seed("theme.meishi", "HISTORY_ANALYSIS", 0.90, 20, "UTC_TIMESTAMP(6)");
        seed("budget.shushi", "HISTORY_ANALYSIS", 0.70, 12, "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 30 DAY)");
        seed("pace.jincou", "HISTORY_ANALYSIS", 0.50, 25, "UTC_TIMESTAMP(6)");

        http.perform(get(PREFIX).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].key").value("theme.meishi"))
                .andExpect(jsonPath("$[0].weight").value(20))
                .andExpect(jsonPath("$[0].sourceType").value("HISTORY_ANALYSIS"))
                .andExpect(jsonPath("$[1].key").value("budget.shushi"))
                .andExpect(jsonPath("$[1].weight").value(11))
                .andExpect(jsonPath("$[2].key").value("theme.qinzi"))
                .andExpect(jsonPath("$[2].weight").value(6))
                .andExpect(jsonPath("$[?(@.key=='pace.jincou')]").isEmpty());
    }

    @Test
    void recallHonoursRequestedTopK() throws Exception {
        String bearer = account("USER");
        seed("theme.meishi", "HISTORY_ANALYSIS", 0.90, 20, "UTC_TIMESTAMP(6)");
        seed("theme.qinzi", "HISTORY_ANALYSIS", 0.90, 30, "UTC_TIMESTAMP(6)");
        seed("budget.shushi", "HISTORY_ANALYSIS", 0.70, 12, "UTC_TIMESTAMP(6)");

        http.perform(get(PREFIX + "?top_k=2").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("theme.qinzi"));
    }

    @Test
    void recallKeepsStoredSourceType() throws Exception {
        String bearer = account("USER");
        seed("pace.relaxed", "EXPLICIT", 0.95, 15, "UTC_TIMESTAMP(6)");

        http.perform(get(PREFIX).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceType").value("EXPLICIT"))
                .andExpect(jsonPath("$[0].confidence").value(0.95));
    }

    @Test
    void recallExcludesClearedAndForeignPreferences() throws Exception {
        String bearer = account("USER");
        seed("theme.meishi", "HISTORY_ANALYSIS", 0.90, 20, "UTC_TIMESTAMP(6)");
        seedCleared("theme.cleared", "HISTORY_ANALYSIS", 0.90, 40);

        UUID other = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_account (id, username, password_hash, role, status)
                VALUES (UUID_TO_BIN(?), ?, 'synthetic-test-hash', 'USER', 'ACTIVE')
                """, other.toString(), "s24-preference-other-" + other);
        jdbc.update("""
                INSERT INTO user_preference
                  (id, user_id, preference_key, version_no, value_json, source_type,
                   confidence, weight, last_used_at, status, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'theme.foreign', 1, CAST('{"count":1}' AS JSON),
                  'HISTORY_ANALYSIS', 0.90, 99, UTC_TIMESTAMP(6), 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, UUID.randomUUID().toString(), other.toString());
        try {
            http.perform(get(PREFIX).header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].key").value("theme.meishi"));
        } finally {
            jdbc.update("DELETE FROM user_preference WHERE user_id = UUID_TO_BIN(?)", other.toString());
            jdbc.update("DELETE FROM user_account WHERE id = UUID_TO_BIN(?)", other.toString());
        }
    }

    @Test
    void recallReturnsOnlyLatestVersionOfEachKey() throws Exception {
        String bearer = account("USER");
        seedVersion("theme.meishi", 1, 20);
        seedVersion("theme.meishi", 2, 33);

        http.perform(get(PREFIX).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].key").value("theme.meishi"))
                .andExpect(jsonPath("$[0].weight").value(33));
    }

    @Test
    void summaryWritesEvidenceLedgerAndRecallUsesEvidenceWeight() throws Exception {
        String bearer = account("USER");
        for (int index = 0; index < 3; index++) {
            seedTripWithPlan("主题行程 " + index,
                    "{\"trip\":{\"preferences\":[\"亲子\"],\"day_count\":2,\"budget\":{\"amount_minor\":200000}},"
                            + "\"summary\":{\"point_count\":3}}", "测试市");
        }

        http.perform(get(SUMMARY).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.dimensions[?(@.key=='theme')].weight").value(9));

        Integer evidenceRows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM preference_evidence e
                JOIN user_preference p ON p.id = e.preference_id
                WHERE p.user_id = UUID_TO_BIN(?) AND p.preference_key = 'theme.亲子'
                """, Integer.class, userId.toString());
        Integer evidenceDelta = jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.weight_delta), 0) FROM preference_evidence e
                JOIN user_preference p ON p.id = e.preference_id
                WHERE p.user_id = UUID_TO_BIN(?) AND p.preference_key = 'theme.亲子'
                """, Integer.class, userId.toString());
        assertThat(evidenceRows).isEqualTo(3);
        assertThat(evidenceDelta).isEqualTo(9);

        List<JsonNode> recalled = recall(bearer, 20);
        assertThat(recalled).anySatisfy(item -> {
            assertThat(item.get("key").asText()).isEqualTo("theme.亲子");
            assertThat(item.get("weight").asInt()).isEqualTo(9);
        });
    }

    @Test
    void repeatedSummaryKeepsOneRecalledRowPerKey() throws Exception {
        String bearer = account("USER");
        for (int index = 0; index < 3; index++) {
            seedTripWithPlan("主题行程 " + index,
                    "{\"trip\":{\"preferences\":[\"亲子\"],\"day_count\":2,\"budget\":{\"amount_minor\":200000}},"
                            + "\"summary\":{\"point_count\":3}}", "测试市");
        }

        http.perform(get(SUMMARY).header("Authorization", bearer)).andExpect(status().isOk());
        http.perform(get(SUMMARY).header("Authorization", bearer)).andExpect(status().isOk());

        List<String> keys = recall(bearer, 20).stream().map(item -> item.get("key").asText()).toList();
        assertThat(keys).contains("theme.亲子");
        assertThat(keys).doesNotHaveDuplicates();
    }

    private List<JsonNode> recall(String bearer, int topK) throws Exception {
        MvcResult result = http.perform(get(PREFIX + "?top_k=" + topK).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<JsonNode> items = new ArrayList<>();
        body.forEach(items::add);
        return items;
    }

    @Test
    void unauthenticatedRecallIsRejected() throws Exception {
        http.perform(get(PREFIX)).andExpect(status().isUnauthorized());
    }

    private String account(String role) {
        UUID id = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        userId = id;
        jdbc.update("""
                INSERT INTO user_account (id, username, password_hash, role, status)
                VALUES (UUID_TO_BIN(?), ?, 'synthetic-test-hash', ?, 'ACTIVE')
                """, id.toString(), "s24-preference-" + id, role);
        jdbc.update("""
                INSERT INTO user_session (id, user_id, refresh_token_hash, issued_at, expires_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UNHEX(SHA2(?, 256)),
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 1 HOUR)
                """, session.toString(), id.toString(), session.toString());
        return "Bearer " + tokens.createAccessToken(id, role, session, Instant.now().getEpochSecond());
    }

    private void seed(String key, String sourceType, double confidence, int weight, String lastUsedSql) {
        jdbc.update("""
                INSERT INTO user_preference
                  (id, user_id, preference_key, version_no, value_json, source_type,
                   confidence, weight, last_used_at, status, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 1, CAST('{"count":1}' AS JSON), ?, ?, ?, %s,
                  'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """.formatted(lastUsedSql), UUID.randomUUID().toString(), userId.toString(), key,
                sourceType, confidence, weight);
    }

    private void seedVersion(String key, int versionNo, int weight) {
        jdbc.update("""
                INSERT INTO user_preference
                  (id, user_id, preference_key, version_no, value_json, source_type,
                   confidence, weight, last_used_at, status, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, CAST('{"count":1}' AS JSON),
                  'HISTORY_ANALYSIS', 0.90, ?, UTC_TIMESTAMP(6), 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, UUID.randomUUID().toString(), userId.toString(), key, versionNo, weight);
    }

    private void seedCleared(String key, String sourceType, double confidence, int weight) {
        jdbc.update("""
                INSERT INTO user_preference
                  (id, user_id, preference_key, version_no, value_json, source_type,
                   confidence, weight, last_used_at, status, cleared_at, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 1, CAST('{"count":1}' AS JSON), ?, ?, ?,
                  UTC_TIMESTAMP(6), 'CLEARED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, UUID.randomUUID().toString(), userId.toString(), key, sourceType, confidence, weight);
    }

    private void seedTripWithPlan(String title, String planJson, String cityName) {
        UUID trip = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO trip (id, owner_id, title, start_date, end_date, status, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, '2026-12-01', '2026-12-02', 'ACTIVE',
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, trip.toString(), userId.toString(), title);
        jdbc.update("""
                INSERT INTO trip_version
                  (id, trip_id, version_no, etag, content_hash, version_status,
                   schema_version, model_version, prompt_version, created_by, created_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 1, UNHEX(SHA2(?, 256)), UNHEX(SHA2(?, 256)),
                        'PUBLISHED', 's19-plan-v1', 'orchestrator-v1', 's19-prompt-v1', UUID_TO_BIN(?),
                        UTC_TIMESTAMP(6))
                """, version.toString(), trip.toString(), trip.toString(), trip.toString(),
                userId.toString());
        jdbc.update("""
                INSERT INTO plan_snapshot (id, trip_version_id, plan_json, created_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), CAST(? AS JSON), UTC_TIMESTAMP(6))
                """, UUID.randomUUID().toString(), version.toString(), planJson);
        jdbc.update("UPDATE trip SET current_version_id = UUID_TO_BIN(?) WHERE id = UUID_TO_BIN(?)",
                version.toString(), trip.toString());
        if (cityName != null) {
            jdbc.update("""
                    INSERT INTO city (id, city_code, name, timezone, status, created_at, updated_at)
                    VALUES (UUID_TO_BIN(?), ?, ?, 'Asia/Shanghai', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE name = VALUES(name)
                    """, UUID.randomUUID().toString(), "alg12-" + cityName, cityName);
            jdbc.update("""
                    INSERT INTO trip_city (id, trip_id, city_id, sequence_no, created_at)
                    VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?),
                            (SELECT id FROM city WHERE city_code = ?), 1, UTC_TIMESTAMP(6))
                    """, UUID.randomUUID().toString(), trip.toString(), "alg12-" + cityName);
        }
    }
}
