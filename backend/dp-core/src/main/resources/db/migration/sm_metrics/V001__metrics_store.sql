-- sm_metrics V001 — run by SmMetricsMigrator as sm_dp_migrator with SET ROLE sm_dp_owner. DP-owned (M-Q19, OR-76).
-- Plan 011 P11-2.1. Shape ruled at the 2026-10-05 sitting (OR-92): OQ-12 (a) out_of_order and OQ-15 (a)
-- ambiguous_placement in the counter's reason CHECK, OQ-21 (2a) metrics_rollup_1h unpartitioned. OQ-20 stays OPEN
-- (OR-95), so ingest_counter keeps M-Q21's key exactly (placement_id NOT NULL). V002 is reserved for P11-5.1.
CREATE TABLE sm_metrics.series (                        -- M-Q7 (a): identity; insert-only
    tenant_id uuid NOT NULL,
    series_id bigint GENERATED ALWAYS AS IDENTITY,
    instance_id uuid NOT NULL,                          -- resolved from sm_data.placement, never from a batch
    placement_id uuid NOT NULL,
    metric_name text NOT NULL CONSTRAINT series_metric_name CHECK (metric_name ~ '^[a-z][a-z0-9_.]{0,127}$'),
    metric_type text NOT NULL CONSTRAINT series_metric_type CHECK (metric_type IN ('counter','gauge')),
    unit text NOT NULL CONSTRAINT series_unit CHECK (length(unit) BETWEEN 1 AND 64),
    source_discriminator text NOT NULL CONSTRAINT series_source CHECK (source_discriminator IN ('dp-poll','broker-push','agent')),
    labels_fingerprint text NOT NULL CONSTRAINT series_labels CHECK (labels_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, series_id),
    CONSTRAINT series_identity UNIQUE (tenant_id, instance_id, placement_id, metric_name, metric_type, unit,
                                       source_discriminator, labels_fingerprint),
    CONSTRAINT series_typed UNIQUE (tenant_id, series_id, metric_type),   -- the rollups' typed FK target
    -- M-Q19: an intra-DP FK to sm_data.placement, which the ruling permits (so are restart_marker's and ingest_counter's).
    CONSTRAINT series_placement FOREIGN KEY (tenant_id, placement_id) REFERENCES sm_data.placement (tenant_id, placement_id));

CREATE TABLE sm_metrics.series_segment (                -- package_version is an ATTRIBUTE (M-Q7); interval in force (discovery default D-3)
    tenant_id uuid NOT NULL, series_id bigint NOT NULL,
    effective_from timestamptz NOT NULL,
    package_version text NOT NULL,
    interval_seconds integer NULL CHECK (interval_seconds BETWEEN 1 AND 86400),   -- NULL on the push path
    PRIMARY KEY (tenant_id, series_id, effective_from),
    FOREIGN KEY (tenant_id, series_id) REFERENCES sm_metrics.series (tenant_id, series_id));

CREATE TABLE sm_metrics.series_reset (                  -- M-Q9: reset_count = count(*) per series; append-only
    tenant_id uuid NOT NULL, series_id bigint NOT NULL, at timestamptz NOT NULL,
    signal text NOT NULL CHECK (signal IN ('declared','docker','inferred')),
    PRIMARY KEY (tenant_id, series_id, at),
    FOREIGN KEY (tenant_id, series_id) REFERENCES sm_metrics.series (tenant_id, series_id));

CREATE TABLE sm_metrics.restart_marker (                -- the LATEST restart token observed per placement (M-Q9).
    -- Not the comparison base: each series is compared with the token seen with ITS OWN previous sample (in memory,
    -- P11-3.1). This row only records the latest observation, and seeds a series' previous token after a boot.
    tenant_id uuid NOT NULL, placement_id uuid NOT NULL,
    token text NOT NULL CHECK (length(token) BETWEEN 1 AND 256), observed_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, placement_id),
    CONSTRAINT restart_marker_placement FOREIGN KEY (tenant_id, placement_id) REFERENCES sm_data.placement (tenant_id, placement_id));

