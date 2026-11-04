-- S15: knowledge base, POI conflict queue, import batches and reservation rule versions.
-- Additive only: V1 is immutable. All datetimes are UTC DATETIME(6), storage is InnoDB/utf8mb4.

ALTER TABLE knowledge_entry
  ADD COLUMN fact_type VARCHAR(32) NULL AFTER title,
  ADD COLUMN fact_value_json JSON NULL AFTER fact_type,
  ADD COLUMN published_at DATETIME(6) NULL AFTER last_verified_at,
  ADD COLUMN reviewed_at DATETIME(6) NULL AFTER published_at,
  ADD COLUMN reviewed_by BINARY(16) NULL AFTER reviewed_at,
  ADD COLUMN disabled_at DATETIME(6) NULL AFTER reviewed_by,
  ADD KEY ix_knowledge_fact(city_id, status, fact_type);

ALTER TABLE knowledge_entry DROP CHECK ck_knowledge_status;
ALTER TABLE knowledge_entry
  ADD CONSTRAINT ck_knowledge_status
    CHECK(status IN('DRAFT','REVIEW','PUBLISHED','DISABLED','ROLLED_BACK')),
  ADD CONSTRAINT ck_knowledge_fact_type
    CHECK(fact_type IS NULL OR fact_type IN(
      'opening_hours','highlights','avoid_tips','suggested_duration',
      'accessibility','best_time','popularity')),
  ADD CONSTRAINT fk_knowledge_reviewer
    FOREIGN KEY(reviewed_by) REFERENCES user_account(id);

ALTER TABLE reservation_rule
  ADD COLUMN reservation_required BOOLEAN NOT NULL DEFAULT FALSE AFTER channel,
  ADD COLUMN release_time TIME NULL AFTER reservation_required,
  ADD COLUMN advance_days INT NULL AFTER release_time,
  ADD COLUMN free_condition VARCHAR(1000) NULL AFTER amount_minor,
  ADD COLUMN holiday_exception VARCHAR(1000) NULL AFTER free_condition,
  ADD COLUMN stale_after_days INT NOT NULL DEFAULT 30 AFTER last_verified_at,
  ADD COLUMN published_at DATETIME(6) NULL AFTER status,
  ADD COLUMN reviewed_at DATETIME(6) NULL AFTER published_at,
  ADD COLUMN reviewed_by BINARY(16) NULL AFTER reviewed_at,
  ADD KEY ix_rule_release(poi_id, status, release_time);

ALTER TABLE reservation_rule DROP CHECK ck_rule_status;
ALTER TABLE reservation_rule
  ADD CONSTRAINT ck_rule_status
    CHECK(status IN('DRAFT','REVIEW','PUBLISHED','DISABLED','ROLLED_BACK')),
  ADD CONSTRAINT ck_rule_advance CHECK(advance_days IS NULL OR advance_days>=0),
  ADD CONSTRAINT ck_rule_stale CHECK(stale_after_days>0),
  ADD CONSTRAINT fk_rule_reviewer
    FOREIGN KEY(reviewed_by) REFERENCES user_account(id);

ALTER TABLE poi
  ADD COLUMN district VARCHAR(128) NULL AFTER category,
  ADD COLUMN merge_group_id BINARY(16) NULL AFTER current_snapshot_id,
  ADD KEY ix_poi_merge_group(merge_group_id);

CREATE TABLE poi_merge_candidate (
 id BINARY(16) NOT NULL, city_id BINARY(16) NOT NULL, candidate_poi_id BINARY(16) NOT NULL,
 existing_poi_id BINARY(16) NOT NULL, conflict_type VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'OPEN', details_json JSON, resolved_by BINARY(16),
 resolved_at DATETIME(6), created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_merge_candidate(candidate_poi_id, existing_poi_id, conflict_type),
 KEY ix_merge_candidate_open(city_id, status, created_at),
 CONSTRAINT ck_merge_conflict_type CHECK(conflict_type IN('NAME','COORDINATE','ALIAS','SPLIT')),
 CONSTRAINT ck_merge_status CHECK(status IN('OPEN','MERGED','REJECTED')),
 CONSTRAINT fk_merge_city FOREIGN KEY(city_id) REFERENCES city(id),
 CONSTRAINT fk_merge_candidate_poi FOREIGN KEY(candidate_poi_id) REFERENCES poi(id),
 CONSTRAINT fk_merge_existing_poi FOREIGN KEY(existing_poi_id) REFERENCES poi(id),
 CONSTRAINT fk_merge_resolver FOREIGN KEY(resolved_by) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE import_batch (
 id BINARY(16) NOT NULL, batch_key VARCHAR(128) NOT NULL, operator_id BINARY(16) NOT NULL,
 import_type VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'VALIDATED',
 row_count INT NOT NULL DEFAULT 0, error_count INT NOT NULL DEFAULT 0,
 errors_json JSON, result_json JSON, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 completed_at DATETIME(6), PRIMARY KEY(id), UNIQUE KEY uk_import_batch_key(batch_key),
 KEY ix_import_batch_operator(operator_id, created_at),
 CONSTRAINT ck_import_batch_type CHECK(import_type IN('ATTRACTION_KNOWLEDGE','RESERVATION_RULE','POI')),
 CONSTRAINT ck_import_batch_status CHECK(status IN('VALIDATED','COMMITTED','FAILED')),
 CONSTRAINT ck_import_batch_rows CHECK(row_count>=0 AND error_count>=0),
 CONSTRAINT ck_import_batch_errors CHECK(error_count=0 OR errors_json IS NOT NULL),
 CONSTRAINT fk_import_batch_operator FOREIGN KEY(operator_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
