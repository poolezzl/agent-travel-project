package com.agenttravel.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class FlywayConfigurationTests {

    @Test
    void migrationDirectoryContainsV1ThroughV3() throws IOException {
        Resource[] versionedMigrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*.sql");

        assertThat(Arrays.stream(versionedMigrations)
                .map(Resource::getFilename)
                .filter(name -> name != null)
                .toList())
                .containsExactlyInAnyOrder("V1__initial_s04_schema.sql",
                        "V2__s15_knowledge_and_reservation_rules.sql",
                        "V3__s20_allow_identical_version_content.sql");
    }

    @Test
    void initialMigrationContainsFrozenPointsConstraints() throws IOException {
        Resource migration = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V1__initial_s04_schema.sql");

        String sql = new String(migration.getInputStream().readAllBytes());

        assertThat(sql).contains("CONSTRAINT ck_quote_expiry CHECK(expires_at>created_at)");
        assertThat(sql).contains("CONSTRAINT ck_ledger_direction CHECK(");
        assertThat(sql).contains("entry_type='RESERVE' AND points_delta<0");
        assertThat(sql).contains("entry_type='CONFIRM' AND points_delta=0");
    }

    @Test
    void initialMigrationWasNotRewrittenByV2() throws IOException {
        String v1 = migration("V1__initial_s04_schema.sql");
        String v2 = migration("V2__s15_knowledge_and_reservation_rules.sql");

        // V1 keeps its original frozen schema and status checks; S15 additions live only in V2.
        assertThat(v1).contains("CREATE TABLE knowledge_entry");
        assertThat(v1).contains("CREATE TABLE reservation_rule");
        assertThat(v1).contains("CONSTRAINT ck_knowledge_status CHECK(status IN('DRAFT','REVIEW','PUBLISHED','DISABLED'))");
        assertThat(v1).contains("CONSTRAINT ck_rule_status CHECK(status IN('DRAFT','REVIEW','PUBLISHED','DISABLED'))");
        assertThat(v1).doesNotContain("poi_merge_candidate", "import_batch", "ROLLED_BACK", "stale_after_days");

        assertThat(v2).contains("ALTER TABLE knowledge_entry");
        assertThat(v2).contains("ALTER TABLE reservation_rule");
        assertThat(v2).contains("CREATE TABLE poi_merge_candidate");
        assertThat(v2).contains("CREATE TABLE import_batch");
        assertThat(v2).contains("ROLLED_BACK");
        assertThat(v2).contains("stale_after_days");
    }

    private String migration(String filename) throws IOException {
        Resource migration = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/" + filename);
        return new String(migration.getInputStream().readAllBytes());
    }
}
