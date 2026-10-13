package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.query.RangeResult.MetricType;
import com.solace.sm.dp.metrics.query.MetricsQueryRows;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every metrics READ (plan 011 P11-6.1), over the runtime credential, in one read-only transaction with
 * {@code statement_timeout} set for it ({@code set_config(..., true)}: transaction-local, and BOUND, so no text is built
 * from a value).
 *
 * <p><b>I5.</b> Every statement names {@code tenant_id} on every tenant-bearing table, with a bind. The one that does not
 * is {@link Reader#unfrozenClosedPartitions}, which reads the install's partition catalog and says so. Every value is
 * bound; the only choices made in code are between constant statements (the two rollup tables, W3-13) and the optional
 * {@code entityType} clause, a constant; no statement is ever formatted.
 *
 * <p><b>Buckets are UTC regardless of the session's zone:</b> {@code date_bin} aligns to the 2001-01-01 UTC origin, as
 * {@link JdbcRollupRepository}'s rollups do, so a bucket read from a rollup and one rebuilt from raw are the same bucket.
 * The counter statement is {@link JdbcRollupRepository}'s hour statement with a variable width: the same window
 * {@code lag}, the same fall-back to the sample before the scan, so a stitched tail and a stored hour agree.
 */
public final class JdbcMetricsQueryRepository implements MetricsQueryRows {

    private static final String SQLSTATE_QUERY_CANCELED = "57014";
    private static final String ORIGIN = "TIMESTAMPTZ '2001-01-01 00:00:00+00'";

    private static final String ENTITY_CLAUSE = " AND EXISTS (SELECT 1 FROM sm_data.metric_definition d"
            + " WHERE d.tenant_id = s.tenant_id AND d.catalog_solution_id = i.catalog_solution_id"
            + " AND d.metric_name = s.metric_name AND d.component_type = :entityType)";

    private static final String INTERVAL_IN_FORCE = " LEFT JOIN LATERAL (SELECT g.interval_seconds"
            + " FROM sm_metrics.series_segment g WHERE g.tenant_id = :tenant AND g.series_id = a.series_id"
            + " AND g.effective_from < a.bucket + make_interval(secs => :width)"
            + " ORDER BY g.effective_from DESC LIMIT 1) seg ON true";

    private static final String EXPECTED = "CASE WHEN seg.interval_seconds IS NULL THEN 0"
            + " ELSE greatest(1, :width / seg.interval_seconds) END";

    private static final String GAUGE_BUCKETS = "SELECT a.series_id, a.bucket, a.n, " + EXPECTED + " AS expected,"
            + " a.s, a.lo, a.hi, NULL::double precision AS delta, NULL::double precision AS covered, false AS gapped FROM ("
            + " SELECT r.series_id, date_bin(make_interval(secs => :width), r.ts, " + ORIGIN + ") AS bucket,"
            + " count(*) AS n, sum(r.value) AS s, min(r.value) AS lo, max(r.value) AS hi"
            + " FROM sm_metrics.metrics_raw r WHERE r.tenant_id = :tenant AND r.series_id IN (:ids)"
            + " AND r.ts >= :from AND r.ts < :to GROUP BY r.series_id, 2) a"
            + INTERVAL_IN_FORCE + " ORDER BY a.series_id, a.bucket";

    private static final String COUNTER_BUCKETS = "SELECT a.series_id, a.bucket, a.n, " + EXPECTED + " AS expected,"
            + " NULL::double precision AS s, NULL::double precision AS lo, NULL::double precision AS hi, a.delta,"
            + " CASE WHEN a.delta IS NULL THEN NULL ELSE coalesce(a.covered, 0) END AS covered, false AS gapped FROM ("
            + " SELECT t.series_id, t.bucket, count(*) AS n, sum(t.increment) AS delta,"
            + " sum(t.gap) FILTER (WHERE t.increment IS NOT NULL) AS covered FROM ("
            + " SELECT r.tenant_id, r.series_id, r.ts, r.increment,"
            + " date_bin(make_interval(secs => :width), r.ts, " + ORIGIN + ") AS bucket,"
            + " extract(epoch FROM r.ts - coalesce(lag(r.ts) OVER (PARTITION BY r.series_id ORDER BY r.ts),"
            + " (SELECT max(p.ts) FROM sm_metrics.metrics_raw p WHERE p.tenant_id = r.tenant_id"
            + " AND p.series_id = r.series_id AND p.ts < r.ts)))::double precision AS gap"
            + " FROM sm_metrics.metrics_raw r WHERE r.tenant_id = :tenant AND r.series_id IN (:ids)"
            + " AND r.ts >= :from AND r.ts < :to) t GROUP BY t.series_id, t.bucket) a"
            + INTERVAL_IN_FORCE + " ORDER BY a.series_id, a.bucket";

    private static final String ROLLUP_HEAD = "SELECT h.series_id, h.bucket, h.sample_count AS n,"
            + " coalesce(h.expected_count, 0) AS expected, h.sum AS s, h.min AS lo, h.max AS hi, h.delta,"
            + " h.covered_seconds AS covered,"
            // Fable MON-01: the spacing judgement is made HERE, with D-3's rule (a spacing over twice the interval in force). A row
            // written before V003 has no spacing recorded and is judged by nothing: unknown is not a gap.
            + " coalesce(h.max_gap_seconds > 2 * h.interval_seconds, false) AS gapped FROM sm_metrics.";

    private static final String ROLLUP_TAIL = " h WHERE h.tenant_id = :tenant"
            + " AND h.series_id IN (:ids) AND h.bucket >= :from AND h.bucket < :to ORDER BY h.series_id, h.bucket";

    /** W3-13 (OR-99): two constant statements, chosen between and never formatted. */
    private static final String ROLLUP_1H_SQL = ROLLUP_HEAD + "metrics_rollup_1h" + ROLLUP_TAIL;
    private static final String ROLLUP_1D_SQL = ROLLUP_HEAD + "metrics_rollup_1d" + ROLLUP_TAIL;

    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;

    public JdbcMetricsQueryRepository(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public <T> T read(Duration statementTimeout, Function<Reader, T> body) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        try {
            return tx.execute(status -> {
                jdbc.queryForObject("SELECT set_config('statement_timeout', :ms, true)",
                        new MapSqlParameterSource("ms", Long.toString(statementTimeout.toMillis())), String.class);
                return body.apply(new JdbcReader());
            });
        } catch (DataAccessException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof PSQLException p && SQLSTATE_QUERY_CANCELED.equals(p.getSQLState())) {
                    throw new StatementTimeout(e);
                }
            }
            throw e;
        }
    }

    private final class JdbcReader implements Reader {

        @Override
        public List<SeriesRow> series(UUID tenant, String metric, String entityType, Collection<UUID> instanceIds, Instant asOf) {
            if (instanceIds.isEmpty()) {
                return List.of();
            }
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenant", tenant).addValue("metric", metric)
                    .addValue("instances", instanceIds).addValue("asOf", Sql.at(asOf));
            String sql = "SELECT s.series_id, s.instance_id, s.placement_id, s.metric_type, s.unit, i.name AS instance_name,"
                    + " s.created_at AS series_created, p.created_at AS placement_created, p.retired_at, seg.interval_seconds"
                    + " FROM sm_metrics.series s"
                    + " JOIN sm_data.instance_state i ON i.tenant_id = s.tenant_id AND i.instance_id = s.instance_id"
                    + " JOIN sm_data.placement p ON p.tenant_id = s.tenant_id AND p.placement_id = s.placement_id"
                    + " LEFT JOIN LATERAL (SELECT g.interval_seconds FROM sm_metrics.series_segment g"
                    + " WHERE g.tenant_id = :tenant AND g.series_id = s.series_id AND g.effective_from < :asOf"
                    + " ORDER BY g.effective_from DESC LIMIT 1) seg ON true"
                    + " WHERE s.tenant_id = :tenant AND s.metric_name = :metric AND s.instance_id IN (:instances)";
            if (entityType != null) {
                sql += ENTITY_CLAUSE;
                p.addValue("entityType", entityType);
            }
            sql += " ORDER BY s.created_at, s.series_id";
            return jdbc.query(sql, p, (rs, n) -> new SeriesRow(rs.getLong("series_id"),
                    rs.getObject("instance_id", UUID.class), rs.getObject("placement_id", UUID.class),
                    type(rs.getString("metric_type")), rs.getString("unit"), rs.getString("instance_name"),
                    Sql.instant(rs, "series_created"), Sql.instant(rs, "placement_created"), Sql.instant(rs, "retired_at"),
                    (Integer) rs.getObject("interval_seconds")));
        }

        @Override
        public Optional<Declared> declared(UUID tenant, String metric, String entityType, Collection<UUID> instanceIds) {
            if (instanceIds.isEmpty()) {
                return Optional.empty();
            }
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenant", tenant).addValue("metric", metric)
                    .addValue("instances", instanceIds);
            String sql = "SELECT d.metric_type, d.unit FROM sm_data.metric_definition d"
                    + " JOIN sm_data.instance_state i ON i.tenant_id = d.tenant_id"
                    + " AND i.catalog_solution_id = d.catalog_solution_id AND i.declared_package_version = d.package_version"
                    + " WHERE d.tenant_id = :tenant AND d.metric_name = :metric AND i.instance_id IN (:instances)";
            if (entityType != null) {
                sql += " AND d.component_type = :entityType";
                p.addValue("entityType", entityType);
            }
            sql += " ORDER BY d.package_version DESC, d.metric_type LIMIT 1";
            List<Declared> rows = jdbc.query(sql, p, (rs, n) -> new Declared(rs.getString("metric_type"), rs.getString("unit")));
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        }

        @Override
        public Map<UUID, String> instanceNames(UUID tenant, Collection<UUID> instanceIds) {
            if (instanceIds.isEmpty()) {
                return Map.of();
            }
            Map<UUID, String> out = new LinkedHashMap<>();
            jdbc.query("SELECT i.instance_id, i.name FROM sm_data.instance_state i WHERE i.tenant_id = :tenant"
                            + " AND i.instance_id IN (:instances) ORDER BY i.name, i.instance_id",
                    new MapSqlParameterSource().addValue("tenant", tenant).addValue("instances", instanceIds),
                    (rs, n) -> out.put(rs.getObject("instance_id", UUID.class), rs.getString("name")));
            return out;
        }

        @Override
        public List<BucketRow> raw(UUID tenant, MetricType type, Collection<Long> seriesIds, Instant from, Instant to,
                int bucketSeconds) {
            if (seriesIds.isEmpty()) {
                return List.of();
            }
            return jdbc.query(type == MetricType.GAUGE ? GAUGE_BUCKETS : COUNTER_BUCKETS,
                    new MapSqlParameterSource().addValue("tenant", tenant).addValue("ids", seriesIds)
                            .addValue("from", Sql.at(from)).addValue("to", Sql.at(to)).addValue("width", bucketSeconds),
                    JdbcMetricsQueryRepository::bucket);
        }

        @Override
        public List<BucketRow> rollup(UUID tenant, boolean daily, Collection<Long> seriesIds, Instant from, Instant to) {
            if (seriesIds.isEmpty()) {
                return List.of();
            }
            return jdbc.query(daily ? ROLLUP_1D_SQL : ROLLUP_1H_SQL,
                    new MapSqlParameterSource().addValue("tenant", tenant).addValue("ids", seriesIds)
                            .addValue("from", Sql.at(from)).addValue("to", Sql.at(to)),
                    JdbcMetricsQueryRepository::bucket);
        }

        @Override
        public Set<UUID> instancesWithCollectorFailures(UUID tenant, Collection<UUID> instanceIds, LocalDate sinceDay) {
            if (instanceIds.isEmpty()) {
                return Set.of();
            }
            List<String> failures = List.of(CollectorReason.SCRAPE_FAILED.wire(), CollectorReason.PARSE_FAILED.wire(),
                    CollectorReason.DEADLINE_MISSED.wire(), CollectorReason.BUFFER_DROPPED.wire(),
                    CollectorReason.RUNWAY_DEGRADED.wire());
            return new HashSet<>(jdbc.queryForList("SELECT DISTINCT p.instance_id FROM sm_metrics.ingest_counter c"
                            + " JOIN sm_data.placement p ON p.tenant_id = c.tenant_id AND p.placement_id = c.placement_id"
                            + " WHERE c.tenant_id = :tenant AND p.instance_id IN (:instances) AND c.day >= :since"
                            + " AND c.reason IN (:failures)",
                    new MapSqlParameterSource().addValue("tenant", tenant).addValue("instances", instanceIds)
                            .addValue("since", sinceDay).addValue("failures", failures),
                    UUID.class));
        }

        @Override
        public List<ObservedRow> observed(UUID tenant, String entityType, Collection<UUID> instanceIds) {
            if (instanceIds.isEmpty()) {
                return List.of();
            }
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenant", tenant).addValue("instances", instanceIds);
            String sql = "SELECT s.metric_name, s.metric_type, s.unit, s.created_at, s.placement_id,"
                    + " (SELECT max(r.ts) FROM sm_metrics.metrics_raw r WHERE r.tenant_id = s.tenant_id"
                    + " AND r.series_id = s.series_id) AS last_ts"
                    + " FROM sm_metrics.series s"
                    + " JOIN sm_data.instance_state i ON i.tenant_id = s.tenant_id AND i.instance_id = s.instance_id"
                    + " WHERE s.tenant_id = :tenant AND s.instance_id IN (:instances)";
            if (entityType != null) {
                sql += ENTITY_CLAUSE;
                p.addValue("entityType", entityType);
            }
            sql += " ORDER BY s.metric_name, s.created_at, s.series_id";
            return jdbc.query(sql, p, (rs, n) -> new ObservedRow(rs.getString("metric_name"), type(rs.getString("metric_type")),
                    rs.getString("unit"), Sql.instant(rs, "created_at"), rs.getObject("placement_id", UUID.class),
                    Sql.instant(rs, "last_ts")));
        }

        @Override
        public Map<UUID, Map<CollectorReason, Long>> reasonsByPlacement(UUID tenant, Collection<UUID> placementIds, LocalDate sinceDay) {
            if (placementIds.isEmpty()) {
                return Map.of();
            }
            Map<UUID, Map<CollectorReason, Long>> out = new HashMap<>();
            jdbc.query("SELECT c.placement_id, c.reason, sum(c.count)::bigint AS total FROM sm_metrics.ingest_counter c"
                            + " WHERE c.tenant_id = :tenant AND c.placement_id IN (:placements) AND c.day >= :since"
                            + " GROUP BY c.placement_id, c.reason",
                    new MapSqlParameterSource().addValue("tenant", tenant).addValue("placements", placementIds)
                            .addValue("since", sinceDay),
                    rs -> {
                        out.computeIfAbsent(rs.getObject("placement_id", UUID.class), k -> new EnumMap<>(CollectorReason.class))
                                .put(reason(rs.getString("reason")), rs.getLong("total"));
                    });
            return out;
        }

        @Override
        public Map<CollectorReason, Long> reasons(UUID tenant, LocalDate sinceDay) {
            Map<CollectorReason, Long> out = new EnumMap<>(CollectorReason.class);
            // W3-03 (OR-107 OQ-20 (b)): the per-placement counter AND the broker-connection-level one (a drop with no single
            // placement to charge: undeclared_dropped, ambiguous_placement, parse_failed, buffer_dropped), summed by reason
            jdbc.query("SELECT u.reason, sum(u.count)::bigint AS total FROM ("
                            + "SELECT c.reason, c.count FROM sm_metrics.ingest_counter c"
                            + " WHERE c.tenant_id = :tenant AND c.day >= :since"
                            + " UNION ALL "
                            + "SELECT b.reason, b.count FROM sm_metrics.broker_ingest_counter b"
                            + " WHERE b.tenant_id = :tenant AND b.day >= :since) u GROUP BY u.reason",
                    new MapSqlParameterSource().addValue("tenant", tenant).addValue("since", sinceDay),
                    rs -> {
                        out.put(reason(rs.getString("reason")), rs.getLong("total"));
                    });
            return out;
        }

        /**
         * INSTALL-LEVEL, and the one statement here with no tenant: the partitions of {@code metrics_raw} are the
         * installation's, and the answer is a count that names no tenant. The upper bound is read from the partition's own
         * bound expression, so a hand-attached partition is judged by its real bound, not its name.
         */
        @Override
        public int unfrozenClosedPartitions(Instant now) {
            Integer count = jdbc.queryForObject("SELECT count(*)::int FROM pg_catalog.pg_inherits h"
                            + " JOIN pg_catalog.pg_class c ON c.oid = h.inhrelid"
                            + " WHERE h.inhparent = 'sm_metrics.metrics_raw'::regclass"
                            + " AND substring(pg_catalog.pg_get_expr(c.relpartbound, c.oid) from 'TO \\(''([^'']+)''\\)')::timestamptz <= :now"
                            + " AND NOT EXISTS (SELECT 1 FROM sm_metrics.partition_freeze_log f WHERE f.partition_name = c.relname)",
                    new MapSqlParameterSource("now", Sql.at(now)), Integer.class);
            return count == null ? 0 : count;
        }
    }

    private static BucketRow bucket(ResultSet rs, int rowNum) throws SQLException {
        return new BucketRow(rs.getLong("series_id"), Sql.instant(rs, "bucket"), rs.getLong("n"), rs.getLong("expected"),
                (Double) rs.getObject("s"), (Double) rs.getObject("lo"), (Double) rs.getObject("hi"),
                (Double) rs.getObject("delta"), (Double) rs.getObject("covered"), rs.getBoolean("gapped"));
    }

    private static MetricType type(String stored) {
        return "counter".equals(stored) ? MetricType.COUNTER : MetricType.GAUGE;
    }

    private static CollectorReason reason(String stored) {
        return CollectorReason.valueOf(stored.toUpperCase(java.util.Locale.ROOT));
    }
}
