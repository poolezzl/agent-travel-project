package com.agenttravel.backend.traveltools;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.agenttravel.backend.adapter.AdapterProperties;
import com.agenttravel.backend.adapter.FreshnessStatus;
import com.agenttravel.backend.adapter.ProviderException;
import com.agenttravel.backend.adapter.WeatherAdapter;
import com.agenttravel.backend.adapter.WeatherRecord;
import com.agenttravel.backend.adapter.WeatherRequest;
import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import com.agenttravel.backend.knowledge.KnowledgeRepository;
import com.agenttravel.backend.knowledge.ReviewStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
class ReminderService {
    private static final String SUBSCRIPTION = """
            SELECT BIN_TO_UUID(t.id), BIN_TO_UUID(s.trip_version_id), BIN_TO_UUID(s.user_id), p.plan_json, t.end_date
            FROM trip_reminder_subscription s JOIN trip_version v ON v.id=s.trip_version_id
            JOIN trip t ON t.id=v.trip_id JOIN user_account u ON u.id=s.user_id
            JOIN plan_snapshot p ON p.trip_version_id=v.id
            WHERE t.current_version_id=v.id AND t.owner_id=s.user_id AND t.deleted_at IS NULL
                AND t.status<>'DELETED' AND u.status='ACTIVE' AND u.deleted_at IS NULL AND v.version_status='PUBLISHED'
                AND s.cancelled_at IS NULL AND s.next_check_at<=?
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ExportService exports;
    private final KnowledgeRepository knowledge;
    private final AuditRepository audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final WeatherAdapter weather;
    private final ZoneId weatherZone;
    private final int forecastDays;
    private final boolean schedulingEnabled;
    private final boolean realWeather;

    ReminderService(JdbcTemplate jdbc, TransactionTemplate tx, ExportService exports, KnowledgeRepository knowledge,
            AuditRepository audit, ObjectMapper json, Clock clock, WeatherAdapter weather,
            AdapterProperties properties, @Value("${app.s26.scheduling-enabled:true}") boolean schedulingEnabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.exports = exports;
        this.knowledge = knowledge;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.weather = weather;
        this.weatherZone = ZoneId.of(properties.getQweatherZoneId());
        this.forecastDays = properties.getQweatherForecastDays();
        this.realWeather = "real".equals(properties.getMode());
        this.schedulingEnabled = schedulingEnabled;
    }

    void authorize(UUID owner, UUID trip, UUID version) {
        tx.executeWithoutResult(status -> {
            exports.requireVersion(owner, trip, version, true);
            Boolean cancelled = jdbc.query("""
                    SELECT cancelled_at IS NOT NULL FROM trip_reminder_subscription
                    WHERE trip_version_id=UUID_TO_BIN(?) AND user_id=UUID_TO_BIN(?)
                    """, rs -> rs.next() ? rs.getBoolean(1) : null, version.toString(), owner.toString());
            if (Boolean.TRUE.equals(cancelled)) {
                throw new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", "此版本的提醒授权已撤销");
            }
            if (cancelled == null) {
                jdbc.update("""
                        INSERT INTO trip_reminder_subscription(trip_version_id,user_id,next_check_at)
                        VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?)
                        """, version.toString(), owner.toString(), Timestamp.from(clock.instant()));
                audit.write(owner, "REMINDER_AUTHORIZE", "TRIP_VERSION", version, "SUCCESS", null);
            }
        });
    }

    void cancel(UUID owner, UUID trip, UUID version) {
        tx.executeWithoutResult(status -> {
            exports.requireVersion(owner, trip, version, false);
            Timestamp now = Timestamp.from(clock.instant());
            int updated = jdbc.update("""
                    UPDATE trip_reminder_subscription SET next_check_at=NULL,cancelled_at=?
                    WHERE trip_version_id=UUID_TO_BIN(?) AND user_id=UUID_TO_BIN(?) AND cancelled_at IS NULL
                    """, now, version.toString(), owner.toString());
            if (updated > 0) {
                audit.write(owner, "REMINDER_CANCEL", "TRIP_VERSION", version, "SUCCESS", null);
            }
        });
    }

    List<Notice> notifications(UUID owner, int page) {
        Instant now = clock.instant();
        return jdbc.query("""
                SELECT BIN_TO_UUID(n.id),BIN_TO_UUID(c.trip_id),n.notification_type,n.delivered_at,
                    n.payload_json,n.expires_at
                FROM notification n JOIN reminder_candidate c ON c.id=n.reminder_candidate_id
                JOIN trip t ON t.id=c.trip_id
                WHERE n.user_id=UUID_TO_BIN(?) AND c.user_id=n.user_id AND t.owner_id=n.user_id
                    AND t.deleted_at IS NULL AND t.status<>'DELETED' AND n.status='DELIVERED'
                    AND c.subscription_version_id IS NOT NULL
                ORDER BY n.delivered_at DESC,n.id DESC LIMIT 20 OFFSET ?
                """, (rs, n) -> {
                    JsonNode payload = read(rs.getString(5));
                    if (!rs.getTimestamp(6).toInstant().isAfter(now)) {
                        ((ObjectNode) payload).put("freshness_status", "stale")
                                .put("message", "提醒依据已过期，请查询最新信息。");
                    }
                    return new Notice(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                            rs.getString(3), rs.getTimestamp(4).toInstant(), payload);
                }, owner.toString(), page * 20);
    }

    @Scheduled(fixedDelayString = "${app.s26.reminder-delay-ms:60000}", scheduler = "travelToolsScheduler")
    void scheduledWork() {
        if (schedulingEnabled) {
            runReminders();
        }
    }

    void runReminders() {
        Instant scanTime = clock.instant();
        var due = jdbc.query(SUBSCRIPTION + " ORDER BY s.next_check_at,s.trip_version_id LIMIT 30",
                (rs, n) -> new Subscription(rs.getString(1), rs.getString(2), rs.getString(3),
                        read(rs.getString(4)), rs.getObject(5, LocalDate.class)), Timestamp.from(scanTime));
        for (Subscription subscription : due) {
            try {
                List<Forecast> forecasts = checkWeather(subscription, scanTime);
                tx.executeWithoutResult(status -> {
                    // 授权、取消、版本切换与投递共用行程锁。
                    boolean tripLocked = Boolean.TRUE.equals(jdbc.query("""
                            SELECT id FROM trip WHERE id=UUID_TO_BIN(?) FOR UPDATE
                            """, rs -> {
                                return rs.next();
                            }, subscription.trip()));
                    if (!tripLocked) {
                        return;
                    }
                    var eligible = jdbc.query(SUBSCRIPTION + """
                             AND t.id=UUID_TO_BIN(?) AND s.trip_version_id=UUID_TO_BIN(?)
                             FOR UPDATE OF s
                            """, rs -> {
                                return rs.next();
                            }, Timestamp.from(scanTime), subscription.trip(), subscription.version());
                    if (Boolean.TRUE.equals(eligible)) {
                        process(subscription, forecasts);
                    }
                });
            } catch (RuntimeException exception) {
                LoggerFactory.getLogger(getClass()).warn("提醒处理暂未成功，版本编号 {}，错误类型 {}",
                        subscription.version(), exception.getClass().getSimpleName());
                // 失败后让同批其他行程继续，重试仍受版本和取消状态约束。
                jdbc.update("""
                        UPDATE trip_reminder_subscription SET next_check_at=?
                        WHERE trip_version_id=UUID_TO_BIN(?) AND cancelled_at IS NULL AND next_check_at<=?
                        """, Timestamp.from(clock.instant().plusSeconds(60)), subscription.version(), Timestamp.from(scanTime));
            }
        }
    }

    private List<Forecast> checkWeather(Subscription subscription, Instant now) {
        try {
            return forecasts(subscription.plan(), now);
        } catch (RuntimeException exception) {
            LoggerFactory.getLogger(getClass()).warn("天气复查暂未成功，版本编号 {}，错误类型 {}",
                    subscription.version(), exception.getClass().getSimpleName());
            return List.of();
        }
    }

    private void process(Subscription subscription, List<Forecast> forecasts) {
        Instant now = clock.instant();
        for (JsonNode day : subscription.plan().path("days")) {
            for (JsonNode booking : day.path("reservations")) {
                reservation(subscription, day, booking, now);
            }
        }
        for (Forecast forecast : forecasts) {
            weatherReminder(subscription, forecast, now);
        }
        Timestamp next = subscription.end().isBefore(LocalDate.ofInstant(now, weatherZone))
                ? null : Timestamp.from(now.plusSeconds(300));
        jdbc.update("UPDATE trip_reminder_subscription SET next_check_at=? WHERE trip_version_id=UUID_TO_BIN(?)",
                next, subscription.version());
    }

    private void weatherReminder(Subscription subscription, Forecast forecast, Instant now) {
        WeatherRecord record = forecast.record();
        Instant retrieved = record.retrievedAt().truncatedTo(ChronoUnit.MICROS);
        if (!record.validUntil().isAfter(now) || record.retrievedAt().isAfter(now)) {
            return;
        }
        WeatherState previous = jdbc.query("""
                SELECT risk_signature,retrieved_at FROM weather_reminder_state
                WHERE trip_id=UUID_TO_BIN(?) AND city_code=? AND forecast_date=?
                """, rs -> rs.next() ? new WeatherState(rs.getString(1), rs.getTimestamp(2).toInstant()) : null,
                subscription.trip(), forecast.city(), record.date());
        if (previous != null && !retrieved.isAfter(previous.retrievedAt())) {
            return;
        }
        // 缺失的降水概率不能代表风险已经消退。
        boolean heavyRain = record.precipProbability() != null ? record.precipProbability() >= 0.5
                : previous != null && Boolean.parseBoolean(previous.signature().split("\\|")[2]);
        String signature = risks(record.condition(), record.tempMin(), record.tempMax(), heavyRain);
        jdbc.update("""
                INSERT INTO weather_reminder_state(trip_id,city_code,forecast_date,risk_signature,retrieved_at)
                VALUES(UUID_TO_BIN(?),?,?,?,?) AS latest
                ON DUPLICATE KEY UPDATE risk_signature=latest.risk_signature,retrieved_at=latest.retrieved_at
                """, subscription.trip(), forecast.city(), record.date(), signature, Timestamp.from(retrieved));
        if (!forecast.differsFromPlan() || (previous != null && signature.equals(previous.signature()))) {
            return;
        }
        // 升级后首次观测同一风险时沿用旧通知，已观测到风险消退后可再次投递。
        if (previous == null && alreadyDelivered(subscription,
                key(subscription.trip(), "WEATHER", forecast.city(), record.date() + "|" + signature))) {
            return;
        }
        String key = key(subscription.trip(), "WEATHER_TRANSITION", forecast.city(), record.date() + "|" + retrieved);
        UUID source = weatherSnapshot(record, forecast.provider());
        ObjectNode payload = payload(record.date(), "天气预报变化：" + record.condition() + "，请复核户外安排。",
                "weather", record.sourceRecordId(), record.retrievedAt(), record.validUntil());
        deliver(subscription, key, "WEATHER", "WEATHER_CHANGE", null, source,
                now, weatherZone, now, record.validUntil(), payload);
    }

    private void reservation(Subscription subscription, JsonNode day, JsonNode booking, Instant now) {
        if (subscription.plan().path("sample_only").asBoolean() || !booking.path("reservation_required").asBoolean()) {
            return;
        }
        String policy = booking.path("policy_id").asText();
        String point = booking.path("point_id").asText();
        if (point.isBlank() || !policy.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            return;
        }
        var rule = knowledge.findRule(UUID.fromString(policy));
        if (rule == null || rule.status() != ReviewStatus.PUBLISHED || !rule.reservationRequired()
                || rule.releaseTime() == null || rule.advanceDays() == null || rule.lastVerifiedAt().isAfter(now)
                || !rule.cityCode().equals(day.path("city_id").asText()) || rule.effectiveFrom().isAfter(now)) {
            return;
        }
        var source = knowledge.findSourceSnapshot(rule.sourceSnapshotId());
        if (source == null || !"ACTIVE".equals(source.sourceStatus()) || !"FRESH".equals(source.freshnessStatus())
                || source.retrievedAt().isAfter(now) || source.validUntil() == null) {
            return;
        }
        Boolean trusted = jdbc.queryForObject("""
                SELECT ss.is_estimated=FALSE AND sr.source_type NOT IN ('USER_INPUT','SYSTEM')
                FROM source_snapshot ss JOIN source_record sr ON sr.id=ss.source_record_id WHERE ss.id=UUID_TO_BIN(?)
                """, Boolean.class, rule.sourceSnapshotId().toString());
        if (!Boolean.TRUE.equals(trusted)) {
            return;
        }
        try {
            ZoneId zone = ZoneId.of(rule.releaseTimezone());
            LocalDate date = LocalDate.parse(day.path("date").asText());
            Instant visit = date.atStartOfDay(zone).toInstant();
            if (visit.isBefore(rule.effectiveFrom())
                    || (rule.effectiveUntil() != null && !rule.effectiveUntil().isAfter(visit))) {
                return;
            }
            var release = date.minusDays(rule.advanceDays()).atTime(rule.releaseTime()).atZone(zone);
            for (JsonNode item : booking.path("timeline")) {
                if ("pending".equals(item.path("status").asText()) && point.equals(item.path("point_id").asText())
                        && policy.equals(item.path("policy_id").asText()) && zone.getId().equals(item.path("timezone").asText())
                        && release.toInstant().equals(ExportRenderer.instant(item.path("release_at").asText()))) {
                    Instant hour = release.minusHours(1).toInstant();
                    boolean lastHour = !now.isBefore(hour);
                    Instant scheduled = lastHour ? hour : release.minusDays(1).toInstant();
                    Instant expiry = earliest(lastHour ? release.toInstant() : hour,
                            earliest(rule.effectiveUntil(), earliest(source.validUntil(),
                                    rule.lastVerifiedAt().plus(rule.staleAfterDays(), ChronoUnit.DAYS))));
                    if (!now.isBefore(scheduled) && expiry.isAfter(now)) {
                        String stage = lastHour ? "RESERVATION_HOUR" : "RESERVATION_DAY";
                        String key = key(subscription.trip(), stage, rule.ruleKey(), release.toInstant().toString());
                        ObjectNode payload = payload(date, rule.poiName() + "预约即将开放，请核对官方渠道。",
                                "reservation_rule", source.externalRecordId(), source.retrievedAt(), expiry);
                        payload.put("release_at", release.toOffsetDateTime().toString()).put("timezone", zone.getId());
                        deliver(subscription, key, "RESERVATION", stage, rule.policyId(), null,
                                scheduled, zone, now, expiry, payload);
                    }
                    return;
                }
            }
        } catch (DateTimeException exception) {
            // 规则时区或日期不可用时不发送确定性通知。
        }
    }

    private List<Forecast> forecasts(JsonNode plan, Instant now) {
        if (!realWeather || plan.path("sample_only").asBoolean()) {
            return List.of();
        }
        LocalDate today = LocalDate.ofInstant(now, weatherZone);
        List<Forecast> result = new ArrayList<>();
        for (JsonNode day : plan.path("days")) {
            JsonNode previous = day.path("weather");
            if (!baseline(plan, previous)) {
                continue;
            }
            LocalDate date = LocalDate.parse(day.path("date").asText());
            String city = day.path("city_id").asText();
            if (city.isBlank() || date.isBefore(today) || !date.isBefore(today.plusDays(forecastDays))) {
                continue;
            }
            try {
                var response = weather.forecast(new WeatherRequest(city, date, date, forecastDays));
                if (response.degraded() || response.source().estimated()) {
                    continue;
                }
                Instant received = clock.instant();
                for (WeatherRecord record : response.value()) {
                    if (date.equals(record.date()) && "forecast".equals(record.dataType())
                            && record.freshnessStatus() == FreshnessStatus.FRESH && !record.retrievedAt().isAfter(received)
                            && record.validUntil() != null && record.validUntil().isAfter(received)) {
                        result.add(new Forecast(city, response.source().providerAlias(), record, changed(previous, record)));
                    }
                }
            } catch (ProviderException exception) {
                // 供应商缺席或失败时保留授权，由下轮重查。
            }
        }
        return result;
    }

    private static boolean baseline(JsonNode plan, JsonNode previous) {
        String source = previous.path("source_record_id").asText();
        Instant generated = ExportRenderer.instant(plan.path("generated_at").asText());
        if (source.isBlank() || previous.path("stale").asBoolean() || generated == null
                || !List.of("forecast", "climate_statistics").contains(previous.path("data_type").asText())
                || previous.path("condition").asText().isBlank()
                || !previous.path("temp_min").isNumber() || !previous.path("temp_max").isNumber()) {
            return false;
        }
        for (JsonNode metadata : plan.path("sources")) {
            Instant retrieved = ExportRenderer.instant(metadata.path("retrieved_at").asText());
            Instant valid = ExportRenderer.instant(metadata.path("valid_until").asText());
            if (source.equals(metadata.path("source_record_id").asText())
                    && "weather".equals(metadata.path("source_type").asText())
                    && "fresh".equals(metadata.path("freshness_status").asText()) && !metadata.path("is_estimated").asBoolean()
                    && retrieved != null && !retrieved.isAfter(generated) && valid != null && valid.isAfter(generated)) {
                return true;
            }
        }
        return false;
    }

    static boolean changed(JsonNode previous, WeatherRecord record) {
        String before = previous.path("condition").asText();
        JsonNode probability = previous.path("precip_prob");
        return (previous.path("temp_min").asDouble() <= 0) != (record.tempMin() <= 0)
                || (previous.path("temp_max").asDouble() >= 35) != (record.tempMax() >= 35)
                || (probability.isNumber() && record.precipProbability() != null
                    && (probability.asDouble() >= 0.5) != (record.precipProbability() >= 0.5))
                || wet(before) != wet(record.condition())
                || severe(before) != severe(record.condition());
    }

    private static String risks(String condition, double min, double max, boolean heavyRain) {
        return (min <= 0) + "|" + (max >= 35) + "|" + heavyRain + "|" + wet(condition) + "|" + severe(condition);
    }

    private static boolean wet(String condition) {
        return condition.matches(".*[雨雪雹].*");
    }

    private static boolean severe(String condition) {
        return condition.matches(".*(暴雨|暴雪|冰雹|雷雨|台风).*");
    }

    private boolean alreadyDelivered(Subscription subscription, String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM reminder_candidate WHERE user_id=UUID_TO_BIN(?) AND dedupe_key=?)
                """, Boolean.class, subscription.owner(), key));
    }

    private void deliver(Subscription subscription, String key, String type, String stage, UUID rule, UUID source,
            Instant scheduled, ZoneId zone, Instant now, Instant expiry, ObjectNode payload) {
        if (alreadyDelivered(subscription, key)) {
            return;
        }
        String candidate = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO reminder_candidate(id,user_id,trip_id,source_type,reservation_rule_id,source_snapshot_id,
                    candidate_type,occurs_at,timezone,dedupe_key,status,payload_json,subscription_version_id)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?,'CONFIRMED',
                    CAST(? AS JSON),UUID_TO_BIN(?))
                """, candidate, subscription.owner(), subscription.trip(), rule == null ? "WEATHER" : "RESERVATION_RULE",
                rule == null ? null : rule.toString(), source == null ? null : source.toString(),
                stage, Timestamp.from(scheduled), zone.getId(), key, payload.toString(), subscription.version());
        jdbc.update("""
                INSERT INTO notification(id,user_id,reminder_candidate_id,notification_type,dedupe_key,status,
                    scheduled_at,delivered_at,expires_at,payload_json)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,'DELIVERED',?,?,?,CAST(? AS JSON))
                """, UUID.randomUUID().toString(), subscription.owner(), candidate, type, key,
                Timestamp.from(scheduled), Timestamp.from(now), Timestamp.from(expiry), payload.toString());
    }

    private UUID weatherSnapshot(WeatherRecord record, String provider) {
        jdbc.update("""
                INSERT INTO source_record(id,source_type,provider_name,external_record_id)
                VALUES(UUID_TO_BIN(?),'WEATHER',?,?) ON DUPLICATE KEY UPDATE id=id
                """, UUID.randomUUID().toString(), provider, record.sourceRecordId());
        String recordId = jdbc.queryForObject("""
                SELECT BIN_TO_UUID(id) FROM source_record WHERE source_type='WEATHER' AND provider_name=?
                    AND external_record_id=? AND status='ACTIVE' FOR UPDATE
                """, String.class, provider, record.sourceRecordId());
        ObjectNode facts = json.createObjectNode().put("date", record.date().toString()).put("condition", record.condition())
                .put("temp_min", record.tempMin()).put("temp_max", record.tempMax()).put("data_type", "forecast");
        if (record.precipProbability() != null) {
            facts.put("precip_prob", record.precipProbability());
        }
        String payload = facts.toString();
        byte[] digest = ExportService.digest(payload.getBytes(StandardCharsets.UTF_8));
        Timestamp retrieved = Timestamp.from(record.retrievedAt());
        jdbc.update("""
                INSERT INTO source_snapshot(id,source_record_id,retrieved_at,valid_until,freshness_status,content_hash,
                    sanitized_payload_json) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,'FRESH',?,CAST(? AS JSON))
                ON DUPLICATE KEY UPDATE id=id
                """, UUID.randomUUID().toString(), recordId, retrieved, Timestamp.from(record.validUntil()), digest, payload);
        return UUID.fromString(jdbc.queryForObject("""
                SELECT BIN_TO_UUID(id) FROM source_snapshot
                WHERE source_record_id=UUID_TO_BIN(?) AND content_hash=? AND retrieved_at=? FOR UPDATE
                """, String.class, recordId, digest, retrieved));
    }

    private ObjectNode payload(LocalDate date, String message, String type, String source, Instant retrieved, Instant expiry) {
        return json.createObjectNode().put("date", date.toString()).put("message", message)
                .put("source_type", type).put("source_record_id", source).put("retrieved_at", retrieved.toString())
                .put("valid_until", expiry.toString()).put("is_estimated", false).put("freshness_status", "fresh");
    }

    private static String key(String trip, String stage, String subject, String time) {
        String value = trip + "|" + stage + "|" + subject + "|" + time;
        return HexFormat.of().formatHex(ExportService.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Instant earliest(Instant first, Instant second) {
        return first == null || second.isBefore(first) ? second : first;
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("行程或提醒内容无效", exception);
        }
    }

    private record Subscription(String trip, String version, String owner, JsonNode plan, LocalDate end) {
    }

    private record Forecast(String city, String provider, WeatherRecord record, boolean differsFromPlan) {
    }

    private record WeatherState(String signature, Instant retrievedAt) {
    }

    record Notice(@JsonProperty("notification_id") UUID notificationId, @JsonProperty("trip_id") UUID tripId,
            String type, @JsonProperty("delivered_at") Instant deliveredAt, JsonNode payload) {
    }
}
