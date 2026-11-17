package com.agenttravel.backend.revision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class RevisionService {

    private static final Set<String> AGENTS = Set.of("orchestrator", "route-planner", "weather", "attractions",
            "dining-lodging", "reservation-rules", "revision");
    private final RevisionRepository repository;
    private final AuditRepository audit;

    public RevisionService(RevisionRepository repository, AuditRepository audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public RevisionModels.RevisionResult preview(UUID userId, UUID tripId, RevisionModels.RevisionCommand command) {
        String intent = classify(command);
        RevisionRepository.VersionRow base = requireBase(userId, tripId, command.baseVersionId(), false);
        if ("query".equals(intent)) {
            return result(intent, base, command, false, command.answer() == null ? "已读取当前行程版本" : command.answer(), null,
                    "只读问答，不创建版本");
        }
        if ("ambiguous".equals(intent)) {
            return result(intent, base, command, true, null,
                    command.clarificationQuestion() == null ? "请说明要查询还是修改哪一天的哪一项" : command.clarificationQuestion(),
                    "需要澄清，不创建版本");
        }
        validateChange(command, base);
        boolean protectedChange = touchesLocked(command);
        boolean needsConfirm = Boolean.TRUE.equals(command.needsConfirm()) || protectedChange;
        return result(intent, base, command, needsConfirm && !Boolean.TRUE.equals(command.confirmed()), null, null,
                needsConfirm ? "请确认变更后再写入版本" : "变更已通过预检");
    }

    @Transactional
    public RevisionModels.RevisionResult confirm(UUID userId, UUID tripId, RevisionModels.RevisionCommand command) {
        String intent = classify(command);
        if (!"change".equals(intent)) {
            return preview(userId, tripId, command);
        }
        RevisionRepository.VersionRow base = requireBase(userId, tripId, command.baseVersionId(), true);
        validateChange(command, base);
        if ((Boolean.TRUE.equals(command.needsConfirm()) || touchesLocked(command))
                && !Boolean.TRUE.equals(command.confirmed())) {
            return result(intent, base, command, true, null, "请确认变更后再写入版本", "确认被拒绝，原版本不变");
        }
        RevisionRepository.VersionRow reused = repository.findVersionByChange(tripId, base.id(), userId,
                changeHash(command));
        if (reused != null) {
            return resultWithVersion("change", reused, command, false, null, null, "重复确认已复用原版本");
        }
        return writeVersion(userId, tripId, base, command, "UPDATE");
    }

    @Transactional
    public RevisionModels.RevisionResult rollback(UUID userId, UUID tripId, UUID targetVersionId, String etag) {
        RevisionRepository.VersionRow target = requireBase(userId, tripId, targetVersionId, false);
        RevisionRepository.VersionRow current = requireBase(userId, tripId, repository.currentVersionId(tripId, userId, true), true);
        RevisionModels.RevisionCommand command = new RevisionModels.RevisionCommand(current.id(), etag, "change", List.of(),
                List.of(), List.of("orchestrator"), List.of(new RevisionModels.AtomicChange("replace", "$", null)),
                false, true, null, "回滚到历史版本", null, null, null, "rollback-schema-v1", "system", "rollback-v1", List.of());
        return writeVersion(userId, tripId, current, command, "ROLLBACK", target.planJson());
    }

    private RevisionModels.RevisionResult writeVersion(UUID userId, UUID tripId, RevisionRepository.VersionRow base,
            RevisionModels.RevisionCommand command, String type) {
        return writeVersion(userId, tripId, base, command, type, base.planJson());
    }

    private RevisionModels.RevisionResult writeVersion(UUID userId, UUID tripId, RevisionRepository.VersionRow base,
            RevisionModels.RevisionCommand command, String type, String rollbackPlan) {
        String planJson = command.plan() == null ? rollbackPlan : repository.json(command.plan());
        byte[] contentHash = sha256(planJson);
        byte[] etag = sha256(base.etag() + ":" + HexFormat.of().formatHex(contentHash));
        Instant now = Instant.now();
        UUID versionId = repository.insertVersion(tripId, base.id(), repository.nextVersionNo(tripId), etag, contentHash,
                value(command.schemaVersion(), "revision-schema-v1"), value(command.modelVersion(), "system"),
                value(command.promptVersion(), "revision-v1"), userId, planJson, repository.json(command.changes()),
                repository.json(command.affectedDays()), changeHash(command), type, base.id(),
                command.sourceSnapshotIds(), now);
        String expected = command.etag() == null ? base.etag() : normalize(command.etag());
        if (!expected.equals(base.etag()) || repository.advanceCurrentVersion(tripId, base.id(), versionId, expected) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "REVISION_BASE_MISMATCH", "行程版本已变化，请刷新后重试");
        }
        audit.write(userId, "TRIP_VERSION_CREATE", "TRIP_VERSION", versionId, "SUCCESS", null);
        return resultWithVersion("change", new RevisionRepository.VersionRow(versionId, tripId, base.id(), 0,
                HexFormat.of().formatHex(etag), HexFormat.of().formatHex(contentHash), "PUBLISHED", null, null, null,
                now, planJson), command, false, null, null, type.equals("ROLLBACK") ? "已创建回滚版本" : "已创建新版本");
    }

    private RevisionRepository.VersionRow requireBase(UUID userId, UUID tripId, UUID versionId, boolean lock) {
        if (versionId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "必须指定基准版本");
        }
        RevisionRepository.VersionRow row = repository.findVersion(tripId, versionId, userId, lock);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONTEXT_NOT_FOUND", "行程版本不存在");
        }
        return row;
    }

    private void validateChange(RevisionModels.RevisionCommand command, RevisionRepository.VersionRow base) {
        if (command.baseVersionId() == null || command.needsConfirm() == null || command.changes() == null
                || command.changes().isEmpty()
                || command.affectedDays() == null || command.affectedDays().isEmpty()
                || command.affectedAgents() == null || command.affectedAgents().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "修改必须包含基准版本、影响范围和原子变更");
        }
        if (command.etag() != null && !normalize(command.etag()).equals(base.etag())) {
            throw new ApiException(HttpStatus.CONFLICT, "REVISION_BASE_MISMATCH", "行程版本已变化，请刷新后重试");
        }
        if (command.affectedAgents().stream().anyMatch(agent -> !AGENTS.contains(agent.toLowerCase(Locale.ROOT)))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "受影响 Agent 不在白名单");
        }
        if (command.changes().stream().anyMatch(change -> !Set.of("add", "remove", "replace", "reorder")
                .contains(change.operation().toLowerCase(Locale.ROOT)))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "原子变更操作不受支持");
        }
        command.changes().forEach(change -> {
            if (!change.path().startsWith("/days/")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "变更路径必须落在日期白名单");
            }
            String[] parts = change.path().split("/");
            if (parts.length < 3) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "变更路径不合法");
            }
            try {
                int day = Integer.parseInt(parts[2]);
                if (!command.affectedDays().contains(day)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "变更超出 affected_days 白名单");
                }
            } catch (NumberFormatException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "变更日期不合法");
            }
        });
    }

    private boolean touchesLocked(RevisionModels.RevisionCommand command) {
        List<String> locks = command.lockedPaths() == null ? List.of() : command.lockedPaths();
        return command.changes().stream().anyMatch(change -> locks.stream().anyMatch(lock -> change.path().equals(lock)
                || change.path().startsWith(lock + "/")));
    }

    private String classify(RevisionModels.RevisionCommand command) {
        if (command.intent() != null) {
            if (!Set.of("query", "change", "ambiguous").contains(command.intent())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "意图只能是 query、change 或 ambiguous");
            }
            return command.intent();
        }
        String text = command.revisionRequest() == null ? "" : command.revisionRequest();
        if (text.isBlank()) {
            return "ambiguous";
        }
        if (text.contains("多少") || text.contains("什么") || text.contains("哪里") || text.contains("吗")) {
            return "query";
        }
        return "change";
    }

    private RevisionModels.RevisionResult result(String intent, RevisionRepository.VersionRow base,
            RevisionModels.RevisionCommand command, boolean needsConfirm, String answer, String question, String summary) {
        return buildResult(intent, base, base.id(), null, command, needsConfirm, answer, question, summary);
    }

    private RevisionModels.RevisionResult resultWithVersion(String intent, RevisionRepository.VersionRow base,
            RevisionModels.RevisionCommand command, boolean needsConfirm, String answer, String question, String summary) {
        UUID parent = base.parentId() == null ? base.id() : base.parentId();
        return buildResult(intent, base, parent, base.id(), command, needsConfirm, answer, question, summary);
    }

    private RevisionModels.RevisionResult buildResult(String intent, RevisionRepository.VersionRow base,
            UUID parentVersionId, UUID versionId, RevisionModels.RevisionCommand command, boolean needsConfirm,
            String answer, String question, String summary) {
        return new RevisionModels.RevisionResult(intent, command.baseVersionId(), versionId, parentVersionId, base.etag(),
                versionId == null ? (needsConfirm ? "AWAITING_CONFIRMATION" : "PRECHECKED") : "CREATED", needsConfirm,
                command.affectedDays() == null ? List.of() : command.affectedDays(),
                command.affectedAgents() == null ? List.of() : command.affectedAgents(),
                command.changes() == null ? List.of() : command.changes(), answer, question, summary, Instant.now(), false,
                command.changes() == null ? List.of() : command.changes(),
                command.sourceSnapshotIds() == null ? List.of() : command.sourceSnapshotIds(), command.schemaVersion(),
                command.modelVersion(), command.promptVersion(), base.contentHash());
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] changeHash(RevisionModels.RevisionCommand command) {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("changes", command.changes());
        fingerprint.put("plan", command.plan());
        fingerprint.put("confirmation_id", command.confirmationId());
        return sha256(repository.json(fingerprint));
    }
}
