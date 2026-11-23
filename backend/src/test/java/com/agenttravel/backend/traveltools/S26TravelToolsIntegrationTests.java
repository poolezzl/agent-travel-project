package com.agenttravel.backend.traveltools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.agenttravel.backend.adapter.FreshnessStatus;
import com.agenttravel.backend.adapter.ProviderErrorCode;
import com.agenttravel.backend.adapter.ProviderException;
import com.agenttravel.backend.adapter.ProviderKind;
import com.agenttravel.backend.adapter.ProviderResponse;
import com.agenttravel.backend.adapter.ProviderSource;
import com.agenttravel.backend.adapter.WeatherAdapter;
import com.agenttravel.backend.adapter.WeatherRecord;
import com.agenttravel.backend.auth.JwtTokenService;
import com.agenttravel.backend.knowledge.KnowledgeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@EnabledIfEnvironmentVariable(named = "S26_IT", matches = "true")
@SpringBootTest(properties = {
        "app.database.mode=mysql",
        "app.security.jwt-secret=s26-integration-test-only-signing-key",
        "app.s11.enabled=false",
        "app.adapters.mode=real",
        "app.adapters.amap-web-key=s26-synthetic-provider-value",
        "app.wallet.reconciliation-cron=-",
        "app.s26.scheduling-enabled=false"
})
@AutoConfigureMockMvc
class S26TravelToolsIntegrationTests {
    private static final Instant BASE = Instant.parse("2030-03-08T12:00:00Z");
    private static final Path DIRECTORY = tempDirectory();

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenService tokens;
    @Autowired ExportService exports;
    @Autowired ReminderService reminders;
    @Autowired KnowledgeRepository knowledge;
    @MockitoBean Clock clock;
    @MockitoBean WeatherAdapter weather;

    @DynamicPropertySource
    static void directory(DynamicPropertyRegistry registry) {
        registry.add("app.s26.export-directory", DIRECTORY::toString);
    }

    @BeforeEach
    void setTime() {
        time(BASE);
        when(weather.forecast(any())).thenThrow(new ProviderException(
                ProviderKind.QWEATHER, ProviderErrorCode.EMPTY_RESULT, "测试预报缺失"));
    }

