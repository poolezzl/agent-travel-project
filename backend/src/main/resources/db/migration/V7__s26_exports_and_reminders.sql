ALTER TABLE export_job
 ADD COLUMN request_key_hash BINARY(32),
 ADD COLUMN attempt_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
 ADD COLUMN available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 ADD UNIQUE KEY uk_export_request(user_id,request_key_hash),
 ADD KEY ix_export_work(status,available_at);

CREATE TABLE trip_reminder_subscription (
 trip_version_id BINARY(16) NOT NULL,
 user_id BINARY(16) NOT NULL,
 cancelled_at DATETIME(6),
 next_check_at DATETIME(6),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(trip_version_id),
 KEY ix_reminder_check(next_check_at),
 CONSTRAINT fk_subscription_version FOREIGN KEY(trip_version_id) REFERENCES trip_version(id),
 CONSTRAINT fk_subscription_owner FOREIGN KEY(user_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE reminder_candidate
 ADD COLUMN subscription_version_id BINARY(16),
 ADD KEY ix_reminder_subscription(subscription_version_id,status),
 ADD CONSTRAINT fk_reminder_subscription FOREIGN KEY(subscription_version_id)
 REFERENCES trip_reminder_subscription(trip_version_id);
