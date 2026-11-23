ALTER TABLE post
    ADD COLUMN community_theme VARCHAR(16),
    ADD COLUMN day_count SMALLINT UNSIGNED,
    ADD COLUMN featured BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN state_version BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_post_theme CHECK(community_theme IN ('FAMILY','HIKING','PHOTOGRAPHY','FOOD','OTHER')),
    ADD KEY ix_post_category(community_theme,day_count,visibility,moderation_status);

ALTER TABLE post_comment
    ADD COLUMN rating TINYINT UNSIGNED,
    ADD CONSTRAINT ck_post_comment_rating CHECK(rating BETWEEN 1 AND 5),
    ADD KEY ix_post_comment_rating(post_id,author_id,created_at);

ALTER TABLE interaction
    ADD COLUMN reuse_trip_id BINARY(16),
    ADD CONSTRAINT fk_interaction_reuse_trip FOREIGN KEY(reuse_trip_id) REFERENCES trip(id);

ALTER TABLE object_metadata
    DROP INDEX uk_object_sha256,
    ADD KEY ix_object_sha256(sha256);

CREATE TABLE post_image (
    post_id BINARY(16) NOT NULL,
    object_id BINARY(16) NOT NULL,
    position_no TINYINT UNSIGNED NOT NULL,
    PRIMARY KEY (post_id,position_no),
    UNIQUE KEY uk_post_image_object(object_id),
    CONSTRAINT fk_post_image_post FOREIGN KEY(post_id) REFERENCES post(id),
    CONSTRAINT fk_post_image_object FOREIGN KEY(object_id) REFERENCES object_metadata(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE community_write (
    actor_id BINARY(16) NOT NULL,
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_type VARCHAR(24) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    result_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (actor_id,request_key),
    KEY ix_community_write_rate(actor_id,operation_type,created_at),
    CONSTRAINT fk_community_write_actor FOREIGN KEY(actor_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
