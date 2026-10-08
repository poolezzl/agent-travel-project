package com.agenttravel.backend;

import com.agenttravel.backend.auth.JwtTokenService;
import com.agenttravel.backend.auth.PasswordHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class BackendBusinessConfiguration {

    @Bean
    PasswordHasher passwordHasher() {
        return new PasswordHasher();
    }

    @Bean
    JwtTokenService jwtTokenService(@Value("${app.security.jwt-secret}") String secret,
            @Value("${app.security.access-token-seconds:900}") long accessSeconds) {
        return new JwtTokenService(secret, accessSeconds);
    }
}
