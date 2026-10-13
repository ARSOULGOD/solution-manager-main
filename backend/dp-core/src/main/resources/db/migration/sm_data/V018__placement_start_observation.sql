-- OR-112 (1) (plan 010 T6.5's second route, built now; lane placeholder V018, the integrator renumbers). Run by Flyway as
-- sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so the object below is born owned by sm_dp_owner. Keyed by
-- tenant_id first (I5); no foreign key leaves sm_data (I7).
--
-- The container start the data plane last OBSERVED on a placement. An UNGATED apply (manual, rolling-no-validation,
-- restart-all, scheduled) runs no health gate and reads nothing back after the restart, so its journal can never say
-- the restart happened; this row is the evidence that does. The config ladder's `restart-observed` rung
-- (ConfigLadder) and the metrics active list (JdbcRestartObservedGenerationRepository, which path 1's collection plan and
-- path 2's push plan both read) compare `started_at` with the start the HOST reported when the apply's write was read back
-- (apply_attempt.write_baseline, V020): a start LATER than that one is the new file loaded; an equal or earlier one is its
-- predecessor running. Both are the host's clock, so no skew against the data plane's can misjudge a start.
-- The data plane's poller reads it with `docker inspect` ({{.State.StartedAt}}, plan 010 T6.9 M18: it moves on restart and on stop-then-start, and it is the Docker host's clock).
--
-- ONE row per placement, the LATEST observation, not a history. Why that is enough: a generation g, written at W(g),
-- has observed its restart when the latest start is after W(g); if a newer generation was written after that start, the
-- newer one is the one still waiting and the older one is the newest-that-observed (the collector and the push plan take
-- the maximum). A row is written only when the observed start CHANGES, so a poll that sees the same container again moves
-- nothing and a placement that never restarts costs one read per round and no write.
--
-- A placement is immutable and never deleted, so its observation is kept when it is retired; reads join to the live set.
CREATE TABLE sm_data.placement_start_observation (
    tenant_id uuid NOT NULL,
    placement_id uuid NOT NULL,
    -- the HOST's clock (the Docker daemon's), as `docker inspect` reported it; 0001-01-01 is what it reports for a
    -- container that never started, which is earlier than any write and so never counts
    started_at timestamptz NOT NULL,
    -- the container id: process metadata, never configuration (I6). The same grammar as a RuntimeRef value.
    process_ref text NOT NULL
        CONSTRAINT start_observation_process_ref CHECK (process_ref ~ '^[A-Za-z0-9._:/@+-]{1,255}$'),
    -- the data plane's clock, when it saw THIS start for the first time
    observed_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, placement_id),
    CONSTRAINT start_observation_has_placement FOREIGN KEY (tenant_id, placement_id)
        REFERENCES sm_data.placement (tenant_id, placement_id));

-- Grants: the poller registers a placement's first observation and moves it when the start changes; it never edits
-- identity and never deletes. (SELECT is V001's default read-baseline.)
GRANT INSERT ON sm_data.placement_start_observation TO sm_dp_runtime;
GRANT UPDATE (started_at, process_ref, observed_at) ON sm_data.placement_start_observation TO sm_dp_runtime;
