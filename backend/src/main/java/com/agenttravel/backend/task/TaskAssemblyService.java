package com.agenttravel.backend.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.agenttravel.backend.common.AuditRepository;
import com.agenttravel.backend.points.PointsModels;
import com.agenttravel.backend.points.PointsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S19 assemble finalisation: create the immutable trip version and settle points, or persist a
 * blocking confirmation without creating any version.
 */
@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@ConditionalOnBooleanProperty(name = "app.s11.enabled", matchIfMissing = true)
public class TaskAssemblyService {

    private static final String SUPPORTED_PLAN_VERSION = "s19-plan-v1";

    private final TaskRepository repository;
    private final TaskPlanRepository planRepository;
    private final PointsService pointsService;
    private final AuditRepository audit;
    private final TaskProperties properties;
    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskAssemblyService(TaskRepository repository, TaskPlanRepository planRepository,
            PointsService pointsService, AuditRepository audit, TaskProperties properties) {
        this.repository = repository;
        this.planRepository = planRepository;
        this.pointsService = pointsService;
        this.audit = audit;
        this.properties = properties;
    }

    @Transactional
    public boolean finalizeAssembly(TaskWorkerClaimService.Claim claim, TaskModels.WorkerResult result) {
        TaskRepository.TaskRow task = repository.findTaskForUpdate(claim.taskId());
        if (task == null || task.status().terminal() || task.status() != TaskStatus.ASSEMBLING) {
            return false;
        }
        Map<String, Object> envelope = result.result();
        String status = String.valueOf(envelope.get("status"));
        if ("failed".equals(status)) {
            return false;
        }
        Map<String, Object> plan = map(envelope.get("plan"));
        if (plan == null || !SUPPORTED_PLAN_VERSION.equals(String.valueOf(plan.get("plan_version")))) {
            return false;
        }
        if (!(plan.get("days") instanceof List<?> days) || days.isEmpty() || map(plan.get("trip")) == null) {
            return false;
        }

        Instant now = Instant.now();
        if (confirmationRequired(plan)) {
            repository.setConfirmationDeadline(task.id(), now.plusSeconds(
                    (long) properties.getConfirmationTimeoutHours() * 3600));
            if (repository.transitionStatus(task.id(), TaskStatus.ASSEMBLING,
                    TaskStatus.AWAITING_CONFIRMATION, task.stateVersion(), now) != 1) {
                throw new IllegalStateException("CONFIRMATION_TRANSITION_FAILED");
            }
            String codes = confirmationCodes(plan);
            repository.appendEvent(task.id(), "TASK_STATUS_CHANGED",
                    new TaskModels.EventData(TaskStatus.AWAITING_CONFIRMATION.name(), claim.stepName(),
                            claim.attemptNo(), "存在待确认冲突：" + codes, List.of("confirmation")));
            audit.write(null, "TASK_AWAITING_CONFIRMATION", "PLANNING_TASK", task.id(), "SUCCESS",
                    codes.isBlank() ? "CONFIRMATION_REQUIRED" : codes);
            return true;
        }
        List<?> variants = envelope.get("variants") instanceof List<?> items ? items : List.of();
        if (!validVariants(expectedCount(task.id()), plan, variants)) {
            return false;
        }

        UUID tripId = task.tripId();
        if (tripId == null) {
            tripId = createTrip(task, plan, now);
            if (repository.bindTrip(task.id(), tripId) != 1) {
                throw new IllegalStateException("TRIP_BIND_FAILED");
            }
        }
        String planJson = canonicalJson.write(plan);
        byte[] contentHash = canonicalJson.hash(plan);
        TaskPlanRepository.VersionRow existing = variants.isEmpty()
                ? planRepository.findVersionByHash(tripId, contentHash) : null;
        TaskPlanRepository.VersionRow version = existing;
        if (version == null) {
            int versionNo = planRepository.nextVersionNo(tripId);
            UUID versionId = UUID.randomUUID();
            byte[] etag = sha256(tripId + ":" + versionNo + ":" + HexFormat.of().formatHex(contentHash));
            version = new TaskPlanRepository.VersionRow(versionId, versionNo, HexFormat.of().formatHex(etag),
                    HexFormat.of().formatHex(contentHash), String.valueOf(plan.get("plan_version")));
            planRepository.insertVersion(versionId, tripId, versionNo, etag, contentHash,
                    version.schemaVersion(), task.ownerId(), now);
            Map<String, Object> summary = new LinkedHashMap<>(mapOrEmpty(plan.get("summary")));
            if (!variants.isEmpty()) {
                summary.put("variants", variants);
            }
            planRepository.insertPlanSnapshot(UUID.randomUUID(), versionId, planJson, canonicalJson.write(summary), now);
            if (planRepository.setTripCurrentVersion(tripId, versionId, now) != 1) {
                throw new IllegalStateException("TRIP_VERSION_BIND_FAILED");
            }
        }

        if (task.reservedPoints() > 0) {
            pointsService.confirmForTask(task.ownerId(), new PointsModels.SettlementRequest(
                    "reserve:" + task.ownerId() + ":task-reserve:" + task.id()), "task-confirm:" + task.id());
        }
        TaskStatus terminal = "ok".equals(status) ? TaskStatus.SUCCEEDED : TaskStatus.PARTIAL;
        if (repository.transitionStatus(task.id(), TaskStatus.ASSEMBLING, terminal, task.stateVersion(),
                now) != 1) {
            throw new IllegalStateException("TASK_FINAL_TRANSITION_FAILED");
        }
        List<String> degraded = result.degradedFields();
        repository.appendEvent(task.id(), "TASK_VERSION_CREATED",
                new TaskModels.EventData(terminal.name(), claim.stepName(), claim.attemptNo(),
                        "已创建不可变版本 v" + version.versionNo() + " " + version.id(), degraded));
        audit.write(null, "TASK_VERSION_CREATED", "TRIP_VERSION", version.id(), "SUCCESS", terminal.name());
        return true;
    }

