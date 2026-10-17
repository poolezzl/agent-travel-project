package com.agenttravel.backend.common;

import java.sql.Timestamp;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class AuditRepository {

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(UUID actor, String action, String resourceType, UUID resourceId, String outcome, String code) {
        RequestContext.Context context = RequestContext.get();
        jdbc.update("INSERT INTO audit_event(id,actor_user_id,action,resource_type,resource_id,request_id,trace_id,outcome,error_code) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,UUID_TO_BIN(?),?,?,?,?)",
                UUID.randomUUID().toString(), actor == null ? null : actor.toString(), action, resourceType,
                resourceId == null ? null : resourceId.toString(), context == null ? null : context.requestId(),
                context == null ? null : context.traceId(), outcome, code);
    }
}
