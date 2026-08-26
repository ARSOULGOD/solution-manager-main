-- Constraint tests for V001. Each asserts a claim docs/design/architecture.md §8 makes.
-- Run: psql -v ON_ERROR_STOP=1 -f V001__telemetry_schema.sql -f V001_constraints_test.sql
-- Self-contained and idempotent: rebuild the schema so results never depend on prior runs.
DROP SCHEMA IF EXISTS sm_data CASCADE;
\ir ../../main/resources/db/migration/V001__telemetry_schema.sql
SET search_path TO sm_data;
\set ON_ERROR_STOP off

-- fixtures: ONE instance, TWO placements (the multi-site case)
INSERT INTO placement (tenant_id, placement_id, instance_id, target_id) VALUES
 ('11111111-1111-1111-1111-111111111111','aaaaaaaa-0000-0000-0000-000000000001',
  'cccccccc-0000-0000-0000-000000000001','dddddddd-0000-0000-0000-00000000000A'),
 ('11111111-1111-1111-1111-111111111111','aaaaaaaa-0000-0000-0000-000000000002',
  'cccccccc-0000-0000-0000-000000000001','dddddddd-0000-0000-0000-00000000000B');

-- T1: same instance, same source_key ('cache-0'), same metric, DIFFERENT placement
--     => MUST be two distinct series (this is the multi-site merge defect)
INSERT INTO series (tenant_id, instance_id, placement_id, solution, metric_name, metric_type, unit,
                    package_version, source_key, labels_fingerprint) VALUES
 ('11111111-1111-1111-1111-111111111111','cccccccc-0000-0000-0000-000000000001',
  'aaaaaaaa-0000-0000-0000-000000000001','cache','cache.hit_rate','gauge','ratio','2.1','cache-0','f0'),
 ('11111111-1111-1111-1111-111111111111','cccccccc-0000-0000-0000-000000000001',
  'aaaaaaaa-0000-0000-0000-000000000002','cache','cache.hit_rate','gauge','ratio','2.1','cache-0','f0');
SELECT CASE WHEN count(*) = 2 THEN 'T1 PASS  two placements -> two series (no merge)'
            ELSE 'T1 FAIL  merged into ' || count(*) END FROM series;

-- T2: identical series twice => rejected by series_identity_unique
INSERT INTO series (tenant_id, instance_id, placement_id, solution, metric_name, metric_type, unit,
                    package_version, source_key, labels_fingerprint) VALUES
 ('11111111-1111-1111-1111-111111111111','cccccccc-0000-0000-0000-000000000001',
  'aaaaaaaa-0000-0000-0000-000000000001','cache','cache.hit_rate','gauge','ratio','2.1','cache-0','f0');
\echo 'T2 expect: duplicate key violation above'

-- T3: retype gauge->counter in a new package version => NEW series, not a silent retype
INSERT INTO series (tenant_id, instance_id, placement_id, solution, metric_name, metric_type, unit,
                    package_version, source_key, labels_fingerprint) VALUES
 ('11111111-1111-1111-1111-111111111111','cccccccc-0000-0000-0000-000000000001',
  'aaaaaaaa-0000-0000-0000-000000000001','cache','cache.hit_rate','counter','ratio','2.4','cache-0','f0');
SELECT CASE WHEN count(*)=3 THEN 'T3 PASS  retype across versions -> separate series'
            ELSE 'T3 FAIL  ' || count(*) END FROM series;

-- T4: two samples, same series, same ts => rejected
INSERT INTO metrics_raw (tenant_id, series_id, ts, value_numeric)
 SELECT tenant_id, series_id, '2026-08-15 10:00:00+00', 0.9 FROM series
 WHERE placement_id='aaaaaaaa-0000-0000-0000-000000000001' AND package_version='2.1';
INSERT INTO metrics_raw (tenant_id, series_id, ts, value_numeric)
 SELECT tenant_id, series_id, '2026-08-15 10:00:00+00', 0.1 FROM series
 WHERE placement_id='aaaaaaaa-0000-0000-0000-000000000001' AND package_version='2.1';
