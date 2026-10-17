package com.agenttravel.backend.points;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class PointsRepository {

    private final JdbcTemplate jdbc;

    public PointsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertQuote(UUID quoteId, UUID userId, String reference, int planCount, long points, Instant expiresAt, UUID taskId) {
        jdbc.update("INSERT INTO points_quote(id,user_id,task_id,quote_reference,plan_count,points,expires_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?, ?,?,?,?)",
                quoteId.toString(), userId.toString(), taskId == null ? null : uuidBytes(taskId), reference, planCount, points, Timestamp.from(expiresAt));
    }

    public QuoteRow findQuoteByReference(String reference, UUID userId) {
        List<QuoteRow> rows = jdbc.query("SELECT BIN_TO_UUID(id),BIN_TO_UUID(user_id),quote_reference,plan_count,points,status,expires_at,task_id FROM points_quote WHERE quote_reference=? AND user_id=UUID_TO_BIN(?)",
                (rs, rowNum) -> new QuoteRow(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3),
                        rs.getInt(4), rs.getLong(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                        rs.getBytes(8) == null ? null : bytesToUuid(rs.getBytes(8))), reference, userId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public QuoteRow findQuoteForUser(String reference, UUID userId) {
        List<QuoteRow> rows = jdbc.query("SELECT BIN_TO_UUID(id),BIN_TO_UUID(user_id),quote_reference,plan_count,points,status,expires_at,task_id FROM points_quote WHERE quote_reference=? AND user_id=UUID_TO_BIN(?) FOR UPDATE",
                (rs, rowNum) -> new QuoteRow(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3),
                        rs.getInt(4), rs.getLong(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                        rs.getBytes(8) == null ? null : bytesToUuid(rs.getBytes(8))), reference, userId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public LedgerRow findLedger(String reference) {
        List<LedgerRow> rows = jdbc.query("SELECT business_reference,points_delta,balance_after,BIN_TO_UUID(user_id) FROM points_ledger WHERE business_reference=?",
                (rs, rowNum) -> new LedgerRow(rs.getString(1), rs.getLong(2), rs.getLong(3), UUID.fromString(rs.getString(4))), reference);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public LedgerRow findLedgerByPrefix(String prefix) {
        List<LedgerRow> rows = jdbc.query("SELECT business_reference,points_delta,balance_after,BIN_TO_UUID(user_id) FROM points_ledger WHERE business_reference LIKE ? ORDER BY created_at LIMIT 1",
                (rs, rowNum) -> new LedgerRow(rs.getString(1), rs.getLong(2), rs.getLong(3), UUID.fromString(rs.getString(4))), prefix + "%");
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AccountRow lockAccount(UUID userId) {
        return jdbc.queryForObject("SELECT BIN_TO_UUID(id),balance_points FROM points_account WHERE user_id=UUID_TO_BIN(?) FOR UPDATE",
                (rs, rowNum) -> new AccountRow(UUID.fromString(rs.getString(1)), rs.getLong(2)), userId.toString());
    }

    public void insertLedger(UUID accountId, UUID userId, UUID taskId, UUID quoteId, String type, long delta,
            long before, long after, String reference) {
        jdbc.update("INSERT INTO points_ledger(id,account_id,user_id,task_id,quote_id,entry_type,points_delta,balance_before,balance_after,business_reference) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?, ?,?,?,?)",
                UUID.randomUUID().toString(), accountId.toString(), userId.toString(), taskId == null ? null : uuidBytes(taskId),
                quoteId == null ? null : uuidBytes(quoteId), type, delta, before, after, reference);
    }

    public void updateBalance(UUID accountId, long balance) {
        jdbc.update("UPDATE points_account SET balance_points=?,state_version=state_version+1,updated_at=? WHERE id=UUID_TO_BIN(?)",
                balance, Timestamp.from(Instant.now()), accountId.toString());
    }

    public boolean ownsTask(UUID taskId, UUID userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM planning_task WHERE id=UUID_TO_BIN(?) AND owner_id=UUID_TO_BIN(?)",
                Integer.class, taskId.toString(), userId.toString()) > 0;
    }

    public int reserveTask(UUID taskId, UUID quoteId, long points, UUID userId) {
        return jdbc.update("UPDATE planning_task SET quote_id=UUID_TO_BIN(?),reserved_points=?,status='BALANCE_RESERVED',state_version=state_version+1,updated_at=? WHERE id=UUID_TO_BIN(?) AND owner_id=UUID_TO_BIN(?) AND status='CREATED'",
                quoteId.toString(), points, Timestamp.from(Instant.now()), taskId.toString(), userId.toString());
    }

    public void setQuoteStatus(UUID quoteId, String status) {
        jdbc.update("UPDATE points_quote SET status=?,accepted_at=CASE WHEN ?='ACCEPTED' THEN ? ELSE accepted_at END WHERE id=UUID_TO_BIN(?)",
                status, status, Timestamp.from(Instant.now()), quoteId.toString());
    }

    public long balance(UUID userId) {
        return jdbc.queryForObject("SELECT balance_points FROM points_account WHERE user_id=UUID_TO_BIN(?)",
                Long.class, userId.toString());
    }

    public UUID accountId(UUID userId) {
        return jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM points_account WHERE user_id=UUID_TO_BIN(?)",
                (rs, rowNum) -> UUID.fromString(rs.getString(1)), userId.toString());
    }

    static byte[] uuidBytes(UUID value) {
        long most = value.getMostSignificantBits();
        long least = value.getLeastSignificantBits();
        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (most >>> (56 - i * 8));
            bytes[i + 8] = (byte) (least >>> (56 - i * 8));
        }
        return bytes;
    }

    static UUID bytesToUuid(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalArgumentException("二进制任务编号长度不正确");
        }
        long most = 0;
        long least = 0;
        for (int i = 0; i < 8; i++) {
            most = (most << 8) | (bytes[i] & 0xffL);
            least = (least << 8) | (bytes[i + 8] & 0xffL);
        }
        return new UUID(most, least);
    }

    public record QuoteRow(UUID id, UUID userId, String reference, int planCount, long points, String status,
            Instant expiresAt, UUID taskId) {
    }

    public record LedgerRow(String reference, long delta, long balanceAfter, UUID userId) {
    }

    public record AccountRow(UUID id, long balance) {
    }
}
