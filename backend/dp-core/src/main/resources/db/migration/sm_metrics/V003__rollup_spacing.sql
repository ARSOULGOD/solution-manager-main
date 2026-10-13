-- sm_metrics V003 -- run by SmMetricsMigrator as sm_dp_migrator with SET ROLE sm_dp_owner. Combined Fable review (OR-70), finding MON-01.
--
-- The rollup tiers judged a gap as sample_count < expected_count. The collector re-arms a source from the dispatch tick that
-- fired it (SourceScheduler.due), so its realised period is always a little longer than the declared interval: a closed hour at
-- 30 s holds ~118 of the 120 samples expected, and every hour of every polled series was drawn as a partial-samples gap. The
-- daily rule was blind the other way (an absent hour added nothing to the day's expected count).
--
-- The honest rollup equivalent of the raw rule (a spacing over twice the interval) needs the spacing, which the rows did not
-- keep. Two facts are added, the judgement is made at read time:
--   max_gap_seconds    the longest spacing between two consecutive samples whose LATER sample falls in the bucket. The first
--                      sample of a bucket looks back across the edge, so an outage that straddles two hours is seen in the
--                      hour it ended in. A day's value is the maximum of its hours'. NULL when the bucket's first sample is the
--                      series' first and it holds one sample, and on every row written before this migration (unknown is not a gap).
--   interval_seconds   the declared interval in force at the bucket's end (the segment EXPECTED already uses); a day's value is
--                      the largest of its hours', so an interval that changed inside the day is judged by the slower one.
-- Both are facts; "gapped" is max_gap_seconds > 2 * interval_seconds, computed by the query (JdbcMetricsQueryRepository), so the
-- factor is not frozen into stored data. expected_count is unchanged and still reported.
ALTER TABLE sm_metrics.metrics_rollup_1h
    ADD COLUMN max_gap_seconds double precision NULL CONSTRAINT rollup_1h_max_gap_nonneg CHECK (max_gap_seconds >= 0),
    ADD COLUMN interval_seconds integer NULL CONSTRAINT rollup_1h_interval_positive CHECK (interval_seconds > 0);
ALTER TABLE sm_metrics.metrics_rollup_1d
    ADD COLUMN max_gap_seconds double precision NULL CONSTRAINT rollup_1d_max_gap_nonneg CHECK (max_gap_seconds >= 0),
    ADD COLUMN interval_seconds integer NULL CONSTRAINT rollup_1d_interval_positive CHECK (interval_seconds > 0);
-- Grants: V001's table-level SELECT, INSERT, UPDATE for sm_dp_runtime already cover the new columns.
