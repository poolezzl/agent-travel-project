package com.agenttravel.backend.community;

import static com.agenttravel.backend.community.CommunityModels.*;
import static com.agenttravel.backend.community.CommunitySafety.digest;
import static com.agenttravel.backend.community.CommunitySafety.invalid;

import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.AuditRepository;
import com.agenttravel.backend.community.CommunityRepository.PostState;
import com.agenttravel.backend.community.CommunityRepository.Ranking;
import com.agenttravel.backend.community.CommunityRepository.StoredPost;
import com.agenttravel.backend.community.CommunitySafety.Upload;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
@Transactional(isolation = Isolation.READ_COMMITTED)
class CommunityService {
    private final CommunityRepository repository;
    private final CommunitySafety safety;
    private final CommunityImages images;
    private final AuditRepository audit;
    private final Ranking ranking;
    private final int reactionLimit;
    private final Set<LocalDate> holidays;

    CommunityService(CommunityRepository repository, CommunitySafety safety, CommunityImages images,
            AuditRepository audit, @Value("${app.community.reaction-limit:20}") int reactionLimit,
            @Value("${app.community.ranking-weights:0.30,0.25,0.15,0.20,0.10}") List<Double> weights,
            @Value("${app.community.ranking-decay-hours:720}") int decayHours,
            @Value("${app.community.ranking-minimum:0.35}") double minimum,
            @Value("${app.community.ranking-holidays:}") List<String> holidays) {
        this.repository = repository;
        this.safety = safety;
        this.images = images;
        this.audit = audit;
        if (reactionLimit < 1 || decayHours < 1 || !Double.isFinite(minimum) || minimum <= 0 || minimum > 1
                || weights.size() != 5 || weights.stream().anyMatch(value -> !Double.isFinite(value) || value < 0)
                || Math.abs(weights.stream().mapToDouble(Double::doubleValue).sum() - 1) > 0.000001) {
            throw new IllegalArgumentException("社区限流或榜单配置不合法");
        }
        this.reactionLimit = reactionLimit;
        this.holidays = Set.copyOf(holidays.stream().filter(date -> !date.isBlank()).map(LocalDate::parse).toList());
        ranking = new Ranking(weights.get(0), weights.get(1), weights.get(2), weights.get(3), weights.get(4),
                decayHours, minimum);
    }

    public Id publish(UUID actor, String key, Publish request, List<MultipartFile> files) {
        List<License> licenses = request.imageLicenses() == null ? List.of() : request.imageLicenses();
        if (files.size() != licenses.size() || files.size() > 3) {
            throw invalid("每张图片都须声明来源和许可");
        }
        String title = safety.text(request.title(), TITLE_LIMIT);
        String body = safety.text(request.body(), POST_BODY_LIMIT);
        List<String> fileHashes = files.stream().map(this::uploadFingerprint).toList();
        return write(actor, key, "PUBLISH", 5, 60, false, List.of(request, fileHashes), () -> {
            String planJson = require(repository.sourcePlan(actor, request.sourceVersionId()));
            StoredPost content = project(body, repository.read(planJson, JsonNode.class));
            List<Upload> uploads = new ArrayList<>();
            for (int index = 0; index < files.size(); index++) {
                uploads.add(safety.read(files.get(index), licenses.get(index)));
            }
            UUID post = UUID.randomUUID();
            String json = repository.json(content);
            repository.write("""
                    INSERT INTO post(id,owner_id,source_version_id,visibility,moderation_status,title,content_json,
                        sanitized_content_json,published_at,community_theme,day_count)
                    VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'PUBLIC','PENDING',?,?,?,
                        UTC_TIMESTAMP(6),?,?)
                    """, post, actor, request.sourceVersionId(), title, json, json, request.theme().name(),
                    content.itinerary().days().size());
            for (int index = 0; index < uploads.size(); index++) {
                Upload upload = uploads.get(index);
                UUID object = UUID.randomUUID();
                String objectKey = images.store(upload.png());
                repository.write("""
                        INSERT INTO object_metadata(id,owner_id,object_key,storage_backend,mime_type,size_bytes,sha256,
                            license_type,source_uri,status)
                        VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,'LOCAL','image/png',?,?,?,?,'AVAILABLE')
                        """, object, actor, objectKey, upload.png().length, digest(upload.png()),
                        upload.license().kind().name(), upload.license().sourceUrl());
                repository.write("INSERT INTO post_image VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?)", post, object, index);
            }
            audit.write(actor, "COMMUNITY_PUBLISH", "POST", post, "SUCCESS", null);
            return post;
        });
    }

