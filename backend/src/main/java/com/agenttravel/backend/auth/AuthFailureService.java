package com.agenttravel.backend.auth;

import java.time.Instant;
import java.util.UUID;

import com.agenttravel.backend.common.AuditRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class AuthFailureService {

    private static final int MAX_FAILURES = 5;
    private final AuthRepository repository;
    private final AuditRepository audit;

    public AuthFailureService(AuthRepository repository, AuditRepository audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureResult recordFailure(UUID userId, Instant now) {
        repository.incrementLoginFailure(userId, now, MAX_FAILURES, 15);
        AuthRepository.LoginFailureRow row = repository.loginFailureState(userId);
        boolean locked = row.failedLoginCount() >= MAX_FAILURES
                && row.lockedUntil() != null && row.lockedUntil().isAfter(now);
        audit.write(userId, "LOGIN", "USER", userId, "DENIED", locked ? "ACCOUNT_LOCKED" : "UNAUTHENTICATED");
        return new FailureResult(locked, locked ? HttpStatus.LOCKED : HttpStatus.UNAUTHORIZED);
    }

    public record FailureResult(boolean locked, HttpStatus status) {
    }
}
