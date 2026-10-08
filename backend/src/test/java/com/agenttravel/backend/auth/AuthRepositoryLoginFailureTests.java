package com.agenttravel.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AuthRepositoryLoginFailureTests {

    @Test
    void fifthFailureUpdateSetsLockAndDeadlineTogetherBeforeIncrementingCount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicReference<Object[]> arguments = new AtomicReference<>();
        doAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            arguments.set(Arrays.copyOfRange(invocation.getArguments(), 1, invocation.getArguments().length));
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));
        AuthRepository repository = new AuthRepository(jdbc);
        Instant now = Instant.parse("2026-10-08T00:00:00Z");

        repository.incrementLoginFailure(UUID.randomUUID(), now, 5, 15);

        assertThat(sql.get()).contains("status=CASE WHEN failed_login_count+1>=? THEN 'LOCKED'")
                .contains("locked_until=CASE WHEN failed_login_count+1>=? THEN DATE_ADD")
                .contains("failed_login_count=failed_login_count+1");
        assertThat(arguments.get()).hasSize(6);
        assertThat(arguments.get()[0]).isEqualTo(5);
        assertThat(arguments.get()[1]).isEqualTo(5);
        assertThat(arguments.get()[3]).isEqualTo(15);
    }
}
