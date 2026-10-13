package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.metrics.ingest.IngestRows;
import java.sql.Savepoint;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_metrics.metrics_raw} for the ingest path (plan 011 P11-3.1 step 6). The only class outside the runway
 * repository that reads a {@code org.postgresql} type, because it classifies a refused insert and hands the service a
 * TYPED outcome, never message text.
 *
 * <p><b>A missing partition</b> is the measured SQLSTATE {@code 23514} with NO constraint name (MF23: the table's own
 * {@code raw_value_finite} is also a {@code check_violation} but names itself), classified by
 * {@link JdbcPartitionRunwayRepository#classify}. Anything else the database refuses is reshaped by
 * {@link PgConstraintViolation} into a named, value-free refusal, so a bad row never echoes its values into a log (I6).
 */
public final class JdbcRawSampleRepository implements IngestRows.Raw {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRawSampleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Outcome insert(UUID tenantId, long seriesId, Instant ts, double value, Double increment, Instant receivedAt) {
        try {
            int n = jdbc.update("INSERT INTO sm_metrics.metrics_raw (tenant_id, series_id, ts, value, increment,"
                    + " received_at) VALUES (:tenant, :series, :ts, :value, :increment, :received)"
                    + " ON CONFLICT (tenant_id, series_id, ts) DO NOTHING",
                    new MapSqlParameterSource().addValue("tenant", tenantId).addValue("series", seriesId)
                            .addValue("ts", Sql.at(ts)).addValue("value", value)
                            .addValue("increment", increment, Types.DOUBLE).addValue("received", Sql.at(receivedAt)));
            return n == 1 ? Outcome.INSERTED : Outcome.DUPLICATE;
        } catch (DataAccessException e) {
            PSQLException pg = unwrap(e);
            if (pg != null && JdbcPartitionRunwayRepository.classify(pg) == JdbcPartitionRunwayRepository.Refusal.NO_PARTITION) {
                return Outcome.NO_PARTITION;
            }
            throw PgConstraintViolation.from(e).map(RuntimeException.class::cast).orElse(e);
        }
    }

    @Override
    public Outcome insertBeyondRunway(UUID tenantId, long seriesId, Instant ts, double value, Double increment,
            Instant receivedAt) {
        return jdbc.getJdbcOperations().execute((ConnectionCallback<Outcome>) connection -> {
            // The connection is the transaction's own (DataSourceUtils), so the savepoint scopes the INSERT below.
            Savepoint savepoint = connection.setSavepoint();
            Outcome outcome = insert(tenantId, seriesId, ts, value, increment, receivedAt);
            if (outcome == Outcome.NO_PARTITION) {
                connection.rollback(savepoint);
            } else {
                connection.releaseSavepoint(savepoint);
            }
            return outcome;
        });
    }

    private static PSQLException unwrap(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException pg) {
                return pg;
            }
        }
        return null;
    }

    @Override
    public Optional<IngestRows.LastSample> latest(UUID tenantId, long seriesId) {
        return jdbc.query("SELECT ts, value FROM sm_metrics.metrics_raw WHERE tenant_id = :tenant AND series_id = :series"
                        + " ORDER BY ts DESC LIMIT 1",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("series", seriesId),
                (rs, i) -> new IngestRows.LastSample(Sql.instant(rs, "ts"), rs.getDouble("value"))).stream().findFirst();
    }

    @Override
    public boolean exists(UUID tenantId, long seriesId, Instant ts) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM sm_metrics.metrics_raw WHERE tenant_id = :tenant"
                        + " AND series_id = :series AND ts = :ts",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("series", seriesId)
                        .addValue("ts", Sql.at(ts)), Integer.class);
        return n != null && n > 0;
    }
}
