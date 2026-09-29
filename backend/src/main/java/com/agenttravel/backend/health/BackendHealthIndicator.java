package com.agenttravel.backend.health;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.core.env.Environment;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("backend")
public class BackendHealthIndicator implements HealthIndicator {

    private final Environment environment;

    public BackendHealthIndicator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Health health() {
        boolean externalServicesEnabled = environment.getProperty(
                "app.external-services.enabled", Boolean.class, false);
        String profiles = Arrays.stream(environment.getActiveProfiles())
                .collect(Collectors.joining(","));

        return Health.up()
                .withDetail("application", environment.getProperty("spring.application.name"))
                .withDetail("profiles", profiles.isBlank() ? "default" : profiles)
                .withDetail("databaseMode", environment.getProperty("app.database.mode", "mysql"))
                .withDetail("externalServices", externalServicesEnabled ? "enabled" : "disabled")
                .build();
    }
}
