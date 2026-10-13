package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.metrics.ingest.IngestRows;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Reads what ingest is allowed to believe about a batch (plan 011 P11-3.1 step 1): the LIVE placement of THIS tenant,
 * its instance, and the instance's declared package version's metric definitions. The batch contributes only the two
 * ids it names; everything else is an SM row (the I1 check). Read-only, bound, tenant-first (I5).
 */
public final class JdbcIngestContextRepository implements IngestRows.Placements {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcIngestContextRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IngestRows.PlacementContext> resolve(UUID tenantId, UUID placementId) {
        MapSqlParameterSource key = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("placement", placementId);
        record Head(UUID instanceId, UUID solutionId, String version, String mode) {
        }
        Optional<Head> head = jdbc.query("SELECT p.instance_id, i.catalog_solution_id, i.declared_package_version,"
                        + " i.acquisition_mode FROM sm_data.placement p JOIN sm_data.instance_state i"
                        + " ON i.tenant_id = p.tenant_id AND i.instance_id = p.instance_id"
                        + " WHERE p.tenant_id = :tenant AND p.placement_id = :placement AND p.retired_at IS NULL",
                key, (rs, i) -> new Head(rs.getObject("instance_id", UUID.class),
                        rs.getObject("catalog_solution_id", UUID.class), rs.getString("declared_package_version"),
                        rs.getString("acquisition_mode"))).stream().findFirst();
        if (head.isEmpty()) {
            return Optional.empty();
        }
        MapSqlParameterSource version = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("solution", head.get().solutionId()).addValue("version", head.get().version());
        Map<String, IngestRows.MetricDef> definitions = new HashMap<>();
        jdbc.query("SELECT metric_name, component_type, metric_type, unit, interval_seconds"
                        + " FROM sm_data.metric_definition WHERE tenant_id = :tenant AND catalog_solution_id = :solution"
                        + " AND package_version = :version",
                version, rs -> {
                    int interval = rs.getInt("interval_seconds");
                    Integer nullable = rs.wasNull() ? null : interval;
                    definitions.put(rs.getString("metric_name"), new IngestRows.MetricDef(rs.getString("metric_name"),
                            rs.getString("component_type"), rs.getString("metric_type"), rs.getString("unit"),
                            nullable));
                });
        // W2-05: a signal with no port cannot be fetched in direct mode, and the mere presence of one makes ingest
        // stop inferring resets. The schema now requires the port; a row from an older copy that lacks it is ignored
        // here, so a portless signal can never yield DECLARED.
        Set<String> signals = new HashSet<>(jdbc.queryForList("SELECT component_type FROM sm_data.metric_restart_signal"
                + " WHERE tenant_id = :tenant AND catalog_solution_id = :solution AND package_version = :version"
                + " AND jsonb_typeof(source -> 'port') = 'number'",
                version, String.class));
        return Optional.of(new IngestRows.PlacementContext(tenantId, head.get().instanceId(), placementId,
                head.get().solutionId(), head.get().version(), head.get().mode(), definitions, signals));
    }
}
