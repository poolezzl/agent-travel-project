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
 * S24 偏好召回的配置覆盖集成验证：证明 {@code app.preferences.*} 改动会直接改变接口召回结果，
 * 而不是只停留在单元测试里。仅在 {@code S24_IT=true} 且配置一次性数据库时运行。
 */
@EnabledIfEnvironmentVariable(named = "S24_IT", matches = "true")
@SpringBootTest(properties = {
    "app.database.mode=mysql",
    "app.security.jwt-secret=s24-preference-override-integration-test-key",
    "app.s11.enabled=true",
    "app.s11.consumer-enabled=false",
    "app.s11.relay-enabled=false",
    "app.preferences.min-confidence=0.4",
    "app.preferences.top-k=2",
    "app.preferences.decay-enabled=false"
})
@AutoConfigureMockMvc
class S24PreferenceConfigOverrideIntegrationTests {

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
    void overriddenParametersChangeRecallResult() throws Exception {
        String bearer = account("USER");
        seed("theme.qinzi", 0.90, 30, "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 400 DAY)");
        seed("pace.jincou", 0.50, 25, "UTC_TIMESTAMP(6)");
        seed("theme.meishi", 0.90, 20, "UTC_TIMESTAMP(6)");
        seed("budget.shushi", 0.70, 12, "UTC_TIMESTAMP(6)");

        http.perform(get(PREFIX).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("theme.qinzi"))
                .andExpect(jsonPath("$[0].weight").value(30))
                .andExpect(jsonPath("$[1].key").value("pace.jincou"))
                .andExpect(jsonPath("$[1].weight").value(25));
    }

    private String account(String role) {
        UUID id = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        userId = id;
        jdbc.update("""
                INSERT INTO user_account (id, username, password_hash, role, status)
                VALUES (UUID_TO_BIN(?), ?, 'synthetic-test-hash', ?, 'ACTIVE')
                """, id.toString(), "s24-preference-override-" + id, role);
        jdbc.update("""
                INSERT INTO user_session (id, user_id, refresh_token_hash, issued_at, expires_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UNHEX(SHA2(?, 256)),
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 1 HOUR)
                """, session.toString(), id.toString(), session.toString());
        return "Bearer " + tokens.createAccessToken(id, role, session, Instant.now().getEpochSecond());
    }

    private void seed(String key, double confidence, int weight, String lastUsedSql) {
        jdbc.update("""
                INSERT INTO user_preference
                  (id, user_id, preference_key, version_no, value_json, source_type,
                   confidence, weight, last_used_at, status, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 1, CAST('{"count":1}' AS JSON),
                  'HISTORY_ANALYSIS', ?, ?, %s, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """.formatted(lastUsedSql), UUID.randomUUID().toString(), userId.toString(), key,
                confidence, weight);
    }
}
