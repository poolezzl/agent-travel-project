package com.agenttravel.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class DatabaseConfigurationTests {

    @Test
    void localProfileFailsWhenMysqlIsUnavailable() {
        ConfigurableApplicationContext context = null;
        try {
            context = new SpringApplicationBuilder(BackendApplication.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "spring.profiles.active=local",
                            "spring.main.log-startup-info=false",
                            "spring.datasource.url=jdbc:mysql://127.0.0.1:1/s03_unavailable",
                            "spring.datasource.username=trip_planner",
                            "spring.datasource.password=",
                            "spring.datasource.hikari.connection-timeout=100",
                            "spring.datasource.hikari.initialization-fail-timeout=1",
                            "spring.flyway.connect-retries=0")
                    .run();

            throw new AssertionError("local profile unexpectedly connected to unavailable MySQL");
        } catch (Exception exception) {
            assertThat(exception).hasRootCauseInstanceOf(SQLException.class);
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }
}
