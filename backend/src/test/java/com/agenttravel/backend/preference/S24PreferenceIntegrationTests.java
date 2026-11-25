package com.agenttravel.backend.preference;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.agenttravel.backend.auth.JwtTokenService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S24 偏好召回的真实 MySQL 集成验证。
 *
 * <p>仅在 {@code S24_IT=true} 且配置一次性数据库时运行；写入合成账号与偏好行，验证召回接口的
 * TOP-K 截断、低置信度过滤、遗忘折算、来源透传、归属隔离与清空态过滤，结束后清理自身数据。
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

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenService tokens;

    private UUID userId;

    @AfterEach
    void cleanUp() {
        if (userId != null) {
            jdbc.update("DELETE FROM user_preference WHERE user_id = UUID_TO_BIN(?)", userId.toString());
            jdbc.update("DELETE FROM user_session WHERE user_id = UUID_TO_BIN(?)", userId.toString());
            jdbc.update("DELETE FROM user_account WHERE id = UUID_TO_BIN(?)", userId.toString());
            userId = null;
        }
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

    private void seedCleared(String key, String sourceType, double confidence, int weight) {
        jdbc.update("""
                INSERT INTO user_preference
                  (id, user_id, preference_key, version_no, value_json, source_type,
                   confidence, weight, last_used_at, status, cleared_at, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 1, CAST('{"count":1}' AS JSON), ?, ?, ?,
                  UTC_TIMESTAMP(6), 'CLEARED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, UUID.randomUUID().toString(), userId.toString(), key, sourceType, confidence, weight);
    }
}
