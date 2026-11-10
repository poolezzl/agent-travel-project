package com.agenttravel.backend.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real MySQL import integration for the S06 minimal set projection.
 *
 * <p>Runs only with {@code S15_IT=true}. It inserts an ADMIN fixture user, then
 * drives the same preview/commit path used by the admin API for POI, knowledge
 * and reservation-rule rows. All created rows must stay DRAFT.
 */
@EnabledIfEnvironmentVariable(named = "S15_IT", matches = "true")
@SpringBootTest(properties = {
        "app.security.jwt-secret=test-only-jwt-signing-key-32-bytes",
        "app.s11.relay-enabled=false",
        "app.s11.consumer-enabled=false",
        "app.s11.heartbeat-scheduler-enabled=false",
        "app.s11.sse-scheduler-enabled=false"
})
@ActiveProfiles("local")
class S15ImportMySqlIntegrationTests {

    @Autowired
    private KnowledgeService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID admin;

    @BeforeEach
    void setUp() {
        admin = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_account(id,username,password_hash,role,status)
                VALUES(UUID_TO_BIN(?),?,?,'ADMIN','ACTIVE')
                """, admin.toString(), "s15-it-" + admin, "not-a-real-password-hash");
        Integer cities = jdbc.queryForObject("SELECT COUNT(*) FROM city WHERE name=?", Integer.class, "北京");
        if (cities == null || cities == 0) {
            jdbc.update("""
                    INSERT INTO city(id,city_code,name,timezone,status)
                    VALUES(UUID_TO_BIN(?),?,?,'Asia/Shanghai','ACTIVE')
                    """, UUID.randomUUID().toString(), "S15BJ-" + UUID.randomUUID().toString().substring(0, 8),
                    "北京");
        }
    }

    @Test
    void s06MinimalSetImportsPoiKnowledgeAndRuleAsDrafts() throws Exception {
        KnowledgeModels.SourceView source = service.registerSource(admin,
                new KnowledgeModels.SourceRegistrationRequest("KNOWLEDGE_BASE", "s06-fixture",
                        "s06-" + UUID.randomUUID(), "https://example.invalid/s06", "CC-BY-4.0", null,
                        "DISPLAY", true, Instant.now().plusSeconds(86_400), Map.of("sample", true)));
        UUID sourceId = source.sourceSnapshotId();

        KnowledgeModels.ImportRow poiRow = row("""
                {"row_no":1,"city":"北京","poi_name":"S06 合成导入景点","external_poi_id":"s06-poi-%s",
                 "poi_category":"MUSEUM","district":"东城","longitude":116.4,"latitude":39.9,
                 "coordinate_system":"WGS84","source_snapshot_id":"%s","aliases":["S06 别名"]}
                """.formatted(UUID.randomUUID(), sourceId));
        assertThat(service.previewImport(admin, request("POI", poiRow)).errorCount()).isZero();
        KnowledgeModels.ImportCommitResponse poiCommit = service.commitImport(admin, request("POI", poiRow));
        UUID poiId = poiCommit.createdIds().get(0);
        assertThat(status("poi", poiId)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poi_alias WHERE poi_id=UUID_TO_BIN(?)",
                Integer.class, poiId.toString())).isEqualTo(1);

        KnowledgeModels.ImportRow knowledgeRow = row("""
                {"row_no":1,"city":"北京","poi_id":"%s","entry_key":"s06-kb-%s",
                 "title":"S06 合成景点知识","fact_type":"highlights","fact_value":"合成事实，不代表真实景点",
                 "effective_from":"2026-01-01T00:00:00Z","last_verified_at":"2026-10-01T00:00:00Z",
                 "source_snapshot_id":"%s","chunks":["合成 chunk，仅用于导入验证"]}
                """.formatted(poiId, UUID.randomUUID(), sourceId));
        assertThat(service.previewImport(admin, request("ATTRACTION_KNOWLEDGE", knowledgeRow)).errorCount())
                .isZero();
        KnowledgeModels.ImportCommitResponse knowledgeCommit = service.commitImport(admin,
                request("ATTRACTION_KNOWLEDGE", knowledgeRow));
        UUID knowledgeId = knowledgeCommit.createdIds().get(0);
        assertThat(status("knowledge_entry", knowledgeId)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk WHERE knowledge_entry_id=UUID_TO_BIN(?)",
                Integer.class, knowledgeId.toString())).isEqualTo(1);

        KnowledgeModels.ImportRow ruleRow = row("""
                {"row_no":1,"city":"北京","poi_id":"%s","rule_key":"s06-rule-%s",
                 "ticket_type":"standard","release_time":"20:00","release_timezone":"Asia/Shanghai",
                 "advance_days":7,"currency":"CNY","amount_minor":100,
                 "effective_from":"2026-01-01T00:00:00Z","last_verified_at":"2026-10-01T00:00:00Z",
                 "stale_after_days":30,"source_snapshot_id":"%s"}
                """.formatted(poiId, UUID.randomUUID(), sourceId));
        assertThat(service.previewImport(admin, request("RESERVATION_RULE", ruleRow)).errorCount()).isZero();
        KnowledgeModels.ImportCommitResponse ruleCommit = service.commitImport(admin,
                request("RESERVATION_RULE", ruleRow));
        UUID ruleId = ruleCommit.createdIds().get(0);
        assertThat(status("reservation_rule", ruleId)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT amount_minor FROM reservation_rule WHERE id=UUID_TO_BIN(?)",
                Long.class, ruleId.toString())).isEqualTo(100L);

        List<String> actions = jdbc.queryForList("""
                SELECT action FROM audit_event
                WHERE actor_user_id=UUID_TO_BIN(?) AND action IN (
                  'KNOWLEDGE_POI_CREATE','RESERVATION_RULE_CREATE','KNOWLEDGE_IMPORT_COMMIT')
                """, String.class, admin.toString());
        assertThat(actions).contains("KNOWLEDGE_POI_CREATE", "RESERVATION_RULE_CREATE",
                "KNOWLEDGE_IMPORT_COMMIT");
    }

    private KnowledgeModels.ImportRow row(String json) throws Exception {
        return objectMapper.findAndRegisterModules().readValue(json, KnowledgeModels.ImportRow.class);
    }

    private KnowledgeModels.ImportPreviewRequest request(String type, KnowledgeModels.ImportRow row) {
        return new KnowledgeModels.ImportPreviewRequest(type, List.of(row));
    }

    private String status(String table, UUID id) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id=UUID_TO_BIN(?)", String.class,
                id.toString());
    }
}
