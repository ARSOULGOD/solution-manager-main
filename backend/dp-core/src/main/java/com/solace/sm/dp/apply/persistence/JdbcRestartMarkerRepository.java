package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.metrics.ingest.IngestRows;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_metrics.restart_marker}: the LATEST restart token observed on a placement (M-Q9 (d')). It is a record of
 * the latest observation and the seed for a series' previous token after a boot. It is never the base a sample is
 * compared with while the process is up: each series is compared with the token seen with ITS OWN previous sample.
 */
public final class JdbcRestartMarkerRepository implements IngestRows.Markers {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRestartMarkerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> token(UUID tenantId, UUID placementId) {
        return jdbc.queryForList("SELECT token FROM sm_metrics.restart_marker WHERE tenant_id = :tenant"
                        + " AND placement_id = :placement",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("placement", placementId),
                String.class).stream().findFirst();
    }

    @Override
    public void observe(UUID tenantId, UUID placementId, String token, Instant at) {
        Sql.write(() -> jdbc.update("INSERT INTO sm_metrics.restart_marker (tenant_id, placement_id, token, observed_at)"
                + " VALUES (:tenant, :placement, :token, :at) ON CONFLICT (tenant_id, placement_id)"
                + " DO UPDATE SET token = EXCLUDED.token, observed_at = EXCLUDED.observed_at",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("placement", placementId)
                        .addValue("token", token).addValue("at", Sql.at(at))));
    }
}
