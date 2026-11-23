package com.agenttravel.backend.points;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class WalletRepository {

    private static final String QUOTE_COLUMNS = """
            SELECT BIN_TO_UUID(id), BIN_TO_UUID(user_id), package_code, points_delta,
                   account_version, balance_after, expires_at, applied_reference FROM wallet_quote
            """;
    private static final RowMapper<QuoteRow> QUOTE = (rs, index) -> new QuoteRow(
            UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(4),
            rs.getObject(5, Long.class), rs.getLong(6), rs.getTimestamp(7).toInstant(), rs.getString(8));

    private final JdbcTemplate jdbc;

    public WalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    QuoteRow findQuote(UUID actor, String keyHash) {
        return jdbc.query(QUOTE_COLUMNS + "WHERE actor_id=UUID_TO_BIN(?) AND quote_key=?",
                QUOTE, actor.toString(), keyHash).stream().findFirst().orElse(null);
    }

    QuoteRow lockQuote(UUID actor, UUID quoteId) {
        return jdbc.query(QUOTE_COLUMNS + "WHERE id=UUID_TO_BIN(?) AND actor_id=UUID_TO_BIN(?) FOR UPDATE",
                QUOTE, quoteId.toString(), actor.toString()).stream().findFirst().orElse(null);
    }

    void insertQuote(UUID actor, String keyHash, QuoteRow quote) {
        jdbc.update("""
                INSERT INTO wallet_quote (id, actor_id, user_id, quote_key, package_code, points_delta,
                                          account_version, balance_after, expires_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?)
                """, quote.id().toString(), actor.toString(), quote.userId().toString(), keyHash, quote.packageCode(),
                quote.delta(), quote.version(), quote.balanceAfter(), Timestamp.from(quote.expiresAt()));
    }

    void markApplied(UUID quoteId, String reference) {
        jdbc.update("UPDATE wallet_quote SET applied_reference=? WHERE id=UUID_TO_BIN(?)",
                reference, quoteId.toString());
    }

    AccountSnapshot accountSnapshot(UUID user) {
        return jdbc.query("""
                SELECT p.balance_points, p.state_version FROM points_account p
                JOIN user_account u ON u.id=p.user_id
                WHERE p.user_id=UUID_TO_BIN(?) AND u.status='ACTIVE' AND u.deleted_at IS NULL
                """, (rs, index) -> new AccountSnapshot(rs.getLong(1), rs.getLong(2)), user.toString())
                .stream().findFirst().orElse(null);
    }

    boolean updateActiveBalance(UUID accountId, long balance, Long expectedVersion) {
        return jdbc.update("""
                UPDATE points_account p JOIN user_account u ON u.id=p.user_id
                SET p.balance_points=?, p.state_version=p.state_version+1, p.updated_at=UTC_TIMESTAMP(6)
                WHERE p.id=UUID_TO_BIN(?) AND u.status='ACTIVE' AND u.deleted_at IS NULL AND (? IS NULL OR p.state_version=?)
                """, balance, accountId.toString(), expectedVersion, expectedVersion) == 1;
    }

    List<WalletModels.LedgerEntry> ledger(UUID user, int offset, int limit) {
        String query = """
                SELECT BIN_TO_UUID(id), BIN_TO_UUID(user_id), BIN_TO_UUID(task_id), entry_type,
                       points_delta, balance_after, created_at
                FROM points_ledger
                """;
        Object[] args = {limit, offset};
        if (user != null) {
            query += "WHERE user_id=UUID_TO_BIN(?) ";
            args = new Object[] {user.toString(), limit, offset};
        }
        return jdbc.query(query + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", (rs, index) -> {
            String task = rs.getString(3);
            return new WalletModels.LedgerEntry(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                    task == null ? null : UUID.fromString(task), rs.getString(4), rs.getLong(5), rs.getLong(6),
                    rs.getTimestamp(7).toInstant());
        }, args);
    }

    long countUnbalancedAccounts() {
        // 同一条查询读取余额与流水，避免把同时入账误报为异常。
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM points_account p
                LEFT JOIN (
                    SELECT account_id, SUM(points_delta) AS total, MIN(user_id) AS first_user, MAX(user_id) AS last_user
                    FROM points_ledger GROUP BY account_id
                ) ledger ON ledger.account_id=p.id
                WHERE p.balance_points<>COALESCE(ledger.total, 0)
                      OR ledger.first_user<>p.user_id OR ledger.last_user<>p.user_id
                """, Long.class);
        return count == null ? 0 : count;
    }

    record QuoteRow(UUID id, UUID userId, String packageCode, long delta, Long version, long balanceAfter,
            Instant expiresAt, String appliedReference) {
        WalletModels.QuoteResponse response() {
            return new WalletModels.QuoteResponse(id, userId, delta, balanceAfter, expiresAt);
        }
    }

    record AccountSnapshot(long balance, long version) {
    }
}
