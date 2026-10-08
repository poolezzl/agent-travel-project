package com.agenttravel.backend.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;

@Service
@Lazy
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class AuthService {

    private static final int MAX_FAILURES = 5;
    private final AuthRepository repository;
    private final AuditRepository audit;
    private final PasswordHasher passwordHasher;
    private final JwtTokenService tokens;
    private final LoginRateLimiter rateLimiter;
    private final AuthFailureService failureService;
    private final long refreshDays;

    public AuthService(AuthRepository repository, AuditRepository audit, PasswordHasher passwordHasher,
            JwtTokenService tokens, LoginRateLimiter rateLimiter, AuthFailureService failureService,
            @Value("${app.security.refresh-token-days:30}") long refreshDays) {
        this.repository = repository;
        this.audit = audit;
        this.passwordHasher = passwordHasher;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.failureService = failureService;
        this.refreshDays = refreshDays;
    }

    @Transactional
    public AuthModels.TokenResponse register(AuthModels.RegisterRequest request) {
        validatePassword(request.password());
        if (request.role() != null && !request.role().isBlank() && !"USER".equals(request.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "公开注册只能创建普通用户");
        }
        if (repository.loginExists(request.login())) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "登录名已存在");
        }
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        repository.insertUser(userId, request.login(), passwordHasher.encode(request.password()));
        repository.createPointsAccount(userId, accountId);
        repository.updatePointsBalance(accountId, 100);
        repository.insertLedger(userId, accountId, 100, 0, 100, "grant:user:" + userId);
        audit.write(userId, "REGISTER", "USER", userId, "SUCCESS", null);
        return issue(userId, "USER");
    }

    @Transactional
    public AuthModels.TokenResponse login(AuthModels.LoginRequest request) {
        if (!rateLimiter.allow(request.login())) {
            audit.write(null, "LOGIN", "USER", null, "DENIED", "RATE_LIMITED");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "登录请求过于频繁，请稍后再试");
        }
        AuthRepository.UserRow user = repository.findByLogin(request.login());
        if (user == null) {
            audit.write(null, "LOGIN", "USER", null, "DENIED", "UNAUTHENTICATED");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "登录名或密码错误");
        }
        Instant now = Instant.now();
        boolean accountActive = "ACTIVE".equals(user.status());
        if ("LOCKED".equals(user.status())) {
            if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
                audit.write(user.id(), "LOGIN", "USER", user.id(), "DENIED", "ACCOUNT_LOCKED");
                throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "登录失败次数过多，请稍后再试");
            }
            if (user.lockedUntil() == null) {
                audit.write(user.id(), "LOGIN", "USER", user.id(), "DENIED", "ACCOUNT_LOCKED");
                throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "账户锁定状态无有效截止时间");
            }
            repository.clearExpiredLoginLock(user.id(), now);
            accountActive = true;
        } else if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
            audit.write(user.id(), "LOGIN", "USER", user.id(), "DENIED", "ACCOUNT_LOCKED");
            throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "登录失败次数过多，请稍后再试");
        }
        if (!accountActive) {
            audit.write(user.id(), "LOGIN", "USER", user.id(), "DENIED", "FORBIDDEN");
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "账户当前状态不允许登录");
        }
        if (!passwordHasher.matches(request.password(), user.passwordHash())) {
            AuthFailureService.FailureResult failure = failureService.recordFailure(user.id(), now);
            throw new ApiException(failure.status(), failure.locked() ? "ACCOUNT_LOCKED" : "UNAUTHENTICATED", "登录名或密码错误");
        }
        AuthRepository.UserRow lockedUser = repository.findByLoginForUpdate(request.login());
        if (lockedUser != null) {
            user = lockedUser;
            if ("LOCKED".equals(user.status())) {
                if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
                    audit.write(user.id(), "LOGIN", "USER", user.id(), "DENIED", "ACCOUNT_LOCKED");
                    throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "登录失败次数过多，请稍后再试");
                }
                repository.clearExpiredLoginLock(user.id(), now);
            } else if (!"ACTIVE".equals(user.status())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "账户当前状态不允许登录");
            }
        }
        repository.markLoginSuccess(user.id(), now);
        audit.write(user.id(), "LOGIN", "USER", user.id(), "SUCCESS", null);
        return issue(user.id(), user.role());
    }

    @Transactional
    public AuthModels.TokenResponse refresh(AuthModels.RefreshRequest request) {
        Instant now = Instant.now();
        AuthRepository.SessionRow session = repository.findSessionForUpdate(tokens.hashRefreshToken(request.refreshToken()));
        if (session == null || session.revokedAt() != null || !session.expiresAt().isAfter(now)) {
            audit.write(null, "REFRESH", "SESSION", null, "DENIED", "UNAUTHENTICATED");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "刷新令牌无效或已失效");
        }
        repository.revokeSession(session.id(), now);
        audit.write(session.userId(), "REFRESH_ROTATE", "SESSION", session.id(), "SUCCESS", null);
        return issue(session.userId(), session.role());
    }

    @Transactional
    public void logout(UUID userId, String refreshToken) {
        AuthRepository.SessionRow session = repository.findSessionForUpdate(tokens.hashRefreshToken(refreshToken));
        if (session != null && session.userId().equals(userId)) {
            repository.revokeSession(session.id(), Instant.now());
        }
        repository.revokeAllSessions(userId, Instant.now());
        audit.write(userId, "LOGOUT", "SESSION", userId, "SUCCESS", null);
    }

    public AuthModels.CurrentUser currentUser(UUID userId) {
        AuthRepository.UserRow user = repository.findById(userId);
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "用户不存在或已删除");
        }
        return new AuthModels.CurrentUser(user.id(), user.login(), user.role(), user.status(), user.createdAt());
    }

    private AuthModels.TokenResponse issue(UUID userId, String role) {
        long now = Instant.now().getEpochSecond();
        String refresh = tokens.randomRefreshToken();
        UUID sessionId = UUID.randomUUID();
        repository.insertSession(sessionId, userId, tokens.hashRefreshToken(refresh), Instant.ofEpochSecond(now),
                Instant.ofEpochSecond(now).plus(Duration.ofDays(refreshDays)));
        return new AuthModels.TokenResponse(tokens.createAccessToken(userId, role, sessionId, now), refresh,
                tokens.accessSeconds(), userId, role);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "密码长度必须为 12 至 128 位");
        }
    }
}