CREATE TABLE sm_metrics.metrics_raw (                   -- REQ-007: raw for the full window
    tenant_id uuid NOT NULL, series_id bigint NOT NULL,
    ts timestamptz NOT NULL,                            -- M-Q24 (8): UTC midpoint of sent/received (poll)
    value double precision NOT NULL
        CONSTRAINT raw_value_finite CHECK (value <> 'NaN'::float8 AND value <> 'Infinity'::float8 AND value <> '-Infinity'::float8),
    increment double precision NULL,                    -- counters only: M-Q9's attributable increase; NULL = none
    received_at timestamptz NOT NULL,                   -- M-Q24 (8): kept, 8 B/row
    PRIMARY KEY (tenant_id, series_id, ts),
    CONSTRAINT raw_series FOREIGN KEY (tenant_id, series_id) REFERENCES sm_metrics.series (tenant_id, series_id)
) PARTITION BY RANGE (ts);                              -- no DEFAULT partition, ever

CREATE TABLE sm_metrics.metrics_rollup_1h (
    tenant_id uuid NOT NULL, series_id bigint NOT NULL, bucket timestamptz NOT NULL,
    metric_type text NOT NULL CHECK (metric_type IN ('counter','gauge')),   -- bound to series by the typed FK below
    sample_count integer NOT NULL CHECK (sample_count > 0), expected_count integer NULL,
    sum double precision NULL, min double precision NULL, max double precision NULL,   -- gauges
    delta double precision NULL,           -- counters: sum(increment); NULL when every increment is NULL (never 0, M-Q9)
    covered_seconds double precision NULL, -- counters: sum of actual delta-ts of the samples whose increment is non-null (M-Q24 (8))
    last_value double precision NOT NULL, last_ts timestamptz NOT NULL,
    reset_count integer NOT NULL DEFAULT 0,
    computed_at timestamptz NOT NULL,
    -- The TYPE decides the shape; nullability never stands in for the type. A gauge hour has sum/min/max and no
    -- delta. A counter hour has no sum/min/max, and its delta and covered_seconds are NULL together (a new series'
    -- first sample, or an hour made only of post-gap samples, has increment NULL throughout).
    CONSTRAINT rollup_1h_type_shape CHECK (
        (metric_type = 'gauge'   AND sum IS NOT NULL AND min IS NOT NULL AND max IS NOT NULL
                                 AND delta IS NULL AND covered_seconds IS NULL)
     OR (metric_type = 'counter' AND sum IS NULL AND min IS NULL AND max IS NULL
                                 AND (delta IS NULL) = (covered_seconds IS NULL))),
    PRIMARY KEY (tenant_id, series_id, bucket),
    CONSTRAINT rollup_1h_series_typed FOREIGN KEY (tenant_id, series_id, metric_type)
        REFERENCES sm_metrics.series (tenant_id, series_id, metric_type)
);   -- UNPARTITIONED (OQ-21 (2a)): about 4.4 M rows in six months at S-A, so the carve-out's grammar stays metrics_raw_YYYY_MM

CREATE TABLE sm_metrics.metrics_rollup_1d (LIKE sm_metrics.metrics_rollup_1h INCLUDING DEFAULTS INCLUDING CONSTRAINTS,
    PRIMARY KEY (tenant_id, series_id, bucket),
    CONSTRAINT rollup_1d_series_typed FOREIGN KEY (tenant_id, series_id, metric_type)
        REFERENCES sm_metrics.series (tenant_id, series_id, metric_type));   -- unpartitioned; retention null

CREATE TABLE sm_metrics.ingest_counter (                -- M-Q21 (a), exactly as ruled under OQ-20 (a); (b)/(c) reshape it
    tenant_id uuid NOT NULL, placement_id uuid NOT NULL,     -- NOT NULL: a drop with no single placement is OQ-20's
    reason text NOT NULL CONSTRAINT ingest_counter_reason CHECK (reason IN ('scrape_failed','parse_failed','cap_rejected',
        'undeclared_dropped','decrease_without_restart','runway_degraded','deadline_missed','buffer_dropped',
        'out_of_order','ambiguous_placement'  /* OQ-12 (a) and OQ-15 (a), ruled OR-92 */)),
    day date NOT NULL, count bigint NOT NULL CHECK (count > 0),
    PRIMARY KEY (tenant_id, placement_id, reason, day),
    CONSTRAINT ingest_counter_placement FOREIGN KEY (tenant_id, placement_id) REFERENCES sm_data.placement (tenant_id, placement_id));

CREATE TABLE sm_metrics.partition_freeze_log (          -- M-Q13 "VACUUM FREEZE each partition on close" (OQ-10): written
    partition_name text PRIMARY KEY                     -- by MetricsPartitionMain freeze (owner credential) after the VACUUM;
        CHECK (partition_name ~ '^metrics_raw_[0-9]{4}_(0[1-9]|1[0-2])$'),   -- read by the runtime for unfrozenClosedPartitions
    frozen_at timestamptz NOT NULL);

