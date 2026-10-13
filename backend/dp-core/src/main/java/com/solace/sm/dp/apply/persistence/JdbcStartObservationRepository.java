package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.health.StartObservationRows;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_data.placement_start_observation} (OR-112 (1), V018): the container start the data plane last observed on a
 * placement, which the config ladder and the metrics active list read as the evidence that an UNGATED apply's restart
 * happened. One row per placement, moved only when the observed start differs, so a poll that sees the same container
 * again writes nothing. Every value is bound; every statement names the tenant (I5).
 */
public final class JdbcStartObservationRepository implements StartObservationRows {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcStartObservationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockInstance(UUID tenantId, UUID instanceId) {
        jdbc.queryForList("SELECT 1 FROM sm_data.instance_state WHERE tenant_id = :t AND instance_id = :i FOR UPDATE",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("i", instanceId), Integer.class);
    }

    @Override
    public boolean record(UUID tenantId, UUID placementId, Instant startedAt, String processRef, Instant observedAt) {
        // ON CONFLICT ... DO UPDATE ... WHERE <differs>: a conflicting row that does not differ is left alone and counts
        // as no row, so the answer is exactly "the stored observation changed"
        int written = Sql.write(() -> jdbc.update("INSERT INTO sm_data.placement_start_observation"
                + " (tenant_id, placement_id, started_at, process_ref, observed_at)"
                + " VALUES (:t, :p, :started, :ref, :observed) ON CONFLICT (tenant_id, placement_id) DO UPDATE"
                + " SET started_at = EXCLUDED.started_at, process_ref = EXCLUDED.process_ref,"
                + "     observed_at = EXCLUDED.observed_at"
                + " WHERE (sm_data.placement_start_observation.started_at, sm_data.placement_start_observation.process_ref)"
                + "     IS DISTINCT FROM (EXCLUDED.started_at, EXCLUDED.process_ref)",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("p", placementId)
                        .addValue("started", Sql.at(startedAt)).addValue("ref", processRef)
                        .addValue("observed", Sql.at(observedAt))));
        return written > 0;
    }
}