    @Test
    void exportChecksOwnerRetriesOnceAndAuditsTheSignedDownload() throws Exception {
        Account owner = account("USER");
        Account other = account("ADMIN");
        Trip trip = trip(owner, BASE, LocalDate.of(2030, 3, 10), null);
        create(other, trip, "ICS", "same").andExpect(status().isNotFound());
        String id = exportId(create(owner, trip, "ICS", "same").andExpect(status().isAccepted()));
        assertThat(exportId(create(owner, trip, "ICS", "same").andExpect(status().isAccepted()))).isEqualTo(id);
        create(owner, trip, "PDF", "same").andExpect(status().isConflict());
        http.perform(get("/api/v1/exports/" + id)).andExpect(status().isUnauthorized());
        details(other, id).andExpect(status().isNotFound());

        jdbc.update("UPDATE export_job SET status='RUNNING',attempt_count=1 WHERE id=UUID_TO_BIN(?)", id);
        parallel(() -> {
            exports.runExports();
            return null;
        });
        String url = json.readTree(details(owner, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED")).andReturn().getResponse().getContentAsString()).path("download_url").asText();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM export_job WHERE id=UUID_TO_BIN(?)", Integer.class, id))
                .isEqualTo(2);
        http.perform(get(URI.create(url))).andExpect(status().isUnauthorized());
        http.perform(get(URI.create(url)).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        byte[] file = http.perform(get(URI.create(url)).header("Authorization", owner.bearer()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(file, StandardCharsets.UTF_8)).contains("中文景点", "DTSTART:20300310T010000Z");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event WHERE resource_id=UUID_TO_BIN(?)
                    AND outcome='SUCCESS' AND action='EXPORT_DOWNLOAD'
                """, Integer.class, id)).isEqualTo(1);

        time(BASE.plusSeconds(301));
        http.perform(get(URI.create(url)).header("Authorization", owner.bearer())).andExpect(status().isForbidden());
        String key = jdbc.queryForObject("""
                SELECT o.object_key FROM export_job e JOIN object_metadata o ON o.id=e.object_metadata_id
                WHERE e.id=UUID_TO_BIN(?)
                """, String.class, id);
        String orphan = UUID.randomUUID() + ".ics";
        Files.writeString(DIRECTORY.resolve(orphan), "孤立文件");
        Files.setLastModifiedTime(DIRECTORY.resolve(orphan), FileTime.from(BASE.minusSeconds(1)));
        time(BASE.plusSeconds(86401));
        exports.cleanup();
        details(owner, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.download_url").doesNotExist());
        assertThat(DIRECTORY.resolve(key)).doesNotExist();
        assertThat(DIRECTORY.resolve(orphan)).doesNotExist();
    }

    @Test
    void queuedWorkStopsWhenTheAccountHasBeenDeleted() throws Exception {
        Account owner = account("USER");
        Trip trip = trip(owner, BASE, LocalDate.of(2030, 3, 11), rule(BASE.plusSeconds(86400 * 5)));
        String id = exportId(create(owner, trip, "ICS", "deleted-account").andExpect(status().isAccepted()));
        allow(owner, trip);
        jdbc.update("UPDATE user_account SET deleted_at=UTC_TIMESTAMP(6) WHERE id=UUID_TO_BIN(?)", owner.id());
        time(Instant.parse("2030-03-10T01:00:00Z"));
        exports.runExports();
        reminders.runReminders();

        assertThat(jdbc.queryForObject("SELECT status FROM export_job WHERE id=UUID_TO_BIN(?)", String.class, id))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=UUID_TO_BIN(?)",
                Integer.class, owner.id())).isZero();
    }

    @Test
    void exhaustedExportDoesNotChangeItsFixedVersion() throws Exception {
        Account owner = account("USER");
        Trip trip = trip(owner, BASE, LocalDate.of(2030, 3, 10), null);
        jdbc.update("UPDATE plan_snapshot SET plan_json=JSON_OBJECT('days',JSON_ARRAY()) WHERE trip_version_id=UUID_TO_BIN(?)", trip.version());
        String id = exportId(create(owner, trip, "ICS", "retry").andExpect(status().isAccepted()));
        exports.runExports();
        details(owner, id).andExpect(jsonPath("$.status").value("PENDING"));
        time(BASE.plusSeconds(31));
        exports.runExports();
        time(BASE.plusSeconds(92));
        exports.runExports();
        details(owner, id).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(jdbc.queryForObject("""
                SELECT BIN_TO_UUID(t.current_version_id) FROM trip t WHERE t.id=UUID_TO_BIN(?)
                """, String.class, trip.id())).isEqualTo(trip.version());
        assertThat(jdbc.queryForObject("""
                SELECT JSON_LENGTH(plan_json,'$.days') FROM plan_snapshot WHERE trip_version_id=UUID_TO_BIN(?)
                """, Integer.class, trip.version())).isZero();
    }

    @Test
    void reservationUsesRuleTimeZoneAndCancellationStopsTheNextDelivery() throws Exception {
        Account owner = account("USER");
        Account other = account("USER");
        LocalDate visit = LocalDate.of(2030, 3, 11);
        String policy = rule(BASE.plusSeconds(86400 * 5));
        assertThat(knowledge.findRule(UUID.fromString(policy)).releaseTime()).hasToString("20:00");
        Trip trip = trip(owner, BASE, visit, policy);
        http.perform(put(trip.path() + "/reminders").header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        http.perform(delete(trip.path() + "/reminders").header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        cancel(owner, trip);
        allow(owner, trip);
        allow(owner, trip);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event WHERE resource_id=UUID_TO_BIN(?) AND action='REMINDER_AUTHORIZE'
                """, Integer.class, trip.version())).isEqualTo(1);
        reminders.runReminders();
        inbox(owner).andExpect(jsonPath("$.length()").value(0));

        Instant dayNotice = Instant.parse("2030-03-10T01:00:00Z");
        time(dayNotice);
        parallel(() -> {
            reminders.runReminders();
            return null;
        });
        inbox(owner).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].payload.timezone").value("America/New_York"))
                .andExpect(jsonPath("$[0].payload.release_at").value("2030-03-10T20:00-04:00"));
        inbox(other).andExpect(jsonPath("$.length()").value(0));
        cancel(owner, trip);
        cancel(owner, trip);
        time(Instant.parse("2030-03-10T23:30:00Z"));
        reminders.runReminders();
        inbox(owner).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].payload.freshness_status").value("stale"));
        http.perform(put(trip.path() + "/reminders").header("Authorization", owner.bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void expiredReservationSourceNeverProducesANotice() throws Exception {
        Account owner = account("USER");
        LocalDate visit = LocalDate.of(2030, 3, 11);
        Trip trip = trip(owner, BASE, visit, rule(BASE.plusSeconds(120)));
        allow(owner, trip);
        time(Instant.parse("2030-03-10T23:30:00Z"));
        reminders.runReminders();
        inbox(owner).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void weatherWaitsForRealWindowAndRepeatsOnlyAfterAFreshRiskTransition() throws Exception {
        Account owner = account("USER");
        LocalDate visit = LocalDate.of(2030, 3, 11);
        Trip trip = trip(owner, BASE, visit, null);
        allow(owner, trip);
        reminders.runReminders();
        verifyNoInteractions(weather);

        Instant within = BASE.plusSeconds(86400);
        time(within);
        forecast(visit, within, within.minusSeconds(1), 37, "暴雨");
        reminders.runReminders();
        inbox(owner).andExpect(jsonPath("$.length()").value(0));
        time(within.plusSeconds(301));
        forecast(visit, within, within.plusSeconds(3600), 21, "多云");
        reminders.runReminders();
        inbox(owner).andExpect(jsonPath("$.length()").value(0));
        time(within.plusSeconds(602));
        forecast(visit, within.plusSeconds(600), within.plusSeconds(3600), 37, "暴雨");
        reminders.runReminders();
        time(within.plusSeconds(903));
        reminders.runReminders();
        inbox(owner).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("WEATHER"))
                .andExpect(jsonPath("$[0].payload.source_record_id").value("s26-forecast-" + visit));
        time(within.plusSeconds(1204));
        forecast(visit, within.plusSeconds(1200), within.plusSeconds(3600), 37, "晴");
        reminders.runReminders();
        inbox(owner).andExpect(jsonPath("$.length()").value(2));

        time(within.plusSeconds(1505));
        forecast(visit, within.plusSeconds(1500), within.plusSeconds(3600), 22, "晴");
        reminders.runReminders();
        inbox(owner).andExpect(jsonPath("$.length()").value(2));

        time(within.plusSeconds(1806));
        forecast(visit, within.plusSeconds(600), within.plusSeconds(3600), 37, "暴雨");
        reminders.runReminders();
        inbox(owner).andExpect(jsonPath("$.length()").value(2));

        time(within.plusSeconds(2107));
        forecast(visit, within.plusSeconds(2100), within.plusSeconds(3600), 37, "暴雨");
        parallel(() -> {
            reminders.runReminders();
            return null;
        });
        inbox(owner).andExpect(jsonPath("$.length()").value(3));

        time(within.plusSeconds(2408));
        forecast(visit, within.plusSeconds(2400), within.plusSeconds(3600), 37, "暴雨");
        reminders.runReminders();
        inbox(owner).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void firstCheckAfterUpgradePreservesLegacyWeatherDeduplication() throws Exception {
        Account owner = account("USER");
        LocalDate visit = LocalDate.of(2030, 3, 11);
        Trip trip = trip(owner, BASE, visit, null);
        Instant within = BASE.plusSeconds(86400);
        time(within);
        allow(owner, trip);
        forecast(visit, within, within.plusSeconds(3600), 37, "暴雨");
        reminders.runReminders();
        inbox(owner).andExpect(jsonPath("$.length()").value(1));

        String oldKeyInput = trip.id() + "|WEATHER|beijing|" + visit + "|false|true|false|true|true";
        jdbc.update("""
                UPDATE reminder_candidate c JOIN notification n ON n.reminder_candidate_id=c.id
                SET c.dedupe_key=SHA2(?,256),n.dedupe_key=SHA2(?,256) WHERE c.trip_id=UUID_TO_BIN(?)
                """, oldKeyInput, oldKeyInput, trip.id());
        jdbc.update("DELETE FROM weather_reminder_state WHERE trip_id=UUID_TO_BIN(?)", trip.id());
        time(within.plusSeconds(301));
        forecast(visit, within.plusSeconds(300), within.plusSeconds(3600), 37, "暴雨");
        reminders.runReminders();
        inbox(owner).andExpect(jsonPath("$.length()").value(1));
    }

    private void forecast(LocalDate date, Instant retrieved, Instant valid, double max, String condition) {
        WeatherRecord record = new WeatherRecord(date, 12, max, 0.2, condition, "forecast",
                "s26-forecast-" + date, retrieved.minusSeconds(10), valid, FreshnessStatus.FRESH);
        ProviderSource source = new ProviderSource("qweather", record.sourceRecordId(), record.retrievedAt(), valid,
                FreshnessStatus.FRESH, false);
        doReturn(new ProviderResponse<>(List.of(record), source, false)).when(weather).forecast(any());
    }

    private void time(Instant instant) {
        when(clock.instant()).thenReturn(instant);
    }

    private Account account(String role) {
        UUID id = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_account(id,username,password_hash,role,status)
                VALUES(UUID_TO_BIN(?),?,'synthetic-test-hash',?,'ACTIVE')
                """, id.toString(), "s26-" + id, role);
        jdbc.update("""
                INSERT INTO user_session(id,user_id,refresh_token_hash,issued_at,expires_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UNHEX(SHA2(?,256)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 1 HOUR)
                """, session.toString(), id.toString(), session.toString());
        return new Account(id.toString(), "Bearer " + tokens.createAccessToken(id, role, session, Instant.now().getEpochSecond()));
    }

    private Trip trip(Account owner, Instant generated, LocalDate date, String policy) throws Exception {
        String trip = UUID.randomUUID().toString();
        String version = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO trip(id,owner_id,title,start_date,end_date,status)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'中文行程',?,?,'ACTIVE')
                """, trip, owner.id(), date, date);
        jdbc.update("""
                INSERT INTO trip_version(id,trip_id,version_no,etag,content_hash,version_status,schema_version,created_by)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),1,UNHEX(SHA2(?,256)),UNHEX(SHA2(?,256)),'PUBLISHED','s19-plan-v1',UUID_TO_BIN(?))
                """, version, trip, version, version, owner.id());
        jdbc.update("UPDATE trip SET current_version_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)", version, trip);
        ObjectNode plan = (ObjectNode) json.readTree("""
                {"trip":{"city_id":"beijing","city_name":"北京"},"generated_at":"%s","days":[
                  {"date":"%s","city_id":"beijing","segments":[{"point_id":"p1","name":"中文景点","start_time":"09:00","end_time":"10:00"}],
                   "weather":{"data_type":"climate_statistics","condition":"晴","source_record_id":"s26-baseline","temp_min":12,"temp_max":22},
                   "reservations":[]}],
                 "sources":[{"source_record_id":"s26-baseline","source_type":"weather","freshness_status":"fresh",
                  "retrieved_at":"%s","valid_until":"%s","is_estimated":false}]}
                """.formatted(generated, date, generated.minusSeconds(5), generated.plusSeconds(120)));
        if (policy != null) {
            var booking = ((ObjectNode) plan.path("days").get(0)).withArray("reservations").addObject();
            booking.put("point_id", "p1").put("policy_id", policy).put("reservation_required", true);
            booking.putArray("timeline").addObject().put("status", "pending").put("point_id", "p1")
                    .put("policy_id", policy).put("timezone", "America/New_York")
                    .put("release_at", date.minusDays(1).atTime(20, 0).atZone(ZoneId.of("America/New_York")).toOffsetDateTime().toString());
        }
        jdbc.update("""
                INSERT INTO plan_snapshot(id,trip_version_id,plan_json,summary_json)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),CAST(? AS JSON),JSON_OBJECT())
                """, UUID.randomUUID().toString(), version, plan.toString());
        return new Trip(trip, version);
    }

    private String rule(Instant sourceExpiry) {
        String source = UUID.randomUUID().toString();
        String snapshot = UUID.randomUUID().toString();
        String poi = UUID.randomUUID().toString();
        String policy = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO city(id,city_code,name,timezone) VALUES(UUID_TO_BIN(?),'beijing','北京','Asia/Shanghai')
                ON DUPLICATE KEY UPDATE id=id
                """, UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO poi(id,city_id,external_poi_id,name,category)
                SELECT UUID_TO_BIN(?),id,?,'中文景点','ATTRACTION' FROM city WHERE city_code='beijing'
                """, poi, poi);
        jdbc.update("""
                INSERT INTO source_record(id,source_type,provider_name,external_record_id)
                VALUES(UUID_TO_BIN(?),'RESERVATION_RULE','s26-test',?)
                """, source, source);
        jdbc.update("""
                INSERT INTO source_snapshot(id,source_record_id,retrieved_at,valid_until,freshness_status,content_hash)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,'FRESH',UNHEX(SHA2(?,256)))
                """, snapshot, source, Timestamp.from(BASE.minusSeconds(30)), Timestamp.from(sourceExpiry), snapshot);
        jdbc.update("""
                INSERT INTO reservation_rule(id,rule_key,poi_id,version_no,ticket_type,release_timezone,effective_from,
                    last_verified_at,status,source_snapshot_id,reservation_required,release_time,advance_days,stale_after_days)
                VALUES(UUID_TO_BIN(?),?,UUID_TO_BIN(?),1,'通用','America/New_York',?,?,'PUBLISHED',UUID_TO_BIN(?),TRUE,'20:00:00',1,30)
                """, policy, policy, poi, Timestamp.from(BASE.minusSeconds(60)), Timestamp.from(BASE.minusSeconds(30)), snapshot);
        return policy;
    }

    private String exportId(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("export_id").asText();
    }

    private ResultActions create(Account owner, Trip trip, String format, String key) throws Exception {
        return http.perform(post(trip.path() + "/exports").header("Authorization", owner.bearer())
                .header("Idempotency-Key", key).contentType("application/json").content("{\"format\":\"" + format + "\"}"));
    }

    private ResultActions details(Account owner, String id) throws Exception {
        return http.perform(get("/api/v1/exports/" + id).header("Authorization", owner.bearer()));
    }

    private void allow(Account owner, Trip trip) throws Exception {
        http.perform(put(trip.path() + "/reminders").header("Authorization", owner.bearer()))
                .andExpect(status().isNoContent());
    }

    private void cancel(Account owner, Trip trip) throws Exception {
        http.perform(delete(trip.path() + "/reminders").header("Authorization", owner.bearer()))
                .andExpect(status().isNoContent());
    }

    private ResultActions inbox(Account owner) throws Exception {
        return http.perform(get("/api/v1/notifications").header("Authorization", owner.bearer())).andExpect(status().isOk());
    }

    private static void parallel(Callable<Void> action) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Void> start = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return action.call();
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(start);
            var second = executor.submit(start);
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
    }

    private static Path tempDirectory() {
        try {
            return Files.createTempDirectory("s26-exports-");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Account(String id, String bearer) {
    }

    private record Trip(String id, String version) {
        String path() {
            return "/api/v1/trips/" + id + "/versions/" + version;
        }
    }
}