-- M-Q13 (c'): the narrow I5 carve-out. No argument; every name and bound comes from the clock through a
-- closed grammar (metrics_raw_YYYY_MM exactly, UTC months; OQ-21 (2a)). MF2: pinned search_path ending in
-- pg_temp; PUBLIC revoked and EXECUTE granted in this same (transactional) migration. MF21, defence in depth: every
-- catalog relation is written pg_catalog-qualified, so neither a missing pg_temp entry NOR an unqualified name
-- alone lets a planted temp table shadow it. OQ-21 (1), confirmed (OR-92): the function lives in sm_metrics, not
-- sm_data (M-Q19's consequence). The grammar is the ruled one; it names one parent.
CREATE FUNCTION sm_metrics.ensure_metric_partitions() RETURNS date
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = sm_metrics, pg_temp
    SET lock_timeout = '5s'
    SET "TimeZone" = 'UTC'
AS $$
DECLARE
    first_month date := date_trunc('month', now())::date;   -- UTC by the SET clause
    child text; lo date; hi date; i int; attempt int;
    runway date;
BEGIN
    FOR i IN 0..3 LOOP                                         -- this month + 3 ahead; ONE parent, metrics_raw
        lo := (first_month + make_interval(months => i))::date;
        hi := (first_month + make_interval(months => i + 1))::date;
        child := 'metrics_raw_' || to_char(lo, 'YYYY_MM');     -- the closed grammar, exactly as ruled
        CONTINUE WHEN to_regclass('sm_metrics.' || child) IS NOT NULL;
        FOR attempt IN 1..3 LOOP
            BEGIN
                EXECUTE format('CREATE TABLE sm_metrics.%I (LIKE sm_metrics.metrics_raw INCLUDING DEFAULTS INCLUDING CONSTRAINTS)', child);
                EXECUTE format('ALTER TABLE sm_metrics.%I ADD CONSTRAINT %I CHECK (ts >= %L::timestamptz AND ts < %L::timestamptz)',
                               child, child || '_bound', to_char(lo, 'YYYY-MM-DD') || ' 00:00:00+00',
                               to_char(hi, 'YYYY-MM-DD') || ' 00:00:00+00');
                EXECUTE format('ALTER TABLE sm_metrics.metrics_raw ATTACH PARTITION sm_metrics.%I FOR VALUES FROM (%L) TO (%L)',
                               child, to_char(lo, 'YYYY-MM-DD') || ' 00:00:00+00', to_char(hi, 'YYYY-MM-DD') || ' 00:00:00+00');
                EXIT;
            EXCEPTION WHEN lock_not_available THEN
                IF attempt = 3 THEN RAISE; END IF;              -- the caller counts runway_degraded; never boot-fatal
            END;
        END LOOP;
    END LOOP;
    SELECT (max(to_date(right(c.relname, 7), 'YYYY_MM')) + interval '1 month')::date INTO runway
      FROM pg_catalog.pg_inherits h JOIN pg_catalog.pg_class c ON c.oid = h.inhrelid
     WHERE h.inhparent = 'sm_metrics.metrics_raw'::regclass;
    RETURN runway;
END $$;
REVOKE ALL ON FUNCTION sm_metrics.ensure_metric_partitions() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION sm_metrics.ensure_metric_partitions() TO sm_dp_runtime;

-- Grants: facts are append-only for the runtime; rollups and counters upsert; nothing is deleted at runtime.
GRANT SELECT, INSERT ON sm_metrics.series, sm_metrics.series_segment, sm_metrics.series_reset, sm_metrics.metrics_raw TO sm_dp_runtime;
GRANT SELECT, INSERT, UPDATE ON sm_metrics.metrics_rollup_1h, sm_metrics.metrics_rollup_1d, sm_metrics.restart_marker TO sm_dp_runtime;
GRANT SELECT, INSERT, UPDATE (count) ON sm_metrics.ingest_counter TO sm_dp_runtime;
GRANT SELECT ON sm_metrics.partition_freeze_log TO sm_dp_runtime;   -- written only by the owner-credential operator command

SELECT sm_metrics.ensure_metric_partitions();            -- M-Q13: each migration seeds 3 months ahead
