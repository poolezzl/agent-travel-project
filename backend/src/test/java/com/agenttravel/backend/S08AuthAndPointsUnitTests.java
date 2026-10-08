package com.agenttravel.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import com.agenttravel.backend.auth.AuthFailureService;
import com.agenttravel.backend.auth.AuthModels;
import com.agenttravel.backend.auth.AuthRepository;
import com.agenttravel.backend.auth.AuthService;
import com.agenttravel.backend.auth.AccessTokenFilter;
import com.agenttravel.backend.auth.JwtTokenService;
import com.agenttravel.backend.auth.LoginRateLimiter;
import com.agenttravel.backend.auth.PasswordHasher;
import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import com.agenttravel.backend.common.RequestContext;
import com.agenttravel.backend.points.PointsController;
import com.agenttravel.backend.points.PointsModels;
import com.agenttravel.backend.points.PointsRepository;
import com.agenttravel.backend.points.PointsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class S08AuthAndPointsUnitTests {

    @AfterEach
    void clearRequestContext() {
        RequestContext.clear();
    }

    @Test
    void passwordHashDoesNotStorePasswordAndVerifiesOnlyMatchingValue() {
        PasswordHasher hasher = new PasswordHasher();
        String password = "correct-horse-battery";
        String encoded = hasher.encode(password);

        assertThat(encoded).doesNotContain(password);
        assertThat(hasher.matches(password, encoded)).isTrue();
        assertThat(hasher.matches("wrong-password", encoded)).isFalse();
    }

    @Test
    void accessTokenRejectsForgeryAndExpiry() {
        JwtTokenService service = new JwtTokenService("01234567890123456789012345678901", 900);
        UUID userId = UUID.randomUUID();
        String token = service.createAccessToken(userId, "USER", 100);

        assertThat(service.parseAccessToken(token, 101).userId()).isEqualTo(userId);
        assertThatThrownBy(() -> service.parseAccessToken(token + "x", 101))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.parseAccessToken(token, 1000))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void quoteUsesServerPricingForOneTwoAndThreePlans() {
        PointsRepository repository = mock(PointsRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        PointsService service = new PointsService(repository, audit);
        UUID userId = UUID.randomUUID();
        when(repository.findQuoteByReference(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(userId)))
                .thenReturn(null);

        assertThat(service.quote(userId, new PointsModels.QuoteRequest(1, null), "one").points()).isEqualTo(10);
        assertThat(service.quote(userId, new PointsModels.QuoteRequest(2, null), "two").points()).isEqualTo(18);
        assertThat(service.quote(userId, new PointsModels.QuoteRequest(3, null), "three").points()).isEqualTo(25);
    }

    @Test
    void repeatedReserveReturnsStoredEconomicEffect() {
        PointsRepository repository = mock(PointsRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        PointsService service = new PointsService(repository, audit);
        UUID userId = UUID.randomUUID();
        String key = "same-request";
        PointsRepository.LedgerRow stored = new PointsRepository.LedgerRow("reserve:" + userId + ":" + key, -10, 90, userId);
        when(repository.findLedger("reserve:" + userId + ":" + key)).thenReturn(stored);
        when(repository.lockAccount(userId)).thenReturn(new PointsRepository.AccountRow(UUID.randomUUID(), 90));

        PointsModels.PointsResponse response = service.reserve(userId,
                new PointsModels.ReserveRequest("quote-reference", null), key);

        assertThat(response.reused()).isTrue();
        assertThat(response.pointsDelta()).isEqualTo(-10);
        verify(repository).findLedger("reserve:" + userId + ":" + key);
    }

    @Test
    void registrationCreatesOneHundredPointBalanceAndLedger() {
        AuthRepository repository = mock(AuthRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        AuthFailureService failureService = mock(AuthFailureService.class);
        JwtTokenService tokens = new JwtTokenService("01234567890123456789012345678901", 900);
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(repository.loginExists("new-user")).thenReturn(false);

        AuthService service = new AuthService(repository, audit, new PasswordHasher(), tokens,
                new LoginRateLimiter(), failureService, 30);
        AuthModels.TokenResponse response = service.register(
                new AuthModels.RegisterRequest("new-user", "correct-horse-battery", null));

        assertThat(response.userId()).isNotNull();
        verify(repository).updatePointsBalance(any(UUID.class), eq(100L));
        verify(repository).insertLedger(any(UUID.class), any(UUID.class), eq(100L), eq(0L), eq(100L),
                anyString());
    }

    @Test
    void fifthFailureLocksAccountAndUsesIndependentTransaction() throws NoSuchMethodException {
        AuthRepository repository = mock(AuthRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-08T00:00:00Z");
        when(repository.loginFailureState(userId))
                .thenReturn(new AuthRepository.LoginFailureRow(1, null))
                .thenReturn(new AuthRepository.LoginFailureRow(2, null))
                .thenReturn(new AuthRepository.LoginFailureRow(3, null))
                .thenReturn(new AuthRepository.LoginFailureRow(4, null))
                .thenReturn(new AuthRepository.LoginFailureRow(5, now.plusSeconds(900)));
        AuthFailureService service = new AuthFailureService(repository, audit);

        java.util.List<AuthFailureService.FailureResult> results = new java.util.ArrayList<>();
        for (int attempt = 0; attempt < 5; attempt++) {
            results.add(service.recordFailure(userId, now));
        }

        assertThat(results).hasSize(5);
        assertThat(results.subList(0, 4)).allMatch(result -> !result.locked()
                && result.status().equals(HttpStatus.UNAUTHORIZED));
        assertThat(results.get(4).locked()).isTrue();
        assertThat(results.get(4).status()).isEqualTo(HttpStatus.LOCKED);
        assertThat(results.get(4).locked()).isTrue();
        verify(repository, org.mockito.Mockito.times(5)).incrementLoginFailure(userId, now, 5, 15);
        assertThat(AuthFailureService.class.getMethod("recordFailure", UUID.class, Instant.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class)
                .propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
    }

    @Test
    void lockedAccountRemainsRejectedBeforeLockDeadline() {
        AuthRepository repository = mock(AuthRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        AuthFailureService failureService = mock(AuthFailureService.class);
        JwtTokenService tokens = new JwtTokenService("01234567890123456789012345678901", 900);
        UUID userId = UUID.randomUUID();
        when(repository.findByLogin("locked-user")).thenReturn(new AuthRepository.UserRow(userId, "locked-user",
                "unused-password-hash", "LOCKED", "LOCKED", 5, Instant.now().plusSeconds(900), Instant.now()));
        AuthService service = new AuthService(repository, audit, new PasswordHasher(), tokens,
                new LoginRateLimiter(), failureService, 30);

        assertThatThrownBy(() -> service.login(new AuthModels.LoginRequest("locked-user", "correct-horse-battery")))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).status())
                .isEqualTo(HttpStatus.LOCKED);
        verify(repository, never()).clearExpiredLoginLock(eq(userId), any(Instant.class));
        verify(repository, never()).markLoginSuccess(eq(userId), any(Instant.class));
    }

    @Test
    void expiredLockIsClearedAndSuccessfulLoginIsAllowed() throws NoSuchMethodException {
        AuthRepository repository = mock(AuthRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        AuthFailureService failureService = mock(AuthFailureService.class);
        PasswordHasher passwordHasher = new PasswordHasher();
        JwtTokenService tokens = new JwtTokenService("01234567890123456789012345678901", 900);
        UUID userId = UUID.randomUUID();
        String password = "correct-horse-battery";
        when(repository.findByLogin("expired-lock-user")).thenReturn(new AuthRepository.UserRow(userId,
                "expired-lock-user", passwordHasher.encode(password), "USER", "LOCKED", 5,
                Instant.now().minusSeconds(1), Instant.now()));
        AuthService service = new AuthService(repository, audit, passwordHasher, tokens,
                new LoginRateLimiter(), failureService, 30);

        AuthModels.TokenResponse response = service.login(new AuthModels.LoginRequest("expired-lock-user", password));

        assertThat(response).isNotNull();
        verify(repository).clearExpiredLoginLock(eq(userId), any(Instant.class));
        verify(repository).markLoginSuccess(eq(userId), any(Instant.class));
        assertThat(AuthService.class.getMethod("login", AuthModels.LoginRequest.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = { "DISABLED", "DELETED" })
    void nonActiveAccountCannotLoginOrBeActivated(String accountStatus) {
        AuthRepository repository = mock(AuthRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        AuthFailureService failureService = mock(AuthFailureService.class);
        JwtTokenService tokens = new JwtTokenService("01234567890123456789012345678901", 900);
        UUID userId = UUID.randomUUID();
        when(repository.findByLogin("blocked-user")).thenReturn(new AuthRepository.UserRow(userId, "blocked-user",
                "unused-password-hash", "USER", accountStatus, 0, null, Instant.now()));
        AuthService service = new AuthService(repository, audit, new PasswordHasher(), tokens,
                new LoginRateLimiter(), failureService, 30);

        assertThatThrownBy(() -> service.login(new AuthModels.LoginRequest("blocked-user", "correct-horse-battery")))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).status())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(repository, never()).markLoginSuccess(eq(userId), any(Instant.class));
        verify(failureService, never()).recordFailure(eq(userId), any(Instant.class));
    }

    @Test
    void settlementRejectsAnotherUsersReservation() {
        PointsRepository repository = mock(PointsRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        PointsService service = new PointsService(repository, audit);
        UUID currentUser = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        when(repository.findLedger("reserve:foreign")).thenReturn(new PointsRepository.LedgerRow("reserve:foreign", -10, 90, otherUser));

        assertThatThrownBy(() -> service.refund(currentUser,
                new PointsModels.SettlementRequest("foreign"), "refund-key"))
                .isInstanceOf(ApiException.class)
                .hasMessage("预扣记录不存在");
    }

    @Test
    void changingRefundKeyDoesNotCreateSecondRefundLedger() {
        PointsRepository repository = mock(PointsRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        PointsService service = new PointsService(repository, audit);
        UUID userId = UUID.randomUUID();
        String reserveReference = "reserve:" + userId + ":reserve-key";
        PointsRepository.LedgerRow reserve = new PointsRepository.LedgerRow(reserveReference, -10, 90, userId);
        PointsRepository.AccountRow account = new PointsRepository.AccountRow(UUID.randomUUID(), 90);
        AtomicInteger refundLookups = new AtomicInteger();
        when(repository.findLedger(anyString())).thenAnswer(invocation -> {
            String reference = invocation.getArgument(0);
            if (reserveReference.equals(reference)) {
                return reserve;
            }
            if (reference.startsWith("refund:" + userId + ":") && refundLookups.getAndIncrement() > 0) {
                return new PointsRepository.LedgerRow(reference, 10, 100, userId);
            }
            return null;
        });
        when(repository.lockAccount(userId)).thenReturn(account);

        PointsModels.PointsResponse first = service.refund(userId,
                new PointsModels.SettlementRequest(reserveReference), "refund-key-one");
        PointsModels.PointsResponse second = service.refund(userId,
                new PointsModels.SettlementRequest(reserveReference), "refund-key-two");

        assertThat(first.reused()).isFalse();
        assertThat(second.reused()).isTrue();
        verify(repository, org.mockito.Mockito.times(1)).insertLedger(eq(account.id()), eq(userId), any(), any(),
                eq("REFUND"), eq(10L), eq(90L), eq(100L), anyString());
        verify(repository, org.mockito.Mockito.times(1)).updateBalance(account.id(), 100L);
    }

    @Test
    void grantIdempotencyReusesSameTargetAndRejectsChangedTarget() {
        PointsRepository repository = mock(PointsRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        PointsService service = new PointsService(repository, audit);
        UUID adminId = UUID.randomUUID();
        UUID firstTarget = UUID.randomUUID();
        UUID changedTarget = UUID.randomUUID();
        String reference = "grant:manual:" + adminId + ":same-grant-key";
        when(repository.findLedger(reference)).thenReturn(
                new PointsRepository.LedgerRow(reference, 10, 110, firstTarget));
        when(repository.lockAccount(adminId)).thenReturn(new PointsRepository.AccountRow(UUID.randomUUID(), 0));
        when(repository.lockAccount(firstTarget)).thenReturn(new PointsRepository.AccountRow(UUID.randomUUID(), 100));
        when(repository.lockAccount(changedTarget)).thenReturn(new PointsRepository.AccountRow(UUID.randomUUID(), 100));

        PointsModels.PointsResponse repeated = service.grant(adminId,
                new PointsModels.GrantRequest(firstTarget, 10), "same-grant-key");
        assertThat(repeated.reused()).isTrue();

        assertThatThrownBy(() -> service.grant(adminId,
                new PointsModels.GrantRequest(changedTarget, 10), "same-grant-key"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("DUPLICATE_REQUEST");
        verify(repository, never()).insertLedger(any(UUID.class), any(UUID.class), any(), any(), anyString(),
                anyLong(), anyLong(), anyLong(), anyString());
        verify(repository, never()).updateBalance(any(UUID.class), anyLong());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository);
        order.verify(repository).lockAccount(adminId);
        order.verify(repository).lockAccount(firstTarget);
        order.verify(repository).findLedger(reference);
        order.verify(repository).lockAccount(adminId);
        order.verify(repository).lockAccount(changedTarget);
        order.verify(repository).findLedger(reference);
    }

    @Test
    void concurrentGrantWithSameKeyCreatesOnlyOneLedgerEntry() throws Exception {
        PointsRepository repository = mock(PointsRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        PointsService service = new PointsService(repository, audit);
        UUID adminId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String key = "concurrent-grant-key";
        String reference = "grant:manual:" + adminId + ":" + key;
        ReentrantLock accountLock = new ReentrantLock();
        ThreadLocal<Integer> heldLocks = ThreadLocal.withInitial(() -> 0);
        AtomicReference<PointsRepository.LedgerRow> ledger = new AtomicReference<>();
        AtomicInteger inserts = new AtomicInteger();
        PointsRepository.AccountRow account = new PointsRepository.AccountRow(UUID.randomUUID(), 100);
        when(repository.lockAccount(any(UUID.class))).thenAnswer(invocation -> {
            accountLock.lock();
            heldLocks.set(heldLocks.get() + 1);
            return account;
        });
        when(repository.findLedger(reference)).thenAnswer(invocation -> {
            PointsRepository.LedgerRow result = ledger.get();
            if (result != null) {
                releaseLocks(accountLock, heldLocks);
            }
            return result;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            inserts.incrementAndGet();
            ledger.set(new PointsRepository.LedgerRow(reference, 10, 110, targetId));
            return null;
        }).when(repository).insertLedger(any(UUID.class), eq(targetId), any(), any(), eq("GRANT"), eq(10L),
                eq(100L), eq(110L), eq(reference));
        org.mockito.Mockito.doAnswer(invocation -> {
            releaseLocks(accountLock, heldLocks);
            return null;
        }).when(repository).updateBalance(any(UUID.class), eq(110L));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PointsModels.PointsResponse> first = executor.submit(() -> {
                start.await();
                return service.grant(adminId, new PointsModels.GrantRequest(targetId, 10), key);
            });
            Future<PointsModels.PointsResponse> second = executor.submit(() -> {
                start.await();
                return service.grant(adminId, new PointsModels.GrantRequest(targetId, 10), key);
            });
            start.countDown();
            PointsModels.PointsResponse firstResponse = first.get();
            PointsModels.PointsResponse secondResponse = second.get();

            assertThat(inserts).hasValue(1);
            assertThat(java.util.stream.Stream.of(firstResponse, secondResponse)
                    .filter(PointsModels.PointsResponse::reused).count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void releaseLocks(ReentrantLock lock, ThreadLocal<Integer> heldLocks) {
        int count = heldLocks.get();
        while (count > 0) {
            lock.unlock();
            count--;
        }
        heldLocks.remove();
    }

    @Test
    void logoutRevokesAllSessions() {
        AuthRepository repository = mock(AuthRepository.class);
        AuditRepository audit = mock(AuditRepository.class);
        JwtTokenService tokens = new JwtTokenService("01234567890123456789012345678901", 900);
        AuthService service = new AuthService(repository, audit, new PasswordHasher(), tokens,
                new LoginRateLimiter(), mock(AuthFailureService.class), 30);
        UUID userId = UUID.randomUUID();

        service.logout(userId, "refresh-token");

        verify(repository).revokeAllSessions(eq(userId), any(Instant.class));
    }

    @Test
    void revokedAccessTokenReturnsCompleteProblemDetails() throws Exception {
        JwtTokenService tokens = new JwtTokenService("01234567890123456789012345678901", 900);
        AuthRepository repository = mock(AuthRepository.class);
        AccessTokenFilter filter = new AccessTokenFilter(tokens, repository, new ObjectMapper().findAndRegisterModules());
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        long now = Instant.now().getEpochSecond();
        RequestContext.set("request-1", "trace-1", null, null);
        when(repository.findActiveAccessIdentity(eq(sessionId), eq(userId), any(Instant.class))).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokens.createAccessToken(userId, "USER", sessionId, now));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String body = response.getContentAsString();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(body).contains("\"detail\"").contains("\"code\"").contains("\"traceId\"")
                .contains("\"timestamp\"");
        verify(chain, never()).doFilter(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = { "DISABLED", "DELETED" })
    void existingAccessTokenIsRejectedWhenAccountIsNoLongerActive(String accountStatus) throws Exception {
        JwtTokenService tokens = new JwtTokenService("01234567890123456789012345678901", 900);
        AuthRepository repository = mock(AuthRepository.class);
        AccessTokenFilter filter = new AccessTokenFilter(tokens, repository, new ObjectMapper().findAndRegisterModules());
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        long now = Instant.now().getEpochSecond();
        RequestContext.set("request-1", "trace-1", null, null);
        when(repository.findActiveAccessIdentity(eq(sessionId), eq(userId), any(Instant.class)))
                .thenReturn(new AuthRepository.AccessIdentity(accountStatus, "USER"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokens.createAccessToken(userId, "USER", sessionId, now));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"detail\"").contains("\"code\"")
                .contains("\"traceId\"").contains("\"timestamp\"");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void existingAccessTokenUsesCurrentDatabaseRole() throws Exception {
        JwtTokenService tokens = new JwtTokenService("01234567890123456789012345678901", 900);
        AuthRepository repository = mock(AuthRepository.class);
        AccessTokenFilter filter = new AccessTokenFilter(tokens, repository, new ObjectMapper().findAndRegisterModules());
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        long now = Instant.now().getEpochSecond();
        RequestContext.set("request-1", "trace-1", null, null);
        when(repository.findActiveAccessIdentity(eq(sessionId), eq(userId), any(Instant.class)))
                .thenReturn(new AuthRepository.AccessIdentity("ACTIVE", "USER"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokens.createAccessToken(userId, "ADMIN", sessionId, now));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (ignoredRequest, ignoredResponse) -> assertThat(RequestContext.get().role()).isEqualTo("USER");

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void controllerPassesRequestHeaderIdempotencyKeyToEveryWriteOperation() {
        PointsService service = mock(PointsService.class);
        PointsController controller = new PointsController(service);
        UUID userId = UUID.randomUUID();
        RequestContext.set("request-1", "trace-1", userId, "ADMIN");
        PointsModels.QuoteRequest quoteRequest = new PointsModels.QuoteRequest(1, null);
        PointsModels.ReserveRequest reserveRequest = new PointsModels.ReserveRequest("quote", null);
        PointsModels.SettlementRequest settlementRequest = new PointsModels.SettlementRequest("reserve");
        PointsModels.GrantRequest grantRequest = new PointsModels.GrantRequest(userId, 10);
        when(service.quote(userId, quoteRequest, "key-quote"))
                .thenReturn(new PointsModels.QuoteResponse(UUID.randomUUID(), "quote", 1, 10, 1));
        when(service.reserve(userId, reserveRequest, "key-reserve"))
                .thenReturn(new PointsModels.PointsResponse("reserve", -10, 90, false));
        when(service.confirm(userId, settlementRequest, "key-confirm"))
                .thenReturn(new PointsModels.PointsResponse("confirm", 0, 90, false));
        when(service.refund(userId, settlementRequest, "key-refund"))
                .thenReturn(new PointsModels.PointsResponse("refund", 10, 100, false));
        when(service.grant(userId, grantRequest, "key-grant"))
                .thenReturn(new PointsModels.PointsResponse("grant", 10, 110, false));

        controller.quote(quoteRequest, "key-quote");
        controller.reserve(reserveRequest, "key-reserve");
        controller.confirm(settlementRequest, "key-confirm");
        controller.refund(settlementRequest, "key-refund");
        controller.grant(grantRequest, "key-grant");

        verify(service).quote(userId, quoteRequest, "key-quote");
        verify(service).reserve(userId, reserveRequest, "key-reserve");
        verify(service).confirm(userId, settlementRequest, "key-confirm");
        verify(service).refund(userId, settlementRequest, "key-refund");
        verify(service).grant(userId, grantRequest, "key-grant");
    }

    @Test
    void ordinaryUserCannotGrantPoints() {
        PointsService service = mock(PointsService.class);
        PointsController controller = new PointsController(service);
        UUID userId = UUID.randomUUID();
        RequestContext.set("request-1", "trace-1", userId, "USER");

        assertThatThrownBy(() -> controller.grant(new PointsModels.GrantRequest(userId, 10), "grant-key"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).status())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).grant(any(UUID.class), any(PointsModels.GrantRequest.class), anyString());
    }

    @Test
    void loginRateLimiterStopsTheTwentyFirstAttemptInOneMinute() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.allow("synthetic-login")).isTrue();
        }
        assertThat(limiter.allow("synthetic-login")).isFalse();
    }
}
