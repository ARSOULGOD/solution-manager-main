package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.metrics.export.ExportRows;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The export's reads (plan 011 P11-6.2, M-Q18 {@code OR-79}), as the runtime role: one bound {@code SELECT} streamed through a
 * server-side cursor, and the small bound read that projects its size.
 *
 * <p><b>Why the cursor works (DMR-20; PgJDBC documents it, and {@code ExportStreamingMeasurementIT} measures it in this
 * stack).</b> PgJDBC reads from a cursor, in batches of the fetch size, only when the connection has autocommit OFF, the
 * statement is {@code TYPE_FORWARD_ONLY} and single, and the fetch size is above 0; "setting it back to 0 restores default
 * behavior of caching all rows". The transaction supplies the first, the statement creator the next two, and
 * the export properties' validation the last. So every export runs inside a read-only
 * transaction that is held open for the whole stream, which is also why a permit from the one budget is held for it.
 *
 * <p><b>I5.</b> The statement text is a constant and every value (the tenant, the metric, the instance list, both ends of the
 * range, and the timeout) is bound. The tenant is bound once, in {@code r.tenant_id = ?}, and the join to {@code series}
 * carries it by equality, so a series of another tenant cannot match however its instance id was supplied. The statement
 * timeout is set with {@code set_config(..., true)} (transaction-local, so it does not outlive the export on the pooled
 * connection) because {@code SET} cannot take a bound value.
 *
 * <p>Order is {@code series_id, ts}, the primary key's own order, chosen so the planner can read the rows from the index
 * rather than sort them first. The plan shape is not asserted by a test.
 */
public final class JdbcMetricsExportRepository implements ExportRows {

    /** The one streaming statement. Five bound values, in this order: tenant, metric, instance ids, from, to. */
    static final String STREAM_SQL = "SELECT s.instance_id, s.placement_id, r.ts, r.value"
            + " FROM sm_metrics.metrics_raw r"
            + " JOIN sm_metrics.series s ON s.tenant_id = r.tenant_id AND s.series_id = r.series_id"
            + " WHERE r.tenant_id = ? AND s.metric_name = ? AND s.instance_id = ANY(?) AND r.ts >= ? AND r.ts < ?"
            + " ORDER BY r.series_id, r.ts";

    /** The projection's read: every series the export would touch with its segments, in time order. Three bound values. */
    static final String SPANS_SQL = "SELECT s.series_id, g.effective_from, g.interval_seconds"
            + " FROM sm_metrics.series s"
            + " LEFT JOIN sm_metrics.series_segment g ON g.tenant_id = s.tenant_id AND g.series_id = s.series_id"
            + " WHERE s.tenant_id = ? AND s.metric_name = ? AND s.instance_id = ANY(?)"
            + " ORDER BY s.series_id, g.effective_from";

    /**
     * W3-12: the push path's projection. A count STOPPED at a limit, over the primary key's own order. Five bound values:
     * tenant, series ids, from, to, limit.
     */
    static final String COUNT_SQL = "SELECT count(*) FROM (SELECT 1 FROM sm_metrics.metrics_raw r"
            + " WHERE r.tenant_id = ? AND r.series_id = ANY(?) AND r.ts >= ? AND r.ts < ? LIMIT ?) c";

    /** Transaction-local, bound; the value is a duration in milliseconds, which PostgreSQL reads as a timeout. */
    static final String TIMEOUT_SQL = "SELECT set_config('statement_timeout', ?, true)";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate readOnly;

