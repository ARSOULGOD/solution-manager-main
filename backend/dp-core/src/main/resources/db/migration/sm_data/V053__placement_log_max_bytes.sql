-- The on-disk cap, in bytes, of one placement's collected log on its host-local agent (plan 016, spec 5).
-- NULL means no cap was ever set (the agent keeps its default); 0 turns collection off. Only an agent placement uses it.
ALTER TABLE sm_data.placement
    ADD COLUMN IF NOT EXISTS log_max_bytes bigint;

ALTER TABLE sm_data.placement
    ADD CONSTRAINT placement_log_max_bytes_nonnegative CHECK (log_max_bytes IS NULL OR log_max_bytes >= 0);