    private String uploadFingerprint(MultipartFile file) {
        if (file.getSize() > 2 * 1024 * 1024) {
            throw invalid("图片大小不合法");
        }
        try {
            return file.getContentType() + ":" + java.util.HexFormat.of().formatHex(digest(file.getBytes()));
        } catch (java.io.IOException exception) {
            throw invalid("图片无法读取");
        }
    }

    private StoredPost project(String body, JsonNode plan) {
        JsonNode trip = plan.path("trip");
        String city = safePlanText(trip.path("city_id"), 128);
        String cityName = safePlanText(trip.path("city_name"), 128);
        JsonNode originalDays = plan.path("days");
        if (!originalDays.isArray() || originalDays.isEmpty() || originalDays.size() > 30) {
            throw invalid("行程缺少可公开的天数");
        }
        List<Day> days = new ArrayList<>();
        for (JsonNode day : originalDays) {
            List<Point> points = new ArrayList<>();
            for (JsonNode segment : day.path("segments")) {
                String kind = segment.path("category").asText().toLowerCase(Locale.ROOT);
                if (!seemsPublicAttraction(kind, segment.path("name").asText())) {
                    continue;
                }
                String point = segment.path("point_id").asText();
                if (!point.isBlank() && segment.path("name").isTextual()) {
                    points.add(new Point(safePlanText(segment.path("point_id"), 255),
                            safePlanText(segment.path("name"), 200)));
                }
            }
            days.add(new Day(safePlanText(day.path("city_id"), 128), points));
        }
        Double quality = switch (plan.path("quality").path("status").asText()) {
            case "pass" -> 1.0;
            case "warn" -> 0.5;
            case "fail" -> 0.0;
            default -> null;
        };
        Season season = season(plan);
        Itinerary itinerary = new Itinerary(city, cityName, plan.path("sample_only").asBoolean(true), days);
        return new StoredPost(body, itinerary, season.months(), season.expiresAt(), quality);
    }

    private boolean seemsPublicAttraction(String category, String name) {
        return Set.of("attraction", "park", "museum", "scenic", "景点", "公园", "博物馆").contains(category)
                && !name.toLowerCase(Locale.ROOT).matches(".*(酒店|住宿|宾馆|民宿|客栈|旅馆|hotel|lodging).*");
    }