    private UUID createTrip(TaskRepository.TaskRow task, Map<String, Object> plan, Instant now) {
        Map<String, Object> trip = map(plan.get("trip"));
        if (trip == null) {
            throw new IllegalArgumentException("PLAN_TRIP_MISSING");
        }
        LocalDate start = LocalDate.parse(String.valueOf(trip.get("start_date")));
        LocalDate end = LocalDate.parse(String.valueOf(trip.get("end_date")));
        String city = String.valueOf(trip.getOrDefault("city_name", trip.get("city_id")));
        String title = city + " " + start + " 至 " + end + " 行程";
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }
        UUID tripId = UUID.randomUUID();
        planRepository.insertTrip(tripId, task.ownerId(), title, start, end, now);
        return tripId;
    }

    private int expectedCount(UUID taskId) {
        String request = repository.taskRequestJson(taskId);
        if (request == null) {
            return 1;
        }
        try {
            return mapper.readTree(request).path("plan_count").asInt(1);
        } catch (JsonProcessingException exception) {
            return -1;
        }
    }

    private boolean validVariants(int count, Map<String, Object> primary, List<?> variants) {
        if (count == 1) {
            return variants.isEmpty();
        }
        if (count < 2 || count > 3 || variants.size() != count) {
            return false;
        }
        var ids = new HashSet<String>();
        String shared = null;
        for (Object item : variants) {
            Map<String, Object> variant = map(item);
            if (variant == null || !(variant.get("variant_id") instanceof String id) || !ids.add(id)
                    || !(variant.get("shared_snapshot_hash") instanceof String snapshot)
                    || !snapshot.matches("[a-f0-9]{64}") || (shared != null && !shared.equals(snapshot))) {
                return false;
            }
            Map<String, Object> plan = map(variant.get("plan"));
            if (plan == null || !SUPPORTED_PLAN_VERSION.equals(plan.get("plan_version"))
                    || confirmationRequired(plan) || (shared == null && !primary.equals(plan))) {
                return false;
            }
            shared = snapshot;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : null;
    }

    private Map<String, Object> mapOrEmpty(Object value) {
        Map<String, Object> result = map(value);
        return result == null ? Map.of() : result;
    }

    private boolean confirmationRequired(Map<String, Object> plan) {
        Map<String, Object> confirmation = map(plan.get("confirmation"));
        return confirmation != null && Boolean.TRUE.equals(confirmation.get("required"));
    }

    private String confirmationCodes(Map<String, Object> plan) {
        Map<String, Object> confirmation = map(plan.get("confirmation"));
        if (confirmation == null || !(confirmation.get("conflict_codes") instanceof List<?> codes)) {
            return "";
        }
        return String.join(",", codes.stream().map(String::valueOf).toList());
    }

    private byte[] sha256(String value) {
        return TaskPlanRepository.sha256(value);
    }
}
