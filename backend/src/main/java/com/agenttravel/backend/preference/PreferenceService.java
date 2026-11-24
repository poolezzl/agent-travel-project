package com.agenttravel.backend.preference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agenttravel.backend.common.ApiException;

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class PreferenceService {

    private static final Map<String, String> DIMENSION_REASONS = Map.of(
            "city", "统计历史行程中出现次数最多的城市",
            "theme", "从行程偏好标签中统计高频主题",
            "pace", "按每日景点数量划分行程节奏",
            "budget", "按行程预算金额划分消费档位",
            "season", "按出行日期所属季节统计");

    private static final int MIN_SAMPLES = 3;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_DIFF_PATHS = 200;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(5);

    private record IdempotencyEntry(PreferenceModels.ReuseResult result, Instant createdAt) {
    }

    private final PreferenceRepository repository;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, IdempotencyEntry> idempotency = new ConcurrentHashMap<>();

    public PreferenceService(PreferenceRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    public List<PreferenceModels.HistoricalTrip> listTrips(UUID owner, LocalDate from, LocalDate to,
            String city, Integer days, String status, String query, int limit, int offset) {
        return repository.findTrips(owner, from, to, city, days, status, query, limit, offset).stream()
                .map(row -> new PreferenceModels.HistoricalTrip(
                        row.id(), row.title(), row.startDate(), row.endDate(), row.status(),
                        repository.findCities(row.id()), repository.countVersions(row.id()),
                        row.currentVersionId()))
                .toList();
    }

    @Transactional
    public synchronized PreferenceModels.ReuseResult reuse(UUID owner, UUID tripId, String key) {
        String keyHash = hashKey(key);
        String idempotencyKey = owner + ":" + keyHash;
        Instant now = Instant.now();
        IdempotencyEntry prior = idempotency.get(idempotencyKey);
        if (prior != null && !prior.createdAt().plus(IDEMPOTENCY_TTL).isBefore(now)) {
            return prior.result();
        }

        PreferenceRepository.TripRow source = repository.findTrip(owner, tripId);
        if (source == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "行程不存在或不属于当前用户");
        }
        UUID newTrip = UUID.randomUUID();
        UUID newVersion = UUID.randomUUID();
        repository.copyTripAsDraft(tripId, newTrip, owner, source.title(),
                source.startDate(), source.endDate(), now);

        PreferenceRepository.VersionRow version = repository.findCurrentVersion(tripId);
        String planJson = version == null ? "{\"days\":[]}" : sanitize(version.planJson());
        String schemaVersion = version == null ? null : version.schemaVersion();
        repository.insertVersion(newVersion, newTrip, owner, planJson, schemaVersion,
                version == null ? null : version.modelVersion(),
                version == null ? null : version.promptVersion(), now);

        PreferenceModels.ReuseResult result = new PreferenceModels.ReuseResult(newTrip, newVersion,
                List.of("lodging.address", "privatePreferences"));
        idempotency.put(idempotencyKey, new IdempotencyEntry(result, now));
        return result;
    }

    private static String hashKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "去重键不合法");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sanitize(String planJson) {
        try {
            var tree = json.readTree(planJson);
            if (tree.has("lodging") && tree.get("lodging").isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) tree.get("lodging")).remove("address");
            }
            if (tree.has("privatePreferences")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove("privatePreferences");
            }
            return tree.toString();
        } catch (JsonProcessingException exception) {
            return "{\"days\":[]}";
        }
    }

    public PreferenceModels.VersionComparison compare(UUID owner, UUID tripId, UUID fromVersion, UUID toVersion) {
        PreferenceRepository.TripRow trip = repository.findTrip(owner, tripId);
        if (trip == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "行程不存在或不属于当前用户");
        }
        List<PreferenceRepository.VersionMetaRow> metas = repository.findVersionMetas(owner, tripId);
        if (metas.size() < 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VERSION_INSUFFICIENT", "至少需要两个版本才能对比");
        }

        PreferenceRepository.VersionMetaRow from = resolveVersion(metas, fromVersion, metas.get(0));
        PreferenceRepository.VersionMetaRow to = resolveVersion(metas, toVersion, metas.get(metas.size() - 1));
        String fromJson = repository.findPlanJson(owner, tripId, from.id());
        String toJson = repository.findPlanJson(owner, tripId, to.id());
        if (fromJson == null || toJson == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "VERSION_SNAPSHOT_MISSING", "版本快照不存在");
        }

        List<PreferenceModels.VersionChange> changes = diff(fromJson, toJson);
        return new PreferenceModels.VersionComparison(tripId, toSummary(from), toSummary(to), changes);
    }

    private static PreferenceRepository.VersionMetaRow resolveVersion(
            List<PreferenceRepository.VersionMetaRow> metas, UUID requested,
            PreferenceRepository.VersionMetaRow fallback) {
        if (requested == null) {
            return fallback;
        }
        return metas.stream().filter(meta -> meta.id().equals(requested)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", "版本不属于该行程"));
    }

    private static PreferenceModels.VersionSummary toSummary(PreferenceRepository.VersionMetaRow meta) {
        return new PreferenceModels.VersionSummary(meta.id(), meta.versionNo(), meta.versionStatus(),
                meta.createdAt(), meta.schemaVersion());
    }

    private List<PreferenceModels.VersionChange> diff(String fromJson, String toJson) {
        java.util.Map<String, JsonNode> before = flatten(parsePlan(fromJson));
        java.util.Map<String, JsonNode> after = flatten(parsePlan(toJson));
        java.util.TreeSet<String> paths = new java.util.TreeSet<>();
        paths.addAll(before.keySet());
        paths.addAll(after.keySet());

        List<PreferenceModels.VersionChange> changes = new ArrayList<>();
        for (String path : paths) {
            if (changes.size() >= MAX_DIFF_PATHS) {
                break;
            }
            JsonNode oldValue = before.get(path);
            JsonNode newValue = after.get(path);
            if (java.util.Objects.equals(oldValue, newValue)) {
                continue;
            }
            String type = oldValue == null ? "ADDED" : newValue == null ? "REMOVED" : "MODIFIED";
            changes.add(new PreferenceModels.VersionChange(path, type, valueOf(oldValue), valueOf(newValue)));
        }
        return changes;
    }

    private JsonNode parsePlan(String planJson) {
        try {
            JsonNode node = json.readTree(planJson);
            return node == null ? json.createObjectNode() : node;
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PLAN_SNAPSHOT_INVALID", "版本快照不是合法 JSON");
        }
    }

    private static void flatten(JsonNode node, String path, java.util.Map<String, JsonNode> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    flatten(entry.getValue(), path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), out));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(node.get(i), path + "[" + i + "]", out);
            }
        } else {
            out.put(path, node);
        }
    }

    private static java.util.Map<String, JsonNode> flatten(JsonNode node) {
        java.util.Map<String, JsonNode> out = new java.util.HashMap<>();
        flatten(node, "", out);
        return out;
    }

    private static Object valueOf(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNull()) {
            return null;
        }
        return node.toString();
    }

    public PreferenceModels.PreferenceSummary summary(UUID user) {
        int sampleCount = repository.countHistories(user);
        int version = repository.nextSummaryVersion(user);
        boolean eligible = sampleCount >= MIN_SAMPLES;

        List<PreferenceModels.PreferenceItem> dimensions = eligible ? buildDimensions(user) : List.of();
        String narrative = eligible ? buildNarrative(dimensions, sampleCount) : "";
        Instant now = Instant.now();

        String summaryJson = writeJson(java.util.Map.of(
                "dimensions", dimensions,
                "narrative", narrative));
        repository.saveSummary(user, version, sampleCount, eligible, summaryJson, narrative, now);

        return new PreferenceModels.PreferenceSummary(version, sampleCount, eligible,
                dimensions, narrative, now);
    }

    private List<PreferenceModels.PreferenceItem> buildDimensions(UUID user) {
        List<PreferenceModels.PreferenceItem> result = new ArrayList<>();
        Instant now = Instant.now();

        List<PreferenceRepository.CityCount> cities = repository.topCities(user, DEFAULT_TOP_K);
        if (!cities.isEmpty()) {
            List<String> names = cities.stream().map(PreferenceRepository.CityCount::city).toList();
            result.add(item("city", names, cities.get(0).count(), now));
            for (PreferenceRepository.CityCount city : cities) {
                repository.upsertPreference(user, "city." + city.city(),
                        "{\"count\":" + city.count() + "}", "HISTORY_ANALYSIS",
                        0.8, city.count(), now);
            }
        }

        List<PreferenceRepository.PlanFact> facts = repository.findLatestPlanFacts(user);
        Map<String, Map<String, Integer>> counters = new java.util.LinkedHashMap<>();
        for (String key : new String[]{"theme", "pace", "budget", "season"}) {
            counters.put(key, new java.util.LinkedHashMap<>());
        }
        for (PreferenceRepository.PlanFact fact : facts) {
            JsonNode plan = parsePlanLenient(fact.planJson());
            for (String theme : extractStrings(plan.at("/trip/preferences"))) {
                bump(counters.get("theme"), theme);
            }
            String pace = extractPace(plan);
            if (pace != null) {
                bump(counters.get("pace"), pace);
            }
            String budget = extractBudgetTier(plan);
            if (budget != null) {
                bump(counters.get("budget"), budget);
            }
            String season = extractSeason(fact.startDate());
            if (season != null) {
                bump(counters.get("season"), season);
            }
        }
        for (Map.Entry<String, Map<String, Integer>> entry : counters.entrySet()) {
            Map<String, Integer> counts = entry.getValue();
            if (counts.isEmpty()) {
                continue;
            }
            String top = counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
            result.add(item(entry.getKey(), counts, counts.get(top), now));
            counts.forEach((value, count) -> repository.upsertPreference(user,
                    entry.getKey() + "." + value, "{\"count\":" + count + "}", "HISTORY_ANALYSIS",
                    0.7, count, now));
        }

        for (PreferenceRepository.StatusCount item : repository.statusCounts(user)) {
            repository.upsertPreference(user, "status." + item.status(),
                    "{\"count\":" + item.count() + "}", "HISTORY_ANALYSIS",
                    0.7, item.count(), now);
        }
        return result;
    }

    private PreferenceModels.PreferenceItem item(String key, Object value, int weight, Instant now) {
        return new PreferenceModels.PreferenceItem(key, writeJson(value), "HISTORY_ANALYSIS",
                null, weight, now, DIMENSION_REASONS.get(key));
    }

    private static void bump(Map<String, Integer> counts, String value) {
        counts.merge(value, counts.getOrDefault(value, 0) + 1, Integer::sum);
    }

    private JsonNode parsePlanLenient(String planJson) {
        try {
            JsonNode node = json.readTree(planJson);
            return node == null ? json.createObjectNode() : node;
        } catch (Exception exception) {
            return json.createObjectNode();
        }
    }

    private static List<String> extractStrings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode element : node) {
                if (element.isTextual() && !element.asText().isBlank()) {
                    values.add(element.asText());
                }
            }
        }
        return values;
    }

    private static String extractPace(JsonNode plan) {
        JsonNode pointCount = plan.at("/summary/point_count");
        JsonNode dayCount = plan.at("/trip/day_count");
        if (pointCount.isNumber() && dayCount.isNumber() && dayCount.asInt() > 0) {
            double pointsPerDay = pointCount.asDouble() / dayCount.asInt();
            return pointsPerDay < 2 ? "轻松" : pointsPerDay <= 4 ? "适中" : "紧凑";
        }
        return null;
    }

    private static String extractBudgetTier(JsonNode plan) {
        JsonNode amount = plan.at("/trip/budget/amount_minor");
        if (!amount.isNumber()) {
            return null;
        }
        double yuan = amount.asDouble() / 100;
        return yuan < 1000 ? "经济" : yuan <= 5000 ? "舒适" : "高端";
    }

    private static String extractSeason(LocalDate date) {
        if (date == null) {
            return null;
        }
        int month = date.getMonthValue();
        if (month <= 2 || month == 12) {
            return "冬季";
        }
        if (month <= 5) {
            return "春季";
        }
        if (month <= 8) {
            return "夏季";
        }
        return "秋季";
    }

    private String buildNarrative(List<PreferenceModels.PreferenceItem> dimensions, int sampleCount) {
        StringBuilder text = new StringBuilder("根据 ").append(sampleCount).append(" 条历史行程，");
        for (PreferenceModels.PreferenceItem item : dimensions) {
            if ("city".equals(item.key())) {
                text.append("你较常前往 ");
                try {
                    List<?> names = json.readValue(String.valueOf(item.valueJson()), List.class);
                    text.append(String.join("、", names.stream().map(String::valueOf).limit(3).toList()));
                } catch (Exception exception) {
                    text.append("若干城市");
                }
                text.append("。");
            }
        }
        if (text.toString().endsWith("，")) {
            text.append("已积累一定的出行偏好。");
        }
        return text.toString();
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Transactional
    public void clear(UUID user, String key) {
        hashKey(key);
        repository.clearPreferences(user, Instant.now());
    }

    public List<PreferenceModels.PreferenceItem> recalledPreferences(UUID user, int topK) {
        int limit = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, 20);
        return repository.findActivePreferences(user, limit).stream()
                .map(row -> new PreferenceModels.PreferenceItem(row.key(), row.valueJson(),
                        "HISTORY_ANALYSIS", row.confidence(), row.weight(), row.lastUsedAt(),
                        DIMENSION_REASONS.getOrDefault(
                                row.key().contains(".") ? row.key().substring(0, row.key().indexOf('.')) : row.key(),
                                "由历史行程统计得出")))
                .toList();
    }
}
