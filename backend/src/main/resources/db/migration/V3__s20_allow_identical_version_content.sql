-- A rollback creates a new version whose content hash can equal the target version.
ALTER TABLE trip_version
    DROP INDEX uk_trip_version_hash,
    ADD INDEX ix_trip_version_hash (trip_id, content_hash);

ALTER TABLE change_set ADD COLUMN scope_json JSON NULL;
