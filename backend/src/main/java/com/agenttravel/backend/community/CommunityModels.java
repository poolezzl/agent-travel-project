package com.agenttravel.backend.community;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

final class CommunityModels {
    static final int TITLE_LIMIT = 200;
    static final int POST_BODY_LIMIT = 4000;
    static final int COMMENT_LIMIT = 1000;
    static final int REPORT_DETAIL_LIMIT = 1000;

    enum Theme { FAMILY, HIKING, PHOTOGRAPHY, FOOD, OTHER }
    enum Duration {
        WEEKEND(2, 2), SHORT(3, 4), WEEK(7, 7);

        final int min;
        final int max;

        Duration(int min, int max) {
            this.min = min;
            this.max = max;
        }
    }
    enum Sort { LATEST, HOT }
    enum Reaction { LIKE, FAVORITE }
    enum Reason { SPAM, ABUSE, PRIVACY, COPYRIGHT, OTHER }
    enum LicenseKind { OWN, CC0, CC_BY, AUTHORIZED }
    enum ReviewAction { APPROVE, WITHDRAW, PIN, UNPIN }
    enum CaseAction { DISMISS, REMOVE }
    enum Target { POST, COMMENT }

    @JsonNaming(SnakeCaseStrategy.class)
    record Publish(@NotNull UUID sourceVersionId, @NotBlank @Size(max = TITLE_LIMIT) String title,
            @NotBlank @Size(max = POST_BODY_LIMIT) String body, @NotNull Theme theme,
            @Size(max = 3) List<@NotNull @Valid License> imageLicenses) {}
    @JsonNaming(SnakeCaseStrategy.class)
    record License(@NotNull LicenseKind kind, @Size(max = 2048) String sourceUrl) {}
    record CommentRequest(@NotBlank @Size(max = COMMENT_LIMIT) String body, @Min(1) @Max(5) Integer rating) {}
    record Report(@NotNull Reason reason, @Size(max = REPORT_DETAIL_LIMIT) String detail) {}
    @JsonNaming(SnakeCaseStrategy.class)
    record Reuse(@NotNull LocalDate startDate) {}
    record Review(@NotNull ReviewAction action) {}
    record Resolution(@NotNull CaseAction action) {}
    record Id(UUID id) {}

    record Point(String id, String name) {}
    record Day(String city, List<Point> points) {}
    @JsonNaming(SnakeCaseStrategy.class)
    record Itinerary(String city, String cityName, boolean sampleOnly, List<Day> days) {}
    @JsonNaming(SnakeCaseStrategy.class)
    record Draft(UUID id, UUID versionId, LocalDate startDate, LocalDate endDate, Itinerary itinerary) {}
    @JsonNaming(SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Comment(UUID id, String body, Integer rating, Instant createdAt) {}
    @JsonNaming(SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Post(UUID id, String title, String body, Theme theme, Itinerary itinerary, Instant publishedAt,
            long likes, long favorites, long comments, long reuses, Double rating, List<String> images, long version,
            Double score, List<String> unavailableDimensions, List<String> estimatedDimensions) {}
    @JsonNaming(SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReviewItem(UUID postId, long version, String status, UUID caseId, UUID targetId, String targetType,
            String title, String body, List<String> images, String targetBody, String reportReason, String reportDetail) {}
    record Filter(String city, Theme theme, Integer days, Duration duration, int limit, int offset) {}
}
