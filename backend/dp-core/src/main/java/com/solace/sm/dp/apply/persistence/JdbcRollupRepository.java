package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.metrics.rollup.RollupRows;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_metrics.metrics_rollup_1h} and {@code metrics_rollup_1d} (plan 011 P11-3.2). Every statement is an idempotent
 * {@code INSERT ... ON CONFLICT DO UPDATE}, binds all its values and names {@code tenant_id} (I5).
 *
 * <p><b>Why {@code series_id IN (...)}.</b> {@code metrics_raw}'s primary key is {@code (tenant_id, series_id, ts)}, so a
 * time-range read that names only the tenant cannot use it. The tenant's series ids are read first and bound, in chunks,
 * so every read walks the key. No extra {@code (ts)} index is added.
 *
 * <p><b>Buckets are UTC regardless of the session's time zone:</b> {@code date_bin} aligns to an epoch origin, where
 * {@code date_trunc} would truncate in the session's zone (a half-hour offset would put an hour boundary at :30).
 *
 * <p><b>The type decides the shape.</b> A gauge hour carries sum/min/max and no delta; a counter hour carries no
 * sum/min/max and a delta that is NULL, never zero, when every increment in it is NULL ({@code sum} of nothing is NULL,
 * and the table's CHECK holds the pair together). {@code covered_seconds} is the sum of the ACTUAL spacing to the previous
 * sample over exactly the samples whose increment is not NULL; the first sample of an hour looks back across the hour
 * boundary, and past the start of the scan, for its previous timestamp.
 */
public final class JdbcRollupRepository implements RollupRows {

    private static final int CHUNK = 1000;
    private static final String ORIGIN = "TIMESTAMPTZ '2001-01-01 00:00:00+00'";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRollupRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private List<Long> seriesOfType(UUID tenantId, String type) {
        return jdbc.queryForList("SELECT series_id FROM sm_metrics.series WHERE tenant_id = :tenant AND metric_type = :type"
                        + " ORDER BY series_id",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("type", type), Long.class);
    }

    private static List<List<Long>> chunks(List<Long> ids) {
        List<List<Long>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += CHUNK) {
            out.add(ids.subList(i, Math.min(ids.size(), i + CHUNK)));
        }
        return out;
    }

    @Override
    public int rollUpHours(UUID tenantId, Instant from, Instant to, Instant computedAt) {
        int written = 0;
        for (List<Long> ids : chunks(seriesOfType(tenantId, "gauge"))) {
            written += Sql.write(() -> jdbc.update(GAUGE_HOURS, params(tenantId, ids, from, to, computedAt)));
        }
        for (List<Long> ids : chunks(seriesOfType(tenantId, "counter"))) {
            written += Sql.write(() -> jdbc.update(COUNTER_HOURS, params(tenantId, ids, from, to, computedAt)));
        }
        return written;
    }

    @Override
    public int rollUpDays(UUID tenantId, Instant from, Instant to, Instant computedAt) {
        List<Long> all = new ArrayList<>(seriesOfType(tenantId, "gauge"));
        all.addAll(seriesOfType(tenantId, "counter"));
        int written = 0;
        for (List<Long> ids : chunks(all)) {
            written += Sql.write(() -> jdbc.update(DAYS, params(tenantId, ids, from, to, computedAt)));
        }
        return written;
    }

    private static MapSqlParameterSource params(UUID tenantId, List<Long> ids, Instant from, Instant to, Instant now) {
        return new MapSqlParameterSource().addValue("tenant", tenantId).addValue("ids", ids)
                .addValue("from", Sql.at(from)).addValue("to", Sql.at(to)).addValue("now", Sql.at(now));
    }

    private static final String EXPECTED = " LEFT JOIN LATERAL (SELECT (3600 / g.interval_seconds) AS expected,"
            + " g.interval_seconds AS interval_seconds"
            + " FROM sm_metrics.series_segment g WHERE g.tenant_id = a.tenant_id AND g.series_id = a.series_id"
            + " AND g.effective_from < a.bucket + interval '1 hour' ORDER BY g.effective_from DESC LIMIT 1) seg ON true";

    private static final String RESETS = " LEFT JOIN (SELECT x.series_id, date_bin('1 hour', x.at, " + ORIGIN + ") AS bucket,"
            + " count(*)::int AS n FROM sm_metrics.series_reset x WHERE x.tenant_id = :tenant AND x.series_id IN (:ids)"
            + " AND x.at >= :from AND x.at < :to GROUP BY 1, 2) rc ON rc.series_id = a.series_id AND rc.bucket = a.bucket";

    /**
     * Fable MON-01: {@code max_gap_seconds} is the longest spacing to the PREVIOUS sample over the samples that fall in the
     * bucket, the first of which looks back across the bucket's edge (and past the start of the scan) exactly as the counter
     * statement's {@code covered_seconds} already does. {@code interval_seconds} is the segment in force at the bucket's end.
     */
    private static final String GAUGE_HOURS = "INSERT INTO sm_metrics.metrics_rollup_1h (tenant_id, series_id, bucket,"
            + " metric_type, sample_count, expected_count, sum, min, max, delta, covered_seconds, last_value, last_ts,"
            + " reset_count, computed_at, max_gap_seconds, interval_seconds)"
            + " SELECT a.tenant_id, a.series_id, a.bucket, 'gauge', a.n, seg.expected, a.s, a.lo, a.hi, NULL, NULL,"
            + " a.last_value, a.last_ts, coalesce(rc.n, 0), :now, a.max_gap, seg.interval_seconds FROM ("
            + " SELECT t.tenant_id, t.series_id, t.bucket, count(*)::int AS n,"
            + " sum(t.value) AS s, min(t.value) AS lo, max(t.value) AS hi,"
            + " (array_agg(t.value ORDER BY t.ts DESC))[1] AS last_value, max(t.ts) AS last_ts, max(t.gap) AS max_gap FROM ("
            + " SELECT r.tenant_id, r.series_id, r.ts, r.value, date_bin('1 hour', r.ts, " + ORIGIN + ") AS bucket,"
            + " extract(epoch FROM r.ts - coalesce(lag(r.ts) OVER (PARTITION BY r.series_id ORDER BY r.ts),"
            + " (SELECT max(p.ts) FROM sm_metrics.metrics_raw p WHERE p.tenant_id = r.tenant_id"
            + " AND p.series_id = r.series_id AND p.ts < r.ts)))::double precision AS gap"
            + " FROM sm_metrics.metrics_raw r WHERE r.tenant_id = :tenant AND r.series_id IN (:ids)"
            + " AND r.ts >= :from AND r.ts < :to) t GROUP BY t.tenant_id, t.series_id, t.bucket) a"
            + EXPECTED + RESETS
            + " ON CONFLICT (tenant_id, series_id, bucket) DO UPDATE SET metric_type = EXCLUDED.metric_type,"
            + " sample_count = EXCLUDED.sample_count, expected_count = EXCLUDED.expected_count, sum = EXCLUDED.sum,"
            + " min = EXCLUDED.min, max = EXCLUDED.max, delta = EXCLUDED.delta,"
            + " covered_seconds = EXCLUDED.covered_seconds, last_value = EXCLUDED.last_value,"
            + " last_ts = EXCLUDED.last_ts, reset_count = EXCLUDED.reset_count, computed_at = EXCLUDED.computed_at,"
            + " max_gap_seconds = EXCLUDED.max_gap_seconds, interval_seconds = EXCLUDED.interval_seconds";

    private static final String COUNTER_HOURS = "INSERT INTO sm_metrics.metrics_rollup_1h (tenant_id, series_id, bucket,"
            + " metric_type, sample_count, expected_count, sum, min, max, delta, covered_seconds, last_value, last_ts,"
            + " reset_count, computed_at, max_gap_seconds, interval_seconds)"
            + " SELECT a.tenant_id, a.series_id, a.bucket, 'counter', a.n, seg.expected, NULL, NULL, NULL, a.delta,"
            + " CASE WHEN a.delta IS NULL THEN NULL ELSE coalesce(a.covered, 0) END,"
            + " a.last_value, a.last_ts, coalesce(rc.n, 0), :now, a.max_gap, seg.interval_seconds FROM ("
            + " SELECT t.tenant_id, t.series_id, t.bucket, count(*)::int AS n, sum(t.increment) AS delta,"
            + " sum(t.gap) FILTER (WHERE t.increment IS NOT NULL) AS covered, max(t.gap) AS max_gap,"
            + " (array_agg(t.value ORDER BY t.ts DESC))[1] AS last_value, max(t.ts) AS last_ts FROM ("
            + " SELECT r.tenant_id, r.series_id, r.ts, r.value, r.increment,"
            + " date_bin('1 hour', r.ts, " + ORIGIN + ") AS bucket,"
            + " extract(epoch FROM r.ts - coalesce(lag(r.ts) OVER (PARTITION BY r.series_id ORDER BY r.ts),"
            + " (SELECT max(p.ts) FROM sm_metrics.metrics_raw p WHERE p.tenant_id = r.tenant_id"
            + " AND p.series_id = r.series_id AND p.ts < r.ts)))::double precision AS gap"
            + " FROM sm_metrics.metrics_raw r WHERE r.tenant_id = :tenant AND r.series_id IN (:ids)"
            + " AND r.ts >= :from AND r.ts < :to) t GROUP BY t.tenant_id, t.series_id, t.bucket) a"
            + EXPECTED + RESETS
            + " ON CONFLICT (tenant_id, series_id, bucket) DO UPDATE SET metric_type = EXCLUDED.metric_type,"
            + " sample_count = EXCLUDED.sample_count, expected_count = EXCLUDED.expected_count, sum = EXCLUDED.sum,"
            + " min = EXCLUDED.min, max = EXCLUDED.max, delta = EXCLUDED.delta,"
            + " covered_seconds = EXCLUDED.covered_seconds, last_value = EXCLUDED.last_value,"
            + " last_ts = EXCLUDED.last_ts, reset_count = EXCLUDED.reset_count, computed_at = EXCLUDED.computed_at,"
            + " max_gap_seconds = EXCLUDED.max_gap_seconds, interval_seconds = EXCLUDED.interval_seconds";

    /** The UTC days that contain an hourly bucket starting in [from, to), summed from the hourly rows. */
    private static final String DAYS = "INSERT INTO sm_metrics.metrics_rollup_1d (tenant_id, series_id, bucket, metric_type,"
            + " sample_count, expected_count, sum, min, max, delta, covered_seconds, last_value, last_ts, reset_count,"
            + " computed_at, max_gap_seconds, interval_seconds)"
            + " SELECT h.tenant_id, h.series_id, date_bin('1 day', h.bucket, " + ORIGIN + ") AS day, h.metric_type,"
            + " sum(h.sample_count)::int, sum(h.expected_count)::int, sum(h.sum), min(h.min), max(h.max), sum(h.delta),"
            + " sum(h.covered_seconds), (array_agg(h.last_value ORDER BY h.last_ts DESC))[1], max(h.last_ts),"
            + " sum(h.reset_count)::int, :now, max(h.max_gap_seconds), max(h.interval_seconds)"
            + " FROM sm_metrics.metrics_rollup_1h h"
            + " WHERE h.tenant_id = :tenant AND h.series_id IN (:ids)"
            + " AND h.bucket >= date_bin('1 day', :from, " + ORIGIN + ")"
            + " AND h.bucket < date_bin('1 day', :to - interval '1 microsecond', " + ORIGIN + ") + interval '1 day'"
            + " GROUP BY h.tenant_id, h.series_id, 3, h.metric_type"
            + " ON CONFLICT (tenant_id, series_id, bucket) DO UPDATE SET metric_type = EXCLUDED.metric_type,"
            + " sample_count = EXCLUDED.sample_count, expected_count = EXCLUDED.expected_count, sum = EXCLUDED.sum,"
            + " min = EXCLUDED.min, max = EXCLUDED.max, delta = EXCLUDED.delta,"
            + " covered_seconds = EXCLUDED.covered_seconds, last_value = EXCLUDED.last_value,"
            + " last_ts = EXCLUDED.last_ts, reset_count = EXCLUDED.reset_count, computed_at = EXCLUDED.computed_at,"
            + " max_gap_seconds = EXCLUDED.max_gap_seconds, interval_seconds = EXCLUDED.interval_seconds";
}
