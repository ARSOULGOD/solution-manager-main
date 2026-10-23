-- Plan 016 task 8: the on-disk cap, in bytes, of one placement's collected log on its host-local agent (spec 5).
-- A SIDE table, not a column on sm_data.placement: the registry is immutable (OR-29, trigger
-- placement_immutable_except_retirement rejects every UPDATE but retirement) and the cap must be changeable.
-- A missing row means "never set" (the agent keeps its default); 0 turns collection off. Only an agent placement uses it.
-- Keyed and tenant-bound exactly like the placement it belongs to; placements are never deleted, so the FK only guards orphans.
CREATE TABLE sm_data.placement_log_retention (
    tenant_id uuid NOT NULL,
    placement_id uuid NOT NULL,
    log_max_bytes bigint NOT NULL CONSTRAINT placement_log_retention_nonnegative CHECK (log_max_bytes >= 0),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, placement_id),
    CONSTRAINT placement_log_retention_placement_fkey FOREIGN KEY (tenant_id, placement_id)
        REFERENCES sm_data.placement (tenant_id, placement_id));

GRANT SELECT, INSERT, UPDATE ON sm_data.placement_log_retention TO sm_dp_runtime;

-- ROLLBACK: nothing does so automatically, and a published migration is never edited (OR-22). By hand, with the gateway
-- stopped: DROP TABLE sm_data.placement_log_retention; (it holds only operator-set caps; the agent falls back to its default).