    public JdbcMetricsExportRepository(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public List<SeriesSpan> spans(UUID tenantId, String metric, List<UUID> instanceIds) {
        return readOnly.execute(status -> {
            List<SeriesSpan> out = new ArrayList<>();
            long[] current = {-1};
            List<List<Segment>> segments = new ArrayList<>();
            List<Long> ids = new ArrayList<>();
            jdbc.getJdbcTemplate().query(creator(SPANS_SQL, tenantId, metric, instanceIds, null, null, 0), (RowCallbackHandler) rs -> {
                long series = rs.getLong(1);
                if (series != current[0]) {
                    current[0] = series;
                    ids.add(series);
                    segments.add(new ArrayList<>());
                }
                Instant from = Sql.instant(rs, "effective_from");
                if (from != null) {
                    int interval = rs.getInt(3);
                    segments.get(segments.size() - 1).add(new Segment(from, rs.wasNull() ? null : interval));
                }
            });
            for (int i = 0; i < ids.size(); i++) {
                out.add(new SeriesSpan(ids.get(i), segments.get(i)));
            }
            return out;
        });
    }

    @Override
    public long countRaw(UUID tenantId, List<Long> seriesIds, Instant from, Instant to, long atMost, Duration statementTimeout) {
        if (seriesIds.isEmpty() || atMost < 1) {
            return 0;
        }
        return readOnly.execute(status -> {
            jdbc.getJdbcTemplate().queryForObject(TIMEOUT_SQL, String.class, statementTimeout.toMillis() + "ms");
            Long n = jdbc.getJdbcTemplate().query((Connection c) -> {
                PreparedStatement ps = c.prepareStatement(COUNT_SQL);
                try {
                    ps.setObject(1, tenantId);
                    ps.setArray(2, c.createArrayOf("bigint", seriesIds.toArray(new Long[0])));
                    ps.setObject(3, Sql.at(from));
                    ps.setObject(4, Sql.at(to));
                    ps.setLong(5, atMost);
                } catch (SQLException | RuntimeException e) {
                    ps.close();
                    throw e;
                }
                return ps;
            }, rs -> rs.next() ? rs.getLong(1) : 0L);
            return n == null ? 0L : n;
        });
    }

    @Override
    public long stream(UUID tenantId, String metric, List<UUID> instanceIds, Instant from, Instant to, Duration statementTimeout,
            int fetchSize, Sink sink) throws IOException {
        try {
            return readOnly.execute(status -> {
                jdbc.getJdbcTemplate().queryForObject(TIMEOUT_SQL, String.class, statementTimeout.toMillis() + "ms");
                long[] rows = {0};
                jdbc.getJdbcTemplate().query(creator(STREAM_SQL, tenantId, metric, instanceIds, from, to, fetchSize),
                        (RowCallbackHandler) rs -> {
                            try {
                                sink.row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), Sql.instant(rs, "ts"),
                                        rs.getDouble(4));
                            } catch (IOException e) {
                                throw new SinkFailed(e);
                            }
                            rows[0]++;
                        });
                return rows[0];
            });
        } catch (SinkFailed e) {
            throw e.cause;
        }
    }

    /** Carries the sink's IOException through the callback, which cannot throw a checked one. */
    private static final class SinkFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient IOException cause;

        SinkFailed(IOException cause) {
            super(null, cause, false, false);
            this.cause = cause;
        }
    }

    /** A forward-only, read-only statement with every value bound; {@code from} null means the three-value spans statement. */
    private static PreparedStatementCreator creator(String sql, UUID tenantId, String metric, List<UUID> instanceIds, Instant from,
            Instant to, int fetchSize) {
        return (Connection c) -> {
            if (from != null && c.getAutoCommit()) {
                // PgJDBC would silently cache every row of the result (the heap grows with the export). Refuse instead:
                // this means the template and the transaction manager do not share one DataSource.
                throw new IllegalStateException("the export stream must run inside a transaction (autocommit is on)");
            }
            PreparedStatement ps = c.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            try {
                ps.setObject(1, tenantId);
                ps.setString(2, metric);
                ps.setArray(3, c.createArrayOf("uuid", instanceIds.toArray(new UUID[0])));
                if (from != null) {
                    ps.setObject(4, Sql.at(from));
                    ps.setObject(5, Sql.at(to));
                    ps.setFetchSize(fetchSize);
                }
            } catch (SQLException | RuntimeException e) {
                ps.close();
                throw e;
            }
            return ps;
        };
    }
}
