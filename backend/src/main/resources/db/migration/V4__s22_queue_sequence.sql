-- S22: durable queue ordering/dedupe anchor.
-- The outbox publishes at least once; Redis Streams may redeliver or reorder.
-- Every queue message carries the task_event sequence_no that produced it, and the
-- consumer records the highest applied sequence per task so duplicate or stale
-- messages are acknowledged without re-running a step.
ALTER TABLE planning_task
  ADD COLUMN last_queue_sequence BIGINT NOT NULL DEFAULT 0 AFTER state_version;