\echo 'T4 expect: duplicate key violation above (no silent overwrite)'

-- T5: BOTH placements can hold a sample at the SAME timestamp (no cross-site collision)
INSERT INTO metrics_raw (tenant_id, series_id, ts, value_numeric)
 SELECT tenant_id, series_id, '2026-08-15 10:00:00+00', 0.5 FROM series
 WHERE placement_id='aaaaaaaa-0000-0000-0000-000000000002';
SELECT CASE WHEN count(*)=2 THEN 'T5 PASS  same ts on two placements coexist'
            ELSE 'T5 FAIL  ' || count(*) END FROM metrics_raw;

-- T6: fact for a non-existent series => rejected (FK needs the unique target to exist)
INSERT INTO metrics_raw (tenant_id, series_id, ts, value_numeric)
 VALUES ('11111111-1111-1111-1111-111111111111', 999999, '2026-08-15 11:00:00+00', 1.0);
\echo 'T6 expect: FK violation above'

-- T7: no DEFAULT partition => an out-of-range ts MUST fail loudly, not land somewhere
INSERT INTO metrics_raw (tenant_id, series_id, ts, value_numeric)
 SELECT tenant_id, series_id, '2025-01-01 00:00:00+00', 1.0 FROM series LIMIT 1;
\echo 'T7 expect: no partition found for row (late backfill outside retention is rejected, not lost)'

-- T8: two business events sharing a timestamp in the same namespace, different ids => BOTH stored
INSERT INTO events_raw (tenant_id, instance_id, placement_id, source_namespace, source_event_id, event_type, ts) VALUES
 ('11111111-1111-1111-1111-111111111111','cccccccc-0000-0000-0000-000000000001',
  'aaaaaaaa-0000-0000-0000-000000000001','broker1/rg1','evt-1','file.complete','2026-08-15 12:00:00+00'),
 ('11111111-1111-1111-1111-111111111111','cccccccc-0000-0000-0000-000000000001',
  'aaaaaaaa-0000-0000-0000-000000000001','broker1/rg1','evt-2','file.complete','2026-08-15 12:00:00+00');
SELECT CASE WHEN count(*)=2 THEN 'T8 PASS  same-timestamp events both stored'
            ELSE 'T8 FAIL  ' || count(*) END FROM events_raw;

-- T9: redelivery of the SAME event id in the SAME namespace => suppressed
INSERT INTO events_raw (tenant_id, instance_id, placement_id, source_namespace, source_event_id, event_type, ts) VALUES
 ('11111111-1111-1111-1111-111111111111','cccccccc-0000-0000-0000-000000000001',
  'aaaaaaaa-0000-0000-0000-000000000001','broker1/rg1','evt-1','file.complete','2026-08-15 12:00:00+00');
\echo 'T9 expect: duplicate key violation above (redelivery suppressed)'

-- T10: a partitioned PK that OMITS the partition key must be rejected by PostgreSQL.
--      This is the exact defect three review rounds produced in prose.
CREATE TABLE bad_events (
    tenant_id uuid NOT NULL, source_event_id text NOT NULL, ts timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, source_event_id)
) PARTITION BY RANGE (ts);
\echo 'T10 expect: unique constraint must include all partitioning columns'

-- T11: CREATE INDEX CONCURRENTLY is not supported on a partitioned table
CREATE INDEX CONCURRENTLY bad_idx ON metrics_raw (value_numeric);
\echo 'T11 expect: cannot create index on partitioned table concurrently'

-- T12: DETACH PARTITION ... CONCURRENTLY works (PG14+) with no DEFAULT partition present
ALTER TABLE metrics_raw DETACH PARTITION metrics_raw_2026_09 CONCURRENTLY;
\echo 'T12 expect: no error (detach concurrently is legal without a DEFAULT partition)'
