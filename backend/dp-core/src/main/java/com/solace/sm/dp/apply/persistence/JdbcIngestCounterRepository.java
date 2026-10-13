package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.metrics.ingest.IngestRows;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_metrics.ingest_counter}: M-Q21's per-placement, per-reason, per-UTC-day drop counters, kept EXACTLY as
 * ruled (placement NOT NULL). A drop with no single placement is a different key and is plan 011 P11-5.1's
 * ({@code OR-107}, OQ-20 (b)); nothing here presumes its shape.
 *
 * <p>An upsert that ADDS: the runtime role holds {@code UPDATE (count)} and nothing else on the table, so the key and
 * the day can never be rewritten.
 */
public final class JdbcIngestCounterRepository implements IngestRows.Counters {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcIngestCounterRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(UUID tenantId, UUID placementId, CollectorReason reason, LocalDate day, long count) {
        if (count <= 0) {
            throw new IllegalArgumentException("a counter is only ever added to, by a positive amount");
        }
        Sql.write(() -> jdbc.update("INSERT INTO sm_metrics.ingest_counter (tenant_id, placement_id, reason, day, count)"
                + " VALUES (:tenant, :placement, :reason, :day, :count)"
                + " ON CONFLICT (tenant_id, placement_id, reason, day)"
                + " DO UPDATE SET count = sm_metrics.ingest_counter.count + EXCLUDED.count",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("placement", placementId)
                        .addValue("reason", reason.wire()).addValue("day", day).addValue("count", count)));
    }
}
