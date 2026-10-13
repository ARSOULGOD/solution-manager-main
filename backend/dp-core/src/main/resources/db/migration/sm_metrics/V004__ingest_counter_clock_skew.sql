-- sm_metrics V004 -- run by SmMetricsMigrator as sm_dp_migrator with SET ROLE sm_dp_owner. Plan 015 T3.4 (the symbolic VMA), OR-130 F-6.
--
-- OR-130 F-6 / OR-134 / AGW-D27 item 5: an agent whose measured clock offset exceeds +-5 minutes has its samples refused and counted
-- 'clock_skew'. ingest_counter's reason list is a closed CHECK (V001), so the new reason needs the CHECK dropped and re-added with the
-- ten reasons V001 names plus 'clock_skew'. Nothing else changes: the table, its key, its grants and its foreign key are untouched.
--
-- LOCK CLASS: ALTER TABLE ... DROP CONSTRAINT and ADD CONSTRAINT ... CHECK take ACCESS EXCLUSIVE on sm_metrics.ingest_counter
-- (PostgreSQL 14, ALTER TABLE: "An ACCESS EXCLUSIVE lock is acquired unless explicitly noted"). The table holds one row per
-- (placement, reason, day), so the re-validation scan is small, and the two statements run in this one transaction.
--
-- ROLLBACK: nothing does so automatically, and a published migration is never edited (OR-22). By hand: delete any 'clock_skew' rows,
-- re-add the V001 CHECK without it, and delete the V004 row from sm_metrics.flyway_schema_history.
ALTER TABLE sm_metrics.ingest_counter DROP CONSTRAINT ingest_counter_reason;
ALTER TABLE sm_metrics.ingest_counter ADD CONSTRAINT ingest_counter_reason CHECK (reason IN ('scrape_failed', 'parse_failed',
    'cap_rejected', 'undeclared_dropped', 'decrease_without_restart', 'runway_degraded', 'deadline_missed', 'buffer_dropped',
    'out_of_order', 'ambiguous_placement', 'clock_skew'));
