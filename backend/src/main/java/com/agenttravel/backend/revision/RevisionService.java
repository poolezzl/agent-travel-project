package com.agenttravel.backend.revision;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import com.agenttravel.backend.points.PointsService;
import com.agenttravel.backend.task.CanonicalJson;
import com.agenttravel.backend.task.TaskPlanRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final RevisionAgentClient agent;
    private final PointsService points;
    private final ObjectMapper mapper;
    private final CanonicalJson canonical = new CanonicalJson();

    public RevisionService(RevisionRepository repository, AuditRepository audit, RevisionAgentClient agent,
            PointsService points, ObjectMapper mapper) {
        this.repository = repository;
        this.audit = audit;
        this.agent = agent;
        this.points = points;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public RevisionModels.RevisionResult preview(UUID userId, UUID tripId, RevisionModels.RevisionCommand request) {
        var base = requireBase(userId, tripId, request.baseVersionId());
        JsonNode plan = readPlan(base);
        var proposal = agent.propose(base.id(), plan, revisionText(request), locks(plan, request));
        if ("change".equals(proposal.intent())) {
            requireCurrent(userId, tripId, base, request.etag());
            validateScope(proposal, plan);
        }
        return previewResult(base, proposal);
    }

    @Transactional
    public RevisionModels.RevisionResult confirm(UUID userId, UUID tripId, RevisionModels.RevisionCommand request) {
        String message = revisionText(request);
        String confirmationId = request.confirmationId();
        if (confirmationId == null || confirmationId.isBlank() || confirmationId.length() > 128) {
            throw invalid("确认键必须为 1 至 128 个字符");
        }
        if (!Boolean.TRUE.equals(request.confirmed())) {
            return preview(userId, tripId, request);
        }
        UUID current = repository.currentVersionId(tripId, userId, true);
        var base = requireBase(userId, tripId, request.baseVersionId());
        checkEtag(base, request.etag());
        JsonNode plan = readPlan(base);
        List<String> locks = locks(plan, request);
        byte[] fingerprint = canonical.hash(List.of(tripId, base.id(), confirmationId, message, locks));
        var reused = repository.findVersionByChange(tripId, base.id(), userId, fingerprint);
        if (reused != null) {
            return published(base, reused, readProposal(reused.id()), repository.versionSources(reused.id()),
                    true, "重复确认已复用原版本");
        }
        if (!base.id().equals(current)) {
            throw conflict();
        }
        var proposal = agent.propose(base.id(), plan, message, locks);
        if (!"change".equals(proposal.intent())) {
            return previewResult(base, proposal);
        }
        validateScope(proposal, plan);
        var settled = points.settleRevision(userId, tripId, HexFormat.of().formatHex(fingerprint), () -> {
            JsonNode updated = agent.apply(plan, proposal);
            validateResult(plan, updated, proposal.scope().days());
            return writeVersion(userId, tripId, base, base, updated, proposal, fingerprint, false);
        });
        if (settled.isPresent()) {
            return settled.get();
        }
        // A concurrent duplicate confirmation already paid for this fingerprint; reuse the
        // version it produced instead of charging twice or leaking a duplicate-key error.
        var raced = repository.findVersionByChange(tripId, base.id(), userId, fingerprint);
        if (raced != null) {
            return published(base, raced, readProposal(raced.id()), repository.versionSources(raced.id()), true,
                    "并发重复确认已复用原版本");
        }
        throw new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", "同一确认正在处理，请稍后重试");
    }

    @Transactional
    public RevisionModels.RevisionResult rollback(UUID userId, UUID tripId, UUID targetVersionId, String etag) {
        UUID currentId = repository.currentVersionId(tripId, userId, true);
        var current = requireBase(userId, tripId, currentId);
        checkEtag(current, etag);
        var target = requireBase(userId, tripId, targetVersionId);
        return writeVersion(userId, tripId, current, target, readPlan(target), null,
                canonical.hash(List.of(current.id(), target.id(), "rollback")), true);
    }

    private RevisionModels.RevisionResult writeVersion(UUID userId, UUID tripId, RevisionRepository.VersionRow base,
            RevisionRepository.VersionRow source, JsonNode plan, RevisionAgentClient.Proposal proposal,
            byte[] fingerprint, boolean rollback) {
        String planJson = canonical.write(mapper.convertValue(plan, Map.class));
        byte[] contentHash = TaskPlanRepository.sha256(planJson);
        int versionNo = repository.nextVersionNo(tripId);
        String hashHex = HexFormat.of().withUpperCase().formatHex(contentHash);
        byte[] etag = TaskPlanRepository.sha256(tripId + ":" + versionNo + ":" + hashHex);
        String etagHex = HexFormat.of().withUpperCase().formatHex(etag);
        var changes = rollback
                ? List.of(new RevisionModels.AtomicChange("replace", "$", Map.of("target_version_id", source.id())))
                : proposal.changes();
        String schema = source.schemaVersion();
        String model = rollback ? source.modelVersion() : "deterministic";
        String prompt = rollback ? source.promptVersion() : "s20-revision-v1";
        Instant now = Instant.now();
        UUID id = repository.insertVersion(tripId, base.id(), versionNo, etag, contentHash, schema, model, prompt,
                userId, planJson, repository.json(changes),
                repository.json(changes.stream().map(RevisionModels.AtomicChange::path).toList()), fingerprint,
                rollback ? "ROLLBACK" : "UPDATE", base.id(), List.of(), now);
        if (proposal != null) {
            repository.saveProposalScope(id, repository.json(proposal.scope()));
        }
        List<UUID> sources = repository.copyVersionSources(source.id(), id);
        if (repository.advanceCurrentVersion(tripId, base.id(), id, base.etag()) != 1) {
            throw conflict();
        }
        audit.write(userId, rollback ? "TRIP_VERSION_ROLLBACK" : "TRIP_VERSION_CREATE",
                "TRIP_VERSION", id, "SUCCESS", null);
        var version = new RevisionRepository.VersionRow(id, tripId, base.id(), versionNo, etagHex,
                hashHex, "PUBLISHED", schema, model, prompt, now, planJson);
        return published(base, version, rollback
                ? new RevisionAgentClient.Proposal(null, "change", base.id().toString(), null, null, null,
                        new RevisionAgentClient.Scope(List.of(), List.of()), changes)
                : proposal, sources, false, rollback ? "已创建回滚版本" : "已创建新版本");
    }

    private void validateScope(RevisionAgentClient.Proposal proposal, JsonNode plan) {
        var scope = proposal.scope();
        if (scope == null || scope.days() == null || scope.days().isEmpty() || scope.agents() == null
                || scope.agents().isEmpty() || !AGENTS.containsAll(scope.agents())
                || proposal.changes() == null || proposal.changes().isEmpty()) {
            throw invalid("修改缺少有效的影响范围");
        }
        Set<Integer> changedDays = new HashSet<>();
        for (var change : proposal.changes()) {
            if (change == null || change.path() == null) {
                throw invalid("修改路径缺失");
            }
            String[] path = change.path().split("/", -1);
            if (path.length < 3 || !path[0].isEmpty() || !"days".equals(path[1])
                    || !path[2].matches("[1-9][0-9]{0,3}") || change.operation() == null
                    || !Set.of("add", "remove", "replace", "reorder").contains(change.operation())) {
                throw invalid("修改路径或操作不受支持");
            }
            int day = Integer.parseInt(path[2]);
            if (day > plan.path("days").size()) {
                throw invalid("修改日期超出行程");
            }
            changedDays.add(day);
        }
        if (scope.days().size() != changedDays.size() || !changedDays.containsAll(scope.days())) {
            throw invalid("变更路径超出影响范围");
        }
    }

    private void validateResult(JsonNode before, JsonNode after, List<Integer> affected) {
        JsonNode oldDays = before.path("days");
        JsonNode newDays = after.path("days");
        if (!after.isObject() || !before.path("trip").equals(after.path("trip"))
                || !oldDays.isArray() || !newDays.isArray() || oldDays.size() != newDays.size()
                || oldDays.equals(newDays) || after.path("confirmation").path("required").asBoolean()) {
            throw invalid("局部重规划没有可发布的变更");
        }
        for (int i = 0; i < oldDays.size(); i++) {
            if (!oldDays.get(i).path("date").equals(newDays.get(i).path("date"))
                    || (!affected.contains(i + 1) && !oldDays.get(i).equals(newDays.get(i)))) {
                throw invalid("局部重规划修改了白名单外的行程");
            }
        }
    }

    private List<String> locks(JsonNode plan, RevisionModels.RevisionCommand request) {
        List<String> result = new ArrayList<>();
        plan.path("locked_paths").forEach(path -> result.add(path.asText()));
        if (request.lockedPaths() != null) {
            result.addAll(request.lockedPaths());
        }
        if (result.stream().anyMatch(path -> path == null || !path.startsWith("/") || path.length() > 256)) {
            throw invalid("锁定路径无效");
        }
        return result.stream().distinct().sorted().toList();
    }

    private String revisionText(RevisionModels.RevisionCommand request) {
        String value = request.revisionRequest();
        if (value == null || value.isBlank() || value.length() > 2000) {
            throw invalid("请输入 1 至 2000 字的行程问题或修改要求");
        }
        return value.strip();
    }

    private JsonNode readPlan(RevisionRepository.VersionRow row) {
        try {
            JsonNode plan = mapper.readTree(row.planJson());
            if (plan != null && plan.path("days").isArray()) {
                return plan;
            }
        } catch (JsonProcessingException ignored) {
            // A broken stored plan cannot be revised.
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PLAN_CORRUPTED", "行程版本内容损坏");
    }

    private RevisionAgentClient.Proposal readProposal(UUID id) {
        try {
            var proposal = mapper.readValue(repository.loadPublishedProposal(id), RevisionAgentClient.Proposal.class);
            return proposal.scope() == null ? null : proposal;
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PLAN_CORRUPTED", "历史修改记录损坏");
        }
    }

    private RevisionRepository.VersionRow requireBase(UUID userId, UUID tripId, UUID id) {
        if (id == null) {
            throw invalid("必须指定可用的行程版本");
        }
        var row = repository.findVersion(tripId, id, userId, false);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONTEXT_NOT_FOUND", "行程版本不存在");
        }
        return row;
    }

    private void requireCurrent(UUID userId, UUID tripId, RevisionRepository.VersionRow base, String etag) {
        checkEtag(base, etag);
        if (!base.id().equals(repository.currentVersionId(tripId, userId, false))) {
            throw conflict();
        }
    }

    private void checkEtag(RevisionRepository.VersionRow base, String etag) {
        String expected = etag == null ? "" : etag.strip().replaceFirst("^\"([^\"]+)\"$", "$1");
        if (!base.etag().equalsIgnoreCase(expected.toUpperCase(Locale.ROOT))) {
            throw conflict();
        }
    }

    private RevisionModels.RevisionResult previewResult(RevisionRepository.VersionRow base,
            RevisionAgentClient.Proposal proposal) {
        boolean change = "change".equals(proposal.intent());
        return new RevisionModels.RevisionResult(proposal.intent(), base.id(), null, base.parentId(), base.etag(),
                change ? "AWAITING_CONFIRMATION" : "PRECHECKED", change,
                change ? proposal.scope().days() : List.of(), change ? proposal.scope().agents() : List.of(),
                change ? proposal.changes() : List.of(), proposal.answer(), proposal.question(), proposal.summary(),
                base.createdAt(), false, List.of(), List.of(), base.schemaVersion(), base.modelVersion(),
                base.promptVersion(), base.contentHash());
    }

    private RevisionModels.RevisionResult published(RevisionRepository.VersionRow base,
            RevisionRepository.VersionRow version, RevisionAgentClient.Proposal proposal, List<UUID> sources,
            boolean reused, String summary) {
        List<RevisionModels.AtomicChange> changes = proposal == null ? List.of() : proposal.changes();
        return new RevisionModels.RevisionResult("change", base.id(), version.id(), version.parentId(), version.etag(),
                "CREATED", false, proposal == null ? List.of() : proposal.scope().days(),
                proposal == null ? List.of() : proposal.scope().agents(), changes, null, null, summary,
                version.createdAt(), reused, changes, sources, version.schemaVersion(), version.modelVersion(),
                version.promptVersion(), version.contentHash());
    }

    private ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "REVISION_BASE_MISMATCH", "行程版本已变化，请刷新后重试");
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }
}
