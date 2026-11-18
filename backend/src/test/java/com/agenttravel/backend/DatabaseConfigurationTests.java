package com.agenttravel.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Objects;
import java.util.stream.Stream;

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
                            "spring.datasource.username=trip_planner",
                            "spring.datasource.password=",
                            "spring.datasource.hikari.connection-timeout=100",
                            "spring.datasource.hikari.initialization-fail-timeout=1",
                            "spring.flyway.connect-retries=0")
                    .run("--spring.datasource.url=jdbc:mysql://127.0.0.1:1/s03_unavailable",
                            "--app.security.jwt-secret=test-only-database-configuration-verification");

            throw new AssertionError("local profile unexpectedly connected to unavailable MySQL");
        } catch (Exception exception) {
            assertThat(Stream.<Throwable>iterate(exception, Objects::nonNull, Throwable::getCause)
                    .anyMatch(SQLException.class::isInstance))
                    .as("startup fails because JDBC cannot connect")
                    .isTrue();
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }
}
