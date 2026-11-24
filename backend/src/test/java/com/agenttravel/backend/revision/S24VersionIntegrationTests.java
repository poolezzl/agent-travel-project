package com.agenttravel.backend.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.agenttravel.backend.auth.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real MySQL coverage for the S24 version list and read-by-version endpoints.
 *
 * <p>Runs only with {@code S24_IT=true} plus a disposable database; it writes synthetic trips,
 * versions and snapshots. Verifies ordering, the current-version flag, change metadata, ownership
 * isolation (including ADMIN), soft-delete handling and paging clamps.
 */
@EnabledIfEnvironmentVariable(named = "S24_IT", matches = "true")
@SpringBootTest(properties = {
        "app.database.mode=mysql",
        "app.security.jwt-secret=s24-version-integration-test-signing-key",
        "app.s11.enabled=true",
        "app.s11.consumer-enabled=false",
        "app.s11.relay-enabled=false"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class S24VersionIntegrationTests {

    private static final String PREFIX = "/api/v1/trips/";
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenService tokens;

    @Test
    void listReturnsNewestFirstWithCurrentFlagAndNoStore() throws Exception {
        Account owner = account("USER");
        Trip trip = seedTrip(owner.id(), true);

        http.perform(get(PREFIX + trip.id() + "/versions").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].versionNo").value(2))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].changeType").value("UPDATE"))
                .andExpect(jsonPath("$[0].parentVersionId").value(trip.v1().toString()))
                .andExpect(jsonPath("$[1].versionNo").value(1))
                .andExpect(jsonPath("$[1].current").value(false))
                .andExpect(jsonPath("$[1].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[1].etag").value(org.hamcrest.Matchers.matchesPattern("[0-9A-F]{64}")));
    }

    @Test
    void detailReturnsPlanChangeMetadataAndDegradedFields() throws Exception {
        Account owner = account("USER");
        Trip trip = seedTrip(owner.id(), true);

        http.perform(get(PREFIX + trip.id() + "/versions/" + trip.v2()).header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.changeType").value("UPDATE"))
                .andExpect(jsonPath("$.affectedPaths[0]").value("days[1].weather"))
                .andExpect(jsonPath("$.plan.days[0].date").value("2026-12-01"))
                .andExpect(jsonPath("$.summary.day_count").value(2))
                .andExpect(jsonPath("$.degradedFields[0]").value("weather"))
                .andExpect(jsonPath("$.sourceMetadata.length()").value(1));

        http.perform(get(PREFIX + trip.id() + "/versions/" + trip.v1()).header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentVersionId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.changeType").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void foreignTripAndUnknownTripLookIdentical() throws Exception {
        Account owner = account("USER");
        Account stranger = account("USER");
        Trip trip = seedTrip(owner.id(), true);

        http.perform(get(PREFIX + trip.id() + "/versions").header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTEXT_NOT_FOUND"));

        http.perform(get(PREFIX + UUID.randomUUID() + "/versions").header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTEXT_NOT_FOUND"));

        http.perform(get(PREFIX + trip.id() + "/versions/" + trip.v2()).header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTEXT_NOT_FOUND"));
    }

    @Test
    void softDeletedTripIsNotFound() throws Exception {
        Account owner = account("USER");
        Trip trip = seedTrip(owner.id(), true);
        jdbc.update("UPDATE trip SET deleted_at=UTC_TIMESTAMP(6), status='DELETED' WHERE id=UUID_TO_BIN(?)",
                trip.id().toString());

        http.perform(get(PREFIX + trip.id() + "/versions").header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminMayReadForeignTripAndPagingIsClamped() throws Exception {
        Account owner = account("USER");
        Account admin = account("ADMIN");
        Trip trip = seedTrip(owner.id(), true);

        http.perform(get(PREFIX + trip.id() + "/versions").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        http.perform(get(PREFIX + trip.id() + "/versions?limit=0").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        http.perform(get(PREFIX + trip.id() + "/versions?limit=999&offset=1")
                .header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].versionNo").value(1));
    }

    @Test
    void unauthenticatedReadIsRejected() throws Exception {
        Account owner = account("USER");
        Trip trip = seedTrip(owner.id(), true);

        http.perform(get(PREFIX + trip.id() + "/versions")).andExpect(status().isUnauthorized());
    }

    private Account account(String role) {
        UUID id = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_account (id, username, password_hash, role, status)
                VALUES (UUID_TO_BIN(?), ?, 'synthetic-test-hash', ?, 'ACTIVE')
                """, id.toString(), "s24-version-" + id, role);
        jdbc.update("""
                INSERT INTO user_session (id, user_id, refresh_token_hash, issued_at, expires_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UNHEX(SHA2(?, 256)),
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 1 HOUR)
                """, session.toString(), id.toString(), session.toString());
        return new Account(id, "Bearer " + tokens.createAccessToken(id, role, session, Instant.now().getEpochSecond()));
    }

    private Trip seedTrip(UUID ownerId, boolean withRevision) {
        UUID trip = UUID.randomUUID();
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO trip (id, owner_id, title, start_date, end_date, status)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'S24 版本集成测试', '2026-12-01', '2026-12-02', 'ACTIVE')
                """, trip.toString(), ownerId.toString());
        insertVersion(v1, trip, null, 1, "PUBLISHED", ownerId);
        snapshot(v1, "{\"days\":[{\"date\":\"2026-12-01\"}],\"sources\":[],\"degraded_fields\":[]}", "{\"day_count\":1}");
        insertVersion(v2, trip, v1, 2, "PUBLISHED", ownerId);
        snapshot(v2, "{\"days\":[{\"date\":\"2026-12-01\"}],\"sources\":[{\"kind\":\"weather\"}],"
                + "\"degraded_fields\":[\"weather\"]}", "{\"day_count\":2}");
        jdbc.update("""
                INSERT INTO change_set (id, trip_version_id, base_version_id, change_no, change_type,
                  affected_paths_json, change_json, change_hash)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), 1, 'UPDATE',
                  CAST('["days[1].weather"]' AS JSON), CAST('{}' AS JSON), UNHEX(SHA2(?, 256)))
                """, UUID.randomUUID().toString(), v2.toString(), v1.toString(), "change-" + v2);
        jdbc.update("UPDATE trip_version SET version_status='SUPERSEDED' WHERE id=UUID_TO_BIN(?)", v1.toString());
        jdbc.update("UPDATE trip SET current_version_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)",
                v2.toString(), trip.toString());
        return new Trip(trip, v1, v2);
    }

    private void insertVersion(UUID id, UUID tripId, UUID parentId, int versionNo, String status, UUID creator) {
        jdbc.update("""
                INSERT INTO trip_version (id, trip_id, parent_version_id, version_no, etag, content_hash,
                  version_status, schema_version, model_version, prompt_version, created_by)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?,
                  UNHEX(SHA2(CONCAT(UUID(), ?), 256)), UNHEX(SHA2(CONCAT(UUID(), ?), 256)),
                  ?, 's19-plan-v1', 'orchestrator-v1', 's19-prompt-v1', UUID_TO_BIN(?))
                """, id.toString(), tripId.toString(), parentId == null ? null : parentId.toString(), versionNo,
                id.toString(), id.toString(), status, creator.toString());
    }

    private void snapshot(UUID versionId, String planJson, String summaryJson) {
        jdbc.update("""
                INSERT INTO plan_snapshot (id, trip_version_id, plan_json, summary_json)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), CAST(? AS JSON), CAST(? AS JSON))
                """, UUID.randomUUID().toString(), versionId.toString(), planJson, summaryJson);
    }

    private record Account(UUID id, String bearer) {
    }

    private record Trip(UUID id, UUID v1, UUID v2) {
    }

}
