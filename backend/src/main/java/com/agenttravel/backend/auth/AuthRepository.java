package com.agenttravel.backend.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class AuthRepository {

    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean loginExists(String login) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE username=? OR phone=? OR email=?",
                Integer.class, login, login, login) > 0;
    }

    public void insertUser(UUID id, String login, String passwordHash) {
        String column = login.contains("@") ? "email" : (login.matches("\\+?[0-9]{6,32}") ? "phone" : "username");
        jdbc.update("INSERT INTO user_account(id," + column + ",password_hash,role,status) VALUES(UUID_TO_BIN(?),?,?,'USER','ACTIVE')",
                id.toString(), login, passwordHash);
    }

    public UserRow findByLogin(String login) {
        return findByLogin(login, false);
    }

    public UserRow findByLoginForUpdate(String login) {
        return findByLogin(login, true);
    }

    private UserRow findByLogin(String login, boolean lock) {
        String sql = "SELECT BIN_TO_UUID(id), COALESCE(username,COALESCE(phone,email)), password_hash, role, status, failed_login_count, locked_until, created_at FROM user_account WHERE (username=? OR phone=? OR email=?) AND deleted_at IS NULL"
                + (lock ? " FOR UPDATE" : "");
        List<UserRow> rows = jdbc.query(sql,
                (rs, rowNum) -> new UserRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6), timestamp(rs.getTimestamp(7)), timestamp(rs.getTimestamp(8))), login, login, login);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public UserRow findById(UUID userId) {
        List<UserRow> rows = jdbc.query("SELECT BIN_TO_UUID(id), COALESCE(username,COALESCE(phone,email)), password_hash, role, status, failed_login_count, locked_until, created_at FROM user_account WHERE id=UUID_TO_BIN(?) AND deleted_at IS NULL",
                (rs, rowNum) -> new UserRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6), timestamp(rs.getTimestamp(7)), timestamp(rs.getTimestamp(8))), userId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void createPointsAccount(UUID userId, UUID accountId) {
        jdbc.update("INSERT INTO points_account(id,user_id,balance_points,state_version) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),0,0)",
                accountId.toString(), userId.toString());
    }

    public void updatePointsBalance(UUID accountId, long balance) {
        jdbc.update("UPDATE points_account SET balance_points=?,state_version=state_version+1,updated_at=? WHERE id=UUID_TO_BIN(?)",
                balance, Timestamp.from(Instant.now()), accountId.toString());
    }

    public void insertLedger(UUID userId, UUID accountId, long delta, long before, long after, String reference) {
        jdbc.update("INSERT INTO points_ledger(id,account_id,user_id,entry_type,points_delta,balance_before,balance_after,business_reference) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'GRANT',?,?,?,?)",
                UUID.randomUUID().toString(), accountId.toString(), userId.toString(), delta, before, after, reference);
    }

    public UUID accountId(UUID userId) {
        return jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM points_account WHERE user_id=UUID_TO_BIN(?)",
                (rs, rowNum) -> UUID.fromString(rs.getString(1)), userId.toString());
    }

    public SessionRow findSessionForUpdate(byte[] tokenHash) {
        List<SessionRow> rows = jdbc.query("SELECT BIN_TO_UUID(s.id), BIN_TO_UUID(s.user_id), s.refresh_token_hash, s.expires_at, s.revoked_at, u.role FROM user_session s JOIN user_account u ON u.id=s.user_id WHERE s.refresh_token_hash=? FOR UPDATE",
                (rs, rowNum) -> new SessionRow(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                        rs.getBytes(3), timestamp(rs.getTimestamp(4)), timestamp(rs.getTimestamp(5)), rs.getString(6)), tokenHash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertSession(UUID sessionId, UUID userId, byte[] hash, Instant issuedAt, Instant expiresAt) {
        jdbc.update("INSERT INTO user_session(id,user_id,refresh_token_hash,issued_at,expires_at,last_seen_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?)",
                sessionId.toString(), userId.toString(), hash, Timestamp.from(issuedAt), Timestamp.from(expiresAt), Timestamp.from(issuedAt));
    }

    public void revokeSession(UUID sessionId, Instant now) {
        jdbc.update("UPDATE user_session SET revoked_at=?,last_seen_at=? WHERE id=UUID_TO_BIN(?) AND revoked_at IS NULL",
                Timestamp.from(now), Timestamp.from(now), sessionId.toString());
    }

    public void revokeAllSessions(UUID userId, Instant now) {
        jdbc.update("UPDATE user_session SET revoked_at=?,last_seen_at=? WHERE user_id=UUID_TO_BIN(?) AND revoked_at IS NULL",
                Timestamp.from(now), Timestamp.from(now), userId.toString());
    }

    public boolean activeAccessSession(UUID sessionId, UUID userId, Instant now) {
        return findActiveAccessIdentity(sessionId, userId, now) != null;
    }

    public AccessIdentity findActiveAccessIdentity(UUID sessionId, UUID userId, Instant now) {
        if (sessionId == null) {
            return null;
        }
        List<AccessIdentity> rows = jdbc.query("SELECT u.status,u.role FROM user_session s JOIN user_account u ON u.id=s.user_id WHERE s.id=UUID_TO_BIN(?) AND s.user_id=UUID_TO_BIN(?) AND s.revoked_at IS NULL AND s.expires_at>? AND u.status='ACTIVE' AND u.deleted_at IS NULL",
                (rs, rowNum) -> new AccessIdentity(rs.getString(1), rs.getString(2)), sessionId.toString(),
                userId.toString(), Timestamp.from(now));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void markLoginSuccess(UUID userId, Instant now) {
        jdbc.update("UPDATE user_account SET failed_login_count=0,locked_until=NULL,last_login_at=?,updated_at=? WHERE id=UUID_TO_BIN(?) AND status='ACTIVE'",
                Timestamp.from(now), Timestamp.from(now), userId.toString());
    }

    public void clearExpiredLoginLock(UUID userId, Instant now) {
        jdbc.update("UPDATE user_account SET status='ACTIVE',failed_login_count=0,locked_until=NULL,updated_at=? WHERE id=UUID_TO_BIN(?) AND status='LOCKED' AND locked_until IS NOT NULL AND locked_until<=?",
                Timestamp.from(now), userId.toString(), Timestamp.from(now));
    }

    public void markLoginFailure(UUID userId, int count, Instant lockedUntil) {
        jdbc.update("UPDATE user_account SET failed_login_count=?,locked_until=?,updated_at=? WHERE id=UUID_TO_BIN(?)",
                count, lockedUntil == null ? null : Timestamp.from(lockedUntil), Timestamp.from(Instant.now()), userId.toString());
    }

    public void incrementLoginFailure(UUID userId, Instant now, int maxFailures, int lockMinutes) {
        jdbc.update("UPDATE user_account SET status=CASE WHEN failed_login_count+1>=? THEN 'LOCKED' ELSE status END,locked_until=CASE WHEN failed_login_count+1>=? THEN DATE_ADD(?,INTERVAL ? MINUTE) ELSE locked_until END,failed_login_count=failed_login_count+1,updated_at=? WHERE id=UUID_TO_BIN(?)",
                maxFailures, maxFailures, Timestamp.from(now), lockMinutes, Timestamp.from(now), userId.toString());
    }

    public LoginFailureRow loginFailureState(UUID userId) {
        return jdbc.queryForObject("SELECT failed_login_count,locked_until FROM user_account WHERE id=UUID_TO_BIN(?)",
                (rs, rowNum) -> new LoginFailureRow(rs.getInt(1), timestamp(rs.getTimestamp(2))), userId.toString());
    }

    private static Instant timestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record UserRow(UUID id, String login, String passwordHash, String role, String status, int failedLoginCount,
            Instant lockedUntil, Instant createdAt) {
    }

    public record SessionRow(UUID id, UUID userId, byte[] tokenHash, Instant expiresAt, Instant revokedAt, String role) {
    }

    public record LoginFailureRow(int failedLoginCount, Instant lockedUntil) {
    }

    public record AccessIdentity(String status, String role) {
    }
}