    private Season season(JsonNode plan) {
        Instant now = Instant.now();
        Map<String, Instant> sources = new HashMap<>();
        for (JsonNode source : plan.path("sources")) {
            if ("knowledge_base".equals(source.path("source_type").asText())
                    && "fresh".equals(source.path("freshness_status").asText())
                    && !source.path("is_estimated").asBoolean(true) && source.path("valid_until").isTextual()
                    && !source.path("source_record_id").asText().isBlank()) {
                try {
                    Instant expiry = OffsetDateTime.parse(source.path("valid_until").asText()).toInstant();
                    if (expiry.isAfter(now)) {
                        sources.put(source.path("source_record_id").asText(), expiry);
                    }
                } catch (java.time.format.DateTimeParseException exception) {
                    // 无法确认时效的条目不参与评分。
                }
            }
        }
        Set<Integer> months = new LinkedHashSet<>();
        long expires = Long.MAX_VALUE;
        for (JsonNode day : plan.path("days")) {
            for (JsonNode segment : day.path("segments")) {
                if (!seemsPublicAttraction(segment.path("category").asText().toLowerCase(Locale.ROOT),
                        segment.path("name").asText())) {
                    continue;
                }
                for (JsonNode fact : segment.path("facts")) {
                    Instant expiry = sources.get(fact.path("source_record_id").asText());
                    if (expiry != null && Set.of("best_season", "best_time").contains(fact.path("fact_type").asText())) {
                        List<Integer> matching = switch (fact.path("value").asText()) {
                            case "春季" -> List.of(3, 4, 5);
                            case "夏季" -> List.of(6, 7, 8);
                            case "秋季" -> List.of(9, 10, 11);
                            case "冬季" -> List.of(12, 1, 2);
                            default -> List.<Integer>of();
                        };
                        if (!matching.isEmpty()) {
                            months.addAll(matching);
                            expires = Math.min(expires, expiry.getEpochSecond());
                        }
                    }
                }
            }
        }
        return months.isEmpty() ? new Season(null, null) : new Season(List.copyOf(months), expires);
    }

    private String safePlanText(JsonNode value, int max) {
        if (!value.isTextual() || value.asText().length() > max) {
            throw invalid("行程缺少安全的公开字段");
        }
        return safety.text(value.asText(), max);
    }

