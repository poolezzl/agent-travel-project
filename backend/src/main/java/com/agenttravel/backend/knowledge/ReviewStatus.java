package com.agenttravel.backend.knowledge;

import com.agenttravel.backend.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Review lifecycle shared by knowledge entries and reservation rule versions.
 *
 * <p>Published rows are immutable: a correction must be created as a new version and moved through
 * DRAFT -> REVIEW -> PUBLISHED again. Superseded/rolled back versions stay in the table so that
 * historical trip versions keep a resolvable reference.
 */
public enum ReviewStatus {
    DRAFT,
    REVIEW,
    PUBLISHED,
    DISABLED,
    ROLLED_BACK;

    public boolean canTransitionTo(ReviewStatus next) {
        if (next == null || next == this) {
            return false;
        }
        return switch (this) {
            case DRAFT -> next == REVIEW;
            case REVIEW -> next == PUBLISHED || next == DRAFT;
            case PUBLISHED -> next == DISABLED || next == ROLLED_BACK;
            case DISABLED -> next == DRAFT;
            case ROLLED_BACK -> next == DRAFT;
        };
    }

    public static void requireTransition(ReviewStatus current, ReviewStatus next) {
        if (!current.canTransitionTo(next)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                    "审核状态不允许从 " + current + " 流转到 " + next);
        }
    }

    public boolean retrievable() {
        return this == PUBLISHED;
    }
}
