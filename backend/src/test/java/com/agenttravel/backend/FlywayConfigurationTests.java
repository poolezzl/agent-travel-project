package com.agenttravel.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class FlywayConfigurationTests {

    @Test
    void migrationDirectoryContainsNoBusinessMigrations() throws IOException {
        Resource[] versionedMigrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*.sql");

        assertThat(Arrays.stream(versionedMigrations)
                .map(Resource::getFilename)
                .filter(name -> name != null)
                .toList())
                .isEmpty();
    }
}
