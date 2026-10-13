-- sm_metrics V002 — run by SmMetricsMigrator as sm_dp_migrator with SET ROLE sm_dp_owner. Plan 011 P11-5.1.
--
-- OR-107 OQ-20 (b), amending M-Q21's ruled counter shape (OR-79 stays unedited, I3): with ONE broad subscription per broker
-- connection (OQ-5, payload form), every unmanaged adapter's stream reaches the Data Plane (17,280 events a day per adapter at
-- File Mesh's 5 s heartbeat, F17), and an attribution failure for a MANAGED instance (a wrong or mistyped key field) would
-- drop all its events with nothing showing why. So a drop that has no single placement to be charged to is counted here, on a
-- BROKER-CONNECTION-LEVEL counter, a second key beside ingest_counter's per-placement one:
--   undeclared_dropped    no instance of the environment has that key, or it has no live placement
--   ambiguous_placement   more than one live placement could own the event (a re-target overlap, M-Q20; OR-92 / OQ-15)
--   parse_failed          the body did not parse, or the attribution key could not be read from it
--   buffer_dropped        the push queue was full
-- Counts only: no body, topic, key value or adapter id is stored (I6).
CREATE TABLE sm_metrics.broker_ingest_counter (
    tenant_id uuid NOT NULL,
    broker_connection_id uuid NOT NULL,
    reason text NOT NULL CONSTRAINT broker_ingest_counter_reason
        CHECK (reason IN ('undeclared_dropped', 'ambiguous_placement', 'parse_failed', 'buffer_dropped')),
    day date NOT NULL,
    count bigint NOT NULL CHECK (count > 0),
    PRIMARY KEY (tenant_id, broker_connection_id, reason, day));
-- NO foreign key to sm_data.broker_connection, deliberately: M-Q19 (b) admits sm_data.placement as the ONLY cross-schema foreign
-- key target of sm_metrics (SmMetricsSchemaIT.everySmMetricsForeignKeyTargetsSmMetricsOrSmDataPlacementOnly enforces it), and a
-- second target is the owner's to rule. The writer (PushIngestService) counts only for connections it holds, from its own plan,
-- under the tenant that owns them; a counter row is counts-only, and a connection is never deleted, so a row cannot dangle.

GRANT SELECT, INSERT, UPDATE (count) ON sm_metrics.broker_ingest_counter TO sm_dp_runtime;

SELECT sm_metrics.ensure_metric_partitions();            -- M-Q13: each migration seeds 3 months ahead
