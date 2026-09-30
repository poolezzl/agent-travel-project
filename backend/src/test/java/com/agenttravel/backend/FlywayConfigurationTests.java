package com.agenttravel.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class FlywayConfigurationTests {

    @Test
    void migrationDirectoryContainsOnlyTheApprovedInitialMigration() throws IOException {
        Resource[] versionedMigrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*.sql");

        assertThat(Arrays.stream(versionedMigrations)
                .map(Resource::getFilename)
                .filter(name -> name != null)
                .toList())
                .containsExactly("V1__initial_s04_schema.sql");
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
}