    @Transactional(readOnly = true)
    public List<Post> posts(Filter filter, Sort sort, String query, boolean ranked) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        boolean busyDay = today.getDayOfWeek() == DayOfWeek.SATURDAY
                || today.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.contains(today);
        return repository.posts(filter, sort, query == null || query.isBlank() ? null : query.strip(),
                ranked ? ranking : null, today, busyDay).stream().map(this::withImages).toList();
    }

    @Transactional(readOnly = true)
    public Post post(UUID id) {
        return withImages(require(repository.post(id)));
    }

    private Post withImages(Post post) {
        return new Post(post.id(), post.title(), post.body(), post.theme(), post.itinerary(), post.publishedAt(),
                post.likes(), post.favorites(), post.comments(), post.reuses(), post.rating(), links(post.id()),
                post.version(), post.score(), post.unavailableDimensions(), post.estimatedDimensions());
    }

    private List<String> links(UUID post) {
        return repository.imageIds(post).stream().map(object -> images.link(post, object)).toList();
    }

    @Transactional(readOnly = true)
    public byte[] image(UUID post, UUID object, long expires, String signature) {
        if (!images.authorized(post, object, expires, signature)) {
            throw missing();
        }
        return images.load(require(repository.imageKey(post, object)));
    }

    @Transactional(readOnly = true)
    public List<Comment> comments(UUID post, int limit, int offset) {
        if (!repository.visible(post)) {
            throw missing();
        }
        return repository.comments(post, limit, offset);
    }

    public Id comment(UUID actor, UUID post, String key, CommentRequest request) {
        String text = safety.text(request.body(), COMMENT_LIMIT);
        return write(actor, key, "COMMENT", 10, 10, false, List.of(post, request), () -> {
            PostState state = require(repository.lockPost(post, true));
            if (request.rating() != null && actor.equals(state.owner())) {
                throw forbidden();
            }
            UUID id = UUID.randomUUID();
            repository.write("""
                    INSERT INTO post_comment(id,post_id,author_id,body_text,rating)
                    VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,?)
                    """, id, post, actor, text, request.rating());
            return id;
        });
    }

    public Id react(UUID actor, UUID post, String key, Reaction reaction, boolean active) {
        return write(actor, key, "REACTION", reactionLimit, 1, false, List.of(post, reaction, active), () -> {
            PostState state = require(repository.lockPost(post, true));
            if (active && actor.equals(state.owner())) {
                throw forbidden();
            }
            var current = repository.interaction(post, actor, reaction.name());
            if (current == null) {
                if (!active) {
                    return post;
                }
                UUID id = UUID.randomUUID();
                repository.write("""
                        INSERT INTO interaction(id,post_id,user_id,interaction_type)
                        VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?)
                        """, id, post, actor, reaction.name());
                return id;
            }
            if (active != current.status().equals("ACTIVE")) {
                repository.write("""
                        UPDATE interaction SET status=?,removed_at=IF(?=TRUE,NULL,UTC_TIMESTAMP(6))
                        WHERE id=UUID_TO_BIN(?)
                        """, active ? "ACTIVE" : "REMOVED", active, current.id());
            }
            return current.id();
        });
    }

    public Id report(UUID actor, UUID post, UUID comment, String key, Report request) {
        String detail = request.detail() == null || request.detail().isBlank()
                ? null : safety.text(request.detail(), REPORT_DETAIL_LIMIT);
        Target target = comment == null ? Target.POST : Target.COMMENT;
        UUID targetId = comment == null ? post : comment;
        return write(actor, key, "REPORT", 10, 60, false, List.of(post, target, targetId, request), () -> {
            PostState state = require(repository.lockPost(post, true));
            UUID owner = comment == null ? state.owner() : require(repository.commentAuthor(post, comment));
            if (actor.equals(owner)) {
                throw forbidden();
            }
            UUID existing = repository.first("""
                    SELECT BIN_TO_UUID(id) FROM report
                    WHERE reporter_id=UUID_TO_BIN(?) AND target_type=? AND target_id=UUID_TO_BIN(?)
                    """, (rs, n) -> CommunityRepository.uuid(rs, 1), actor, target.name(), targetId);
            if (existing != null) {
                return existing;
            }
            var latest = repository.reportCase(target, targetId);
            UUID caseId;
            if (latest != null && List.of("OPEN", "REVIEWING").contains(latest.status())) {
                caseId = latest.id();
            } else {
                caseId = UUID.randomUUID();
                repository.write("""
                        INSERT INTO moderation_case(id,target_type,target_id,case_no,opened_by,opened_at)
                        VALUES(UUID_TO_BIN(?),?,UUID_TO_BIN(?),?,UUID_TO_BIN(?),UTC_TIMESTAMP(6))
                        """, caseId, target.name(), targetId, latest == null ? 1 : latest.number() + 1, actor);
            }
            UUID id = UUID.randomUUID();
            repository.write("""
                    INSERT INTO report(id,target_type,target_id,reporter_id,reason_code,detail_text,moderation_case_id)
                    VALUES(UUID_TO_BIN(?),?,UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,UUID_TO_BIN(?))
                    """, id, target.name(), targetId, actor, request.reason().name(), detail, caseId);
            audit.write(actor, "COMMUNITY_REPORT", target.name(), targetId, "SUCCESS", request.reason().name());
            return id;
        });
    }

    public Id reuse(UUID actor, UUID post, String key, Reuse request) {
        return write(actor, key, "REUSE", 10, 60, false, List.of(post, request), () -> {
            PostState state = require(repository.lockPost(post, true));
            if (actor.equals(state.owner())) {
                throw forbidden();
            }
            var existing = repository.interaction(post, actor, "REUSE");
            if (existing != null && existing.trip() != null) {
                Draft draft = require(repository.draft(actor, existing.trip()));
                if (!draft.startDate().equals(request.startDate())) {
                    throw conflict("该帖子已复用，请修改已有草稿");
                }
                return existing.trip();
            }
            if (request.startDate().isBefore(LocalDate.now(ZoneOffset.UTC)) || request.startDate().getYear() > 9998) {
                throw invalid("草稿出发日期不合法");
            }
            Post source = require(repository.post(post));
            UUID trip = UUID.randomUUID();
            UUID version = UUID.randomUUID();
            LocalDate date = request.startDate();
            String plan = repository.json(source.itinerary());
            repository.write("""
                    INSERT INTO trip(id,owner_id,title,start_date,end_date,status)
                    VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,'DRAFT')
                    """, trip, actor, source.itinerary().cityName() + "行程草稿", date,
                    date.plusDays(source.itinerary().days().size() - 1L));
            repository.write("""
                    INSERT INTO trip_version(id,trip_id,version_no,etag,content_hash,version_status,schema_version,created_by)
                    VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),1,?,?,'DRAFT','s23-community-draft-v1',UUID_TO_BIN(?))
                    """, version, trip, digest(version + ":" + plan), digest(plan), actor);
            repository.write("INSERT INTO plan_snapshot(id,trip_version_id,plan_json) VALUES(UUID_TO_BIN(UUID()),UUID_TO_BIN(?),?)",
                    version, plan);
            repository.write("UPDATE trip SET current_version_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)", version, trip);
            if (existing == null) {
                repository.write("""
                        INSERT INTO interaction(id,post_id,user_id,interaction_type,reuse_trip_id)
                        VALUES(UUID_TO_BIN(UUID()),UUID_TO_BIN(?),UUID_TO_BIN(?),'REUSE',UUID_TO_BIN(?))
                        """, post, actor, trip);
            } else {
                repository.write("UPDATE interaction SET reuse_trip_id=UUID_TO_BIN(?),status='ACTIVE',removed_at=NULL"
                        + " WHERE id=UUID_TO_BIN(?)", trip, existing.id());
            }
            return trip;
        });
    }

    @Transactional(readOnly = true)
    public Draft draft(UUID actor, UUID trip) {
        return require(repository.draft(actor, trip));
    }

    @Transactional(readOnly = true)
    public List<ReviewItem> queue(int limit, int offset) {
        return repository.reviewItems(limit, offset).stream().map(item -> new ReviewItem(item.postId(), item.version(),
                item.status(), item.caseId(), item.targetId(), item.targetType(), item.title(), item.body(),
                links(item.postId()), item.targetBody(), item.reportReason(), item.reportDetail())).toList();
    }

    public Id review(UUID actor, UUID post, String key, long version, ReviewAction action, boolean admin) {
        return write(actor, key, "REVIEW", 60, 1, admin, List.of(post, version, action, admin), () -> {
            PostState state = require(repository.lockPost(post, false));
            if (!admin && !actor.equals(state.owner())) {
                throw missing();
            }
            version(state, version);
            if (!List.of("PENDING", "PUBLISHED").contains(state.status())) {
                throw conflict("帖子已退出公开状态");
            }
            switch (action) {
                case WITHDRAW -> withdraw(post);
                case APPROVE -> repository.write("""
                        UPDATE post SET moderation_status='PUBLISHED',state_version=state_version+1,
                            updated_at=UTC_TIMESTAMP(6) WHERE id=UUID_TO_BIN(?)
                        """, post);
                case PIN, UNPIN -> repository.write("""
                        UPDATE post SET featured=?,state_version=state_version+1,updated_at=UTC_TIMESTAMP(6)
                        WHERE id=UUID_TO_BIN(?)
                        """, action == ReviewAction.PIN, post);
            }
            audit.write(actor, "COMMUNITY_" + action.name(), "POST", post, "SUCCESS", null);
            return post;
        });
    }

    public Id resolve(UUID actor, UUID post, UUID caseId, String key, long version, CaseAction decision) {
        return write(actor, key, "RESOLVE", 60, 1, true, List.of(post, caseId, version, decision), () -> {
            PostState state = require(repository.lockPost(post, false));
            version(state, version);
            CaseTarget target = require(repository.first("""
                    SELECT m.target_type,BIN_TO_UUID(m.target_id),m.status FROM moderation_case m
                    LEFT JOIN post_comment c ON m.target_type='COMMENT' AND c.id=m.target_id
                    WHERE m.id=UUID_TO_BIN(?) AND IF(m.target_type='POST',m.target_id,c.post_id)=UUID_TO_BIN(?)
                    """, (rs, n) -> new CaseTarget(Target.valueOf(rs.getString(1)),
                    CommunityRepository.uuid(rs, 2), rs.getString(3)), caseId, post));
            if (!List.of("OPEN", "REVIEWING").contains(target.status())) {
                throw conflict("该举报已处理");
            }
            boolean remove = decision == CaseAction.REMOVE;
            if (remove && target.type() == Target.POST) {
                withdraw(post);
            } else {
                if (remove) {
                    repository.write("""
                            UPDATE post_comment SET moderation_status='HIDDEN',updated_at=UTC_TIMESTAMP(6)
                            WHERE id=UUID_TO_BIN(?) AND post_id=UUID_TO_BIN(?)
                            """, target.id(), post);
                }
                repository.write("UPDATE post SET state_version=state_version+1,updated_at=UTC_TIMESTAMP(6)"
                        + " WHERE id=UUID_TO_BIN(?)", post);
            }
            repository.write("""
                    UPDATE moderation_case SET status=?,assigned_to=UUID_TO_BIN(?),resolution_code=?,
                        resolved_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE id=UUID_TO_BIN(?)
                    """, remove ? "RESOLVED" : "REJECTED", actor, decision.name(), caseId);
            repository.write("UPDATE report SET status=? WHERE moderation_case_id=UUID_TO_BIN(?) AND status='OPEN'",
                    remove ? "ACCEPTED" : "REJECTED", caseId);
            audit.write(actor, "COMMUNITY_RESOLVE", target.type().name(), target.id(), "SUCCESS", decision.name());
            return caseId;
        });
    }

    private void withdraw(UUID post) {
        repository.write("""
                UPDATE post SET visibility='PRIVATE',moderation_status='WITHDRAWN',featured=FALSE,
                    withdrawn_at=COALESCE(withdrawn_at,UTC_TIMESTAMP(6)),updated_at=UTC_TIMESTAMP(6),
                    state_version=state_version+1 WHERE id=UUID_TO_BIN(?)
                """, post);
        repository.write("UPDATE ranking_entry SET visible=FALSE WHERE post_id=UUID_TO_BIN(?) AND visible=TRUE", post);
    }

    private void version(PostState state, long expected) {
        if (expected < 1) {
            throw invalid("帖子版本必须为正整数");
        }
        if (expected != state.version()) {
            throw conflict("帖子状态已更新，请重新读取");
        }
    }

    private Id write(UUID actor, String key, String operation, int limit, int minutes, boolean admin,
            List<?> input, Supplier<UUID> action) {
        if (!key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw invalid("幂等键不合法");
        }
        if (!repository.lockActor(actor, admin)) {
            throw forbidden();
        }
        byte[] hash = digest(repository.json(input));
        var previous = repository.replay(actor, key);
        if (previous != null) {
            if (!operation.equals(previous.operation()) || !MessageDigest.isEqual(hash, previous.hash())) {
                throw conflict("幂等键已用于另一请求");
            }
            return new Id(previous.result());
        }
        if (repository.writesSince(actor, operation, minutes) >= limit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "操作过于频繁");
        }
        UUID id = action.get();
        repository.write("""
                INSERT INTO community_write(actor_id,request_key,operation_type,request_hash,result_id)
                VALUES(UUID_TO_BIN(?),?,?,?,UUID_TO_BIN(?))
                """, actor, key, operation, hash, id);
        return new Id(id);
    }

    private static <T> T require(T value) {
        if (value == null) {
            throw missing();
        }
        return value;
    }

    static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "CONTEXT_NOT_FOUND", "内容不存在或不可见");
    }

    static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前账号无权操作");
    }

    private static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", message);
    }

    private record CaseTarget(Target type, UUID id, String status) {}
    private record Season(List<Integer> months, Long expiresAt) {}
}
