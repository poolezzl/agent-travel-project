package com.agenttravel.backend.points;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.agenttravel.backend.auth.JwtTokenService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@EnabledIfEnvironmentVariable(named = "S25_IT", matches = "true")
@SpringBootTest(properties = {
        "app.database.mode=mysql",
        "app.security.jwt-secret=s25-integration-test-only-signing-key",
        "app.s11.enabled=false",
        "app.wallet.reconciliation-cron=-"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class S25WalletIntegrationTests {

    private static final String PREFIX = "/api/v1/points";
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenService tokens;
    @Autowired PointsService points;
    @Autowired WalletRepository wallet;
    @Autowired WalletReconciliation reconciliation;

    @Test
    void repeatedQuoteAndConfirmationApplyOnlyOneTopUp() throws Exception {
        Account member = account("USER", 100);
        http.perform(get(PREFIX + "/packages").header("Authorization", member.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].points").value(contains(50, 100, 200)));
        String quote = topUpQuote(member, "standard", "quote");
        assertThat(topUpQuote(member, "standard", "quote")).isEqualTo(quote);
        send(member, "/top-up/quotes", "{\"packageCode\":\"large\"}", "quote")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        send(member, "/top-up/quotes", "{\"packageCode\":\"fake\"}", "fake")
                .andExpect(status().isBadRequest());

        var results = parallel(() -> confirm(member, quote, "confirm").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(results.stream().map(body -> JsonPath.<Boolean>read(body, "$.reused")))
                .containsExactlyInAnyOrder(false, true);
        confirm(member, quote, "changed-key").andExpect(status().isConflict());
        confirm(member, topUpQuote(member, "small", "new-quote"), "confirm")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(points.balance(member.id()).balancePoints()).isEqualTo(200);
        assertThat(entries(member, "TOP_UP")).isEqualTo(1);
    }

    @Test
    void simultaneousNegativeAdjustmentsCannotOverdrawOrUseStaleBalance() throws Exception {
        Account member = account("USER", 100);
        Account admin = account("ADMIN", 100);
        String first = adjustmentQuote(admin, member, -80, "first");
        String second = adjustmentQuote(admin, member, -80, "second");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var results = parallel(() -> {
            boolean initial = calls.getAndIncrement() == 0;
            return confirm(admin, initial ? first : second, initial ? "first" : "second")
                    .andReturn().getResponse().getStatus();
        });
        assertThat(results).containsExactlyInAnyOrder(200, 409);
        assertThat(points.balance(member.id()).balancePoints()).isEqualTo(20);
        assertThat(entries(member, "ADMIN_ADJUST")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event
                WHERE action='POINTS_ADMIN_ADJUST' AND actor_user_id=UUID_TO_BIN(?) AND outcome='SUCCESS'
                """, Integer.class, admin.id().toString())).isEqualTo(1);

        String stale = adjustmentQuote(admin, member, 10, "stale");
        confirm(member, topUpQuote(member, "small", "top-up"), "top-up").andExpect(status().isOk());
        confirm(admin, stale, "stale-confirm").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENCY_CONFLICT"));
        assertThat(points.balance(member.id()).balancePoints()).isEqualTo(70);
    }

    @Test
    void walletReadsAndWritesEnforceOwnershipAndCurrentAdminRole() throws Exception {
        Account admin = account("ADMIN", 100);
        Account member = account("USER", 100);
        Account other = account("USER", 100);
        String quote = adjustmentQuote(admin, member, 20, "admin-quote");
        String otherQuote = topUpQuote(other, "small", "other-quote");
        confirm(member, otherQuote, "foreign").andExpect(status().isNotFound());
        send(member, "/admin/adjustments/quotes", adjustment(other, 25), "forged-role")
                .andExpect(status().isForbidden());
        http.perform(get(PREFIX + "/admin/ledger").header("Authorization", member.bearer()))
                .andExpect(status().isForbidden());
        http.perform(get(PREFIX + "/ledger")).andExpect(status().isUnauthorized());
        http.perform(get(PREFIX + "/ledger").param("userId", other.id().toString())
                        .header("Authorization", member.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[*].userId").value(contains(member.id().toString())));
        http.perform(get(PREFIX + "/admin/ledger").param("userId", other.id().toString())
                        .header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[*].userId").value(contains(other.id().toString())));
        send(admin, "/admin/adjustments/quotes", "{\"userId\":\"" + other.id() + "\",\"pointsDelta\":1.5}", "fraction")
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE user_account SET role='USER' WHERE id=UUID_TO_BIN(?)", admin.id().toString());
        confirm(admin, quote, "demoted").andExpect(status().isForbidden());
        assertThat(entries(member, "ADMIN_ADJUST")).isZero();
    }

    @Test
    void refundRetryRestoresBalanceAndAppearsOnceInLedger() throws Exception {
        Account member = account("USER", 100);
        var quote = points.quote(member.id(), new PointsModels.QuoteRequest(1, null), "quote");
        var reserve = points.reserve(member.id(), new PointsModels.ReserveRequest(quote.quoteReference(), null),
                "reserve");
        var request = new PointsModels.SettlementRequest(reserve.businessReference());

        var results = parallel(() -> points.refund(member.id(), request, "retry-refund").reused());

        assertThat(results).containsExactlyInAnyOrder(false, true);
        assertThat(points.balance(member.id()).balancePoints()).isEqualTo(100);
        assertThat(entries(member, "REFUND")).isEqualTo(1);
        http.perform(get(PREFIX + "/ledger").header("Authorization", member.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.entryType=='REFUND')].pointsDelta").value(contains(10)))
                .andExpect(jsonPath("$.entries[?(@.entryType=='REFUND')].balanceAfter").value(contains(100)));
    }

    @Test
    void reconciliationDetectsDriftAndAlertsWithoutChangingLedger(CapturedOutput output) {
        Account member = account("USER", 100);
        // 对账是整库检查，其它步骤会留下合成漂移账户，因此只能与用例开始时的基线比较。
        long baseline = wallet.countUnbalancedAccounts();
        int alerts = warnings();
        jdbc.update("UPDATE points_account SET balance_points=101 WHERE user_id=UUID_TO_BIN(?)",
                member.id().toString());
        try {
            assertThat(wallet.countUnbalancedAccounts()).isEqualTo(baseline + 1);
            reconciliation.check();
            assertThat(output.getOut()).contains("POINTS_RECONCILIATION_MISMATCH");
            // 全库有 baseline+1 个漂移账户时仍只写一条汇总告警，不是按账户逐条写。
            assertThat(warnings()).isEqualTo(alerts + 1);
            assertThat(points.balance(member.id()).balancePoints()).isEqualTo(101);
            assertThat(entries(member, "GRANT")).isEqualTo(1);
        } finally {
            jdbc.update("UPDATE points_account SET balance_points=100 WHERE user_id=UUID_TO_BIN(?)",
                    member.id().toString());
        }
        // 只证明本用例制造的漂移已消失；不要求清理其它步骤的合成账务。
        assertThat(wallet.countUnbalancedAccounts()).isEqualTo(baseline);
        assertThat(points.balance(member.id()).balancePoints()).isEqualTo(100);
        assertThat(ledgerTotal(member)).isEqualTo(100);
    }

    private Account account(String role, int balance) {
        UUID id = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_account (id, username, password_hash, role, status)
                VALUES (UUID_TO_BIN(?), ?, 'synthetic-test-hash', ?, 'ACTIVE')
                """, id.toString(), "s25-" + id, role);
        jdbc.update("""
                INSERT INTO user_session (id, user_id, refresh_token_hash, issued_at, expires_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UNHEX(SHA2(?, 256)),
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 1 HOUR)
                """, session.toString(), id.toString(), session.toString());
        jdbc.update("""
                INSERT INTO points_account (id, user_id, balance_points)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?)
                """, account.toString(), id.toString(), balance);
        jdbc.update("""
                INSERT INTO points_ledger (id, account_id, user_id, entry_type, points_delta,
                                           balance_before, balance_after, business_reference)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), 'GRANT', ?, 0, ?, ?)
                """, UUID.randomUUID().toString(), account.toString(), id.toString(), balance, balance, "grant:" + id);
        return new Account(id, "Bearer " + tokens.createAccessToken(id, role, session, Instant.now().getEpochSecond()));
    }

    private ResultActions send(Account actor, String path, String payload, String key) throws Exception {
        return http.perform(post(PREFIX + path).header("Authorization", actor.bearer())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(payload));
    }

    private ResultActions confirm(Account actor, String quote, String key) throws Exception {
        return send(actor, "/wallet/confirm", "{\"quoteId\":\"" + quote + "\"}", key);
    }

    private String topUpQuote(Account actor, String code, String key) throws Exception {
        return JsonPath.read(send(actor, "/top-up/quotes", "{\"packageCode\":\"" + code + "\"}", key)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.quoteId");
    }

    private String adjustmentQuote(Account admin, Account target, int delta, String key) throws Exception {
        return JsonPath.read(send(admin, "/admin/adjustments/quotes", adjustment(target, delta), key)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.quoteId");
    }

    private String adjustment(Account target, int delta) {
        return "{\"userId\":\"" + target.id() + "\",\"pointsDelta\":" + delta + "}";
    }

    private int entries(Account account, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM points_ledger WHERE user_id=UUID_TO_BIN(?) AND entry_type=?",
                Integer.class, account.id().toString(), type);
    }

    private long ledgerTotal(Account account) {
        Long total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(points_delta), 0) FROM points_ledger WHERE user_id=UUID_TO_BIN(?)", Long.class,
                account.id().toString());
        return total == null ? 0 : total;
    }

    private int warnings() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE error_code='POINTS_RECONCILIATION_MISMATCH'",
                Integer.class);
    }

    private <T> List<T> parallel(Callable<T> operation) throws Exception {
        var barrier = new CyclicBarrier(2);
        Callable<T> run = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return operation.call();
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(run);
            var second = executor.submit(run);
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }

    private record Account(UUID id, String bearer) {
    }
}
