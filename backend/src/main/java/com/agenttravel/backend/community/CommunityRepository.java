package com.agenttravel.backend.community;

import static com.agenttravel.backend.community.CommunityModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
class CommunityRepository {
    // 全部公开读取共用同一个条件，下架无需等待派生数据失效。
    static final String VISIBLE = """
            p.visibility='PUBLIC' AND p.moderation_status IN ('PENDING','PUBLISHED')
            AND p.published_at IS NOT NULL AND p.withdrawn_at IS NULL AND p.deleted_at IS NULL
            AND p.community_theme IS NOT NULL
            """;
    private static final String VISIBLE_COMMENT = "c.moderation_status='VISIBLE' AND c.deleted_at IS NULL";
    private static final String HEAT = "(likes+2*favorites+3*reuses+commenters)";
    private static final String POST_FIELDS = """
            BIN_TO_UUID(p.id) AS post_id,p.title,p.community_theme,p.sanitized_content_json,p.published_at,
            p.featured,p.state_version,
            (SELECT COUNT(*) FROM interaction i WHERE i.post_id=p.id AND i.status='ACTIVE'
                AND i.interaction_type='LIKE') AS likes,
            (SELECT COUNT(*) FROM interaction i WHERE i.post_id=p.id AND i.status='ACTIVE'
                AND i.interaction_type='FAVORITE') AS favorites,
            (SELECT COUNT(*) FROM interaction i WHERE i.post_id=p.id AND i.status='ACTIVE'
                AND i.interaction_type='REUSE') AS reuses,
            (SELECT COUNT(*) FROM post_comment c WHERE c.post_id=p.id AND %1$s) AS comments,
            (SELECT COUNT(DISTINCT c.author_id) FROM post_comment c WHERE c.post_id=p.id AND %1$s
                AND c.author_id<>p.owner_id) AS commenters,
            (SELECT AVG(rating) FROM post_comment c WHERE c.post_id=p.id AND %1$s AND c.rating IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM post_comment newer WHERE newer.post_id=c.post_id
                    AND newer.author_id=c.author_id AND newer.moderation_status='VISIBLE' AND newer.deleted_at IS NULL
                    AND newer.rating IS NOT NULL AND (newer.created_at,newer.id)>(c.created_at,c.id))) AS rating
            """.formatted(VISIBLE_COMMENT);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    CommunityRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    <T> T first(String sql, RowMapper<T> row, Object... args) {
        List<T> rows = jdbc.query(sql, row, sqlArgs(args));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    int write(String sql, Object... args) {
        return jdbc.update(sql, sqlArgs(args));
    }

    private Object[] sqlArgs(Object... args) {
        return Arrays.stream(args).map(value -> value instanceof UUID id ? id.toString() : value).toArray();
    }

    boolean lockActor(UUID actor, boolean admin) {
        String role = first("SELECT role FROM user_account WHERE id=UUID_TO_BIN(?) AND status='ACTIVE'"
                + " AND deleted_at IS NULL FOR UPDATE", (rs, n) -> rs.getString(1), actor);
        return role != null && (!admin || role.equals("ADMIN"));
    }

    Replay replay(UUID actor, String key) {
        return first("""
                SELECT operation_type,request_hash,BIN_TO_UUID(result_id) FROM community_write
                WHERE actor_id=UUID_TO_BIN(?) AND request_key=?
                """, (rs, n) -> new Replay(rs.getString(1), rs.getBytes(2), uuid(rs, 3)), actor, key);
    }

    int writesSince(UUID actor, String operation, int minutes) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM community_write WHERE actor_id=UUID_TO_BIN(?) AND operation_type=?
                  AND created_at>=TIMESTAMPADD(MINUTE,-?,UTC_TIMESTAMP(6))
                """, Integer.class, actor.toString(), operation, minutes);
    }

    String sourcePlan(UUID actor, UUID version) {
        return first("""
                SELECT s.plan_json FROM trip_version v JOIN trip t ON t.id=v.trip_id
                  JOIN plan_snapshot s ON s.trip_version_id=v.id
                WHERE v.id=UUID_TO_BIN(?) AND t.owner_id=UUID_TO_BIN(?)
                  AND t.deleted_at IS NULL AND t.status IN ('DRAFT','ACTIVE')
                  AND v.version_status IN ('PUBLISHED','SUPERSEDED')
                FOR SHARE
                """, (rs, n) -> rs.getString(1), version, actor);
    }

    PostState lockPost(UUID id, boolean onlyVisible) {
        return first("""
                SELECT BIN_TO_UUID(p.owner_id),p.moderation_status,p.state_version
                FROM post p WHERE p.id=UUID_TO_BIN(?) AND p.deleted_at IS NULL AND p.community_theme IS NOT NULL
                """ + (onlyVisible ? " AND " + VISIBLE : "") + " FOR UPDATE",
                (rs, n) -> new PostState(uuid(rs, 1), rs.getString(2), rs.getLong(3)), id);
    }

    Post post(UUID id) {
        return first("SELECT " + POST_FIELDS + " FROM post p WHERE p.id=UUID_TO_BIN(?) AND " + VISIBLE,
                (rs, n) -> post(rs, false), id);
    }

    boolean visible(UUID id) {
        return first("SELECT 1 FROM post p WHERE p.id=UUID_TO_BIN(?) AND " + VISIBLE, (rs, n) -> true, id) != null;
    }

    List<Post> posts(Filter filter, Sort sort, String query, Ranking ranking, LocalDate today, boolean busyDay) {
        StringBuilder sql = new StringBuilder("WITH candidates AS (SELECT " + POST_FIELDS
                + " FROM post p WHERE " + VISIBLE);
        List<Object> args = new ArrayList<>();
        if (filter.city() != null) {
            sql.append(" AND (JSON_UNQUOTE(JSON_EXTRACT(p.sanitized_content_json,'$.itinerary.city'))=?")
                    .append(" OR JSON_SEARCH(p.sanitized_content_json,'one',?, '!', '$.itinerary.days[*].city') IS NOT NULL)");
            args.add(filter.city());
            args.add(escapeLike(filter.city()));
        }
        if (filter.theme() != null) {
            sql.append(" AND p.community_theme=?");
            args.add(filter.theme().name());
        }
        if (filter.days() != null) {
            sql.append(" AND p.day_count=?");
            args.add(filter.days());
        }
        if (filter.duration() != null) {
            sql.append(" AND p.day_count BETWEEN ? AND ?");
            args.add(filter.duration().min);
            args.add(filter.duration().max);
        }
        if (query != null) {
            sql.append("""
                     AND (p.title LIKE ? ESCAPE '!' OR
                     JSON_UNQUOTE(JSON_EXTRACT(p.sanitized_content_json,'$.body')) LIKE ? ESCAPE '!')
                    """);
            String term = "%" + escapeLike(query) + "%";
            args.add(term);
            args.add(term);
        }
        sql.append(") ");
        if (ranking != null) {
            sql.append("""
                    , scored AS (
                      SELECT candidates.*,
                        ((? * LEAST(1, %1$s/20.0))
                          + (? * COALESCE(rating/5.0,0))
                          + (? * IF(JSON_VALUE(sanitized_content_json,'$.season_expires_at' RETURNING UNSIGNED)
                            > UNIX_TIMESTAMP(), COALESCE(JSON_CONTAINS(
                            JSON_EXTRACT(sanitized_content_json,'$.season_months'),
                            CAST(? AS JSON)),0),0))
                          + (? * COALESCE(JSON_EXTRACT(sanitized_content_json,'$.quality_score'),0))
                          + (? * (1 - LEAST(1, %1$s/40.0 + ?))))
                        / (1 + GREATEST(0,TIMESTAMPDIFF(HOUR,published_at,UTC_TIMESTAMP(6)))/?) AS score
                      FROM candidates
                    )
                    SELECT * FROM scored WHERE featured=TRUE OR score>=?
                    ORDER BY featured DESC, score DESC, published_at DESC, post_id DESC
                    """.formatted(HEAT));
            args.addAll(List.of(ranking.heat(), ranking.rating(), ranking.season(), String.valueOf(today.getMonthValue()),
                    ranking.quality(), ranking.crowd(), busyDay ? 0.5 : 0.0, ranking.decayHours(), ranking.minimum()));
        } else {
            sql.append("SELECT * FROM candidates ORDER BY ");
            if (sort == Sort.HOT) {
                sql.append(HEAT).append(" DESC, ");
            }
            sql.append("published_at DESC, post_id DESC");
        }
        sql.append(" LIMIT ? OFFSET ?");
        args.add(filter.limit());
        args.add(filter.offset());
        return jdbc.query(sql.toString(), (rs, n) -> post(rs, ranking != null), args.toArray());
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private Post post(ResultSet rs, boolean ranked) throws SQLException {
        StoredPost content = read(rs.getString("sanitized_content_json"), StoredPost.class);
        List<String> unavailable = null;
        if (ranked) {
            unavailable = new ArrayList<>();
            if (rs.getObject("rating") == null) unavailable.add("RATING");
            if (content.seasonExpiresAt() == null || content.seasonExpiresAt() <= Instant.now().getEpochSecond()) {
                unavailable.add("SEASON");
            }
            if (content.qualityScore() == null) unavailable.add("QUALITY");
        }
        return new Post(UUID.fromString(rs.getString("post_id")), rs.getString("title"), content.body(),
                Theme.valueOf(rs.getString("community_theme")), content.itinerary(), rs.getTimestamp("published_at").toInstant(),
                rs.getLong("likes"), rs.getLong("favorites"), rs.getLong("comments"), rs.getLong("reuses"),
                rs.getObject("rating") == null ? null : rs.getDouble("rating"), List.of(),
                rs.getLong("state_version"), ranked ? rs.getDouble("score") : null, unavailable,
                ranked ? List.of("CROWD") : null);
    }

    List<Comment> comments(UUID post, int limit, int offset) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(c.id),c.body_text,c.rating,c.created_at
                FROM post_comment c JOIN post p ON p.id=c.post_id WHERE p.id=UUID_TO_BIN(?) AND
                """ + VISIBLE + " AND " + VISIBLE_COMMENT + " ORDER BY c.created_at,c.id LIMIT ? OFFSET ?",
                (rs, n) -> new Comment(uuid(rs, 1), rs.getString(2), rs.getObject(3, Integer.class),
                        rs.getTimestamp(4).toInstant()), post.toString(), limit, offset);
    }

    UUID commentAuthor(UUID post, UUID comment) {
        return first("""
                SELECT BIN_TO_UUID(c.author_id) FROM post_comment c
                WHERE c.post_id=UUID_TO_BIN(?) AND c.id=UUID_TO_BIN(?) AND
                """ + VISIBLE_COMMENT, (rs, n) -> uuid(rs, 1), post, comment);
    }

    List<UUID> imageIds(UUID post) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(o.id) FROM post_image i JOIN object_metadata o ON i.object_id=o.id
                JOIN post p ON i.post_id=p.id AND o.owner_id=p.owner_id
                WHERE p.id=UUID_TO_BIN(?) AND o.status='AVAILABLE' AND
                """ + VISIBLE + " ORDER BY i.position_no", (rs, n) -> uuid(rs, 1), post.toString());
    }

    String imageKey(UUID post, UUID object) {
        return first("""
                SELECT o.object_key FROM post_image i JOIN object_metadata o ON i.object_id=o.id
                JOIN post p ON i.post_id=p.id AND o.owner_id=p.owner_id
                WHERE p.id=UUID_TO_BIN(?) AND o.id=UUID_TO_BIN(?) AND o.status='AVAILABLE' AND
                """ + VISIBLE, (rs, n) -> rs.getString(1), post, object);
    }

    Interaction interaction(UUID post, UUID actor, String type) {
        return first("""
                SELECT BIN_TO_UUID(id),status,BIN_TO_UUID(reuse_trip_id) FROM interaction
                WHERE post_id=UUID_TO_BIN(?) AND user_id=UUID_TO_BIN(?) AND interaction_type=?
                """, (rs, n) -> new Interaction(uuid(rs, 1), rs.getString(2), uuid(rs, 3)), post, actor, type);
    }

    ReportCase reportCase(Target target, UUID id) {
        return first("""
                SELECT BIN_TO_UUID(id),case_no,status FROM moderation_case
                WHERE target_type=? AND target_id=UUID_TO_BIN(?) ORDER BY case_no DESC LIMIT 1
                """, (rs, n) -> new ReportCase(uuid(rs, 1), rs.getInt(2), rs.getString(3)), target.name(), id);
    }

    List<ReviewItem> reviewItems(int limit, int offset) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(p.id),p.state_version,p.moderation_status,NULL,NULL,NULL,p.title,
                    JSON_UNQUOTE(JSON_EXTRACT(p.sanitized_content_json,'$.body')),NULL,NULL,NULL,
                    p.published_at AS queued_at
                FROM post p WHERE p.moderation_status='PENDING' AND
                """ + VISIBLE + """
                 UNION ALL
                SELECT BIN_TO_UUID(p.id),p.state_version,m.status,BIN_TO_UUID(m.id),BIN_TO_UUID(m.target_id),
                    m.target_type,p.title,JSON_UNQUOTE(JSON_EXTRACT(p.sanitized_content_json,'$.body')),
                    c.body_text,
                    (SELECT r.reason_code FROM report r WHERE r.moderation_case_id=m.id
                        ORDER BY r.created_at,r.id LIMIT 1),
                    (SELECT r.detail_text FROM report r WHERE r.moderation_case_id=m.id
                        ORDER BY r.created_at,r.id LIMIT 1),m.opened_at
                FROM moderation_case m
                LEFT JOIN post_comment c ON m.target_type='COMMENT' AND c.id=m.target_id
                JOIN post p ON p.id=IF(m.target_type='POST',m.target_id,c.post_id)
                WHERE m.status IN ('OPEN','REVIEWING') AND p.community_theme IS NOT NULL
                ORDER BY queued_at, 1, 4 LIMIT ? OFFSET ?
                """, (rs, n) -> new ReviewItem(uuid(rs, 1), rs.getLong(2), rs.getString(3), uuid(rs, 4), uuid(rs, 5),
                rs.getString(6), rs.getString(7), rs.getString(8), List.of(), rs.getString(9), rs.getString(10),
                rs.getString(11)), limit, offset);
    }

    Draft draft(UUID actor, UUID trip) {
        return first("""
                SELECT BIN_TO_UUID(v.id),t.start_date,t.end_date,s.plan_json
                FROM trip t JOIN trip_version v ON v.id=t.current_version_id AND v.trip_id=t.id
                  JOIN plan_snapshot s ON s.trip_version_id=v.id
                WHERE t.id=UUID_TO_BIN(?) AND t.owner_id=UUID_TO_BIN(?) AND t.status='DRAFT' AND t.deleted_at IS NULL
                  AND v.version_status='DRAFT' AND v.schema_version='s23-community-draft-v1'
                """, (rs, n) -> new Draft(trip, uuid(rs, 1), rs.getObject(2, LocalDate.class),
                rs.getObject(3, LocalDate.class), read(rs.getString(4), Itinerary.class)), trip, actor);
    }

    String json(Object data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("社区内容无法写入", exception);
        }
    }

    <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("社区内容无法读取", exception);
        }
    }

    static UUID uuid(ResultSet rs, int column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record StoredPost(String body, Itinerary itinerary,
            @com.fasterxml.jackson.annotation.JsonProperty("season_months") List<Integer> seasonMonths,
            @com.fasterxml.jackson.annotation.JsonProperty("season_expires_at") Long seasonExpiresAt,
            @com.fasterxml.jackson.annotation.JsonProperty("quality_score") Double qualityScore) {}
    record PostState(UUID owner, String status, long version) {}
    record Replay(String operation, byte[] hash, UUID result) {}
    record Interaction(UUID id, String status, UUID trip) {}
    record ReportCase(UUID id, int number, String status) {}
    record Ranking(double heat, double rating, double season, double quality, double crowd,
            int decayHours, double minimum) {}
}
