package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.metrics.push.BrokerCounterRows;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_metrics.broker_ingest_counter}: the broker-connection-level drop counters (plan 011 P11-5.1; OR-107 OQ-20 (b)), the
 * second key beside {@link JdbcIngestCounterRepository}'s per-placement one, for a push drop with no single placement to be
 * charged to. An upsert that ADDS: the runtime role holds {@code UPDATE (count)} and nothing else on the table.
 */
public final class JdbcBrokerCounterRepository implements BrokerCounterRows {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBrokerCounterRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(UUID tenantId, UUID brokerConnectionId, CollectorReason reason, LocalDate day, long n) {
        if (n <= 0) {
            throw new IllegalArgumentException("a counter is only ever added to, by a positive amount");
        }
        Sql.write(() -> jdbc.update("INSERT INTO sm_metrics.broker_ingest_counter (tenant_id, broker_connection_id,"
                + " reason, day, count) VALUES (:tenant, :connection, :reason, :day, :count)"
                + " ON CONFLICT (tenant_id, broker_connection_id, reason, day)"
                + " DO UPDATE SET count = sm_metrics.broker_ingest_counter.count + EXCLUDED.count",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("connection", brokerConnectionId)
                        .addValue("reason", reason.wire()).addValue("day", day).addValue("count", n)));
    }
}
