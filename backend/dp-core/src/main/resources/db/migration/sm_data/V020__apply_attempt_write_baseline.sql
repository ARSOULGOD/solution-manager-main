-- OR-112 (1), second round (lane placeholder V020, the integrator renumbers). Run by Flyway as sm_dp_migrator after
-- `SET ROLE sm_dp_owner` (initSql), so the column is born owned by sm_dp_owner.
--
-- The container start the HOST reported when a MATERIALIZE write was read back: the same-clock baseline the ungated route
-- to the config ladder's `restart-observed` rung needs. V018's observation row holds the latest start the poller read, on
-- the Docker host's clock; the write's own end is on the data plane's. Comparing the two (as the first build did) is a
-- comparison across two clocks, and any skew between them misjudges a start that is within the skew of the write. This
-- column is the host's own clock value at the write, so "a start later than the write" becomes "a start different from,
-- and later than, the start the host reported when the file was written", and no data-plane timestamp is compared with a
-- host timestamp anywhere.
--
--   {"startedAt": "<ISO-8601 instant>", "processRef": "<replica ref>"}     -- the PreBaseline shape LIFECYCLE already carries
--
-- Recorded only by the MATERIALIZE attempt that CLOSED SUCCEEDED (the engine's read-back status is where the baseline is
-- read), in the same statement that closes it, so a crash cannot leave a succeeded write without it or a baseline on a
-- write that did not happen. Process metadata, never configuration (I6). NULL for every other attempt and for a write whose
-- container had no readable start (never started): a write with no baseline claims nothing, so nothing is compared.
ALTER TABLE sm_data.apply_attempt ADD COLUMN write_baseline jsonb;

-- Throw-free, as V015's shape check is: jsonb_path_exists with silent => true, every clause coalesced. Exactly the two keys,
-- both strings; the instant in the form java.time.Instant#toString writes (so the ::timestamptz read cannot throw), the ref
-- in the same grammar as a replica ref.
ALTER TABLE sm_data.apply_attempt ADD CONSTRAINT write_baseline_shape CHECK (write_baseline IS NULL OR (
        coalesce(jsonb_typeof(write_baseline) = 'object', false)
        AND NOT coalesce(jsonb_path_exists(write_baseline,
            '$.keyvalue() ? (@.key != "startedAt" && @.key != "processRef")', '{}'::jsonb, true), false)
        AND coalesce(jsonb_path_exists(write_baseline,
            '$ ? (@.startedAt.type() == "string" && @.startedAt like_regex "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z$")',
            '{}'::jsonb, true), false)
        AND coalesce(jsonb_path_exists(write_baseline,
            '$ ? (@.processRef.type() == "string" && @.processRef like_regex "^[A-Za-z0-9._:/@+-]{1,255}$")',
            '{}'::jsonb, true), false)));

ALTER TABLE sm_data.apply_attempt ADD CONSTRAINT write_baseline_only_for_materialize
    CHECK (write_baseline IS NULL OR step = 'MATERIALIZE');

-- No grant to write: V005's table-wide SELECT, INSERT, UPDATE, DELETE on apply_attempt already covers the new column.
