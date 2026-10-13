package com.solace.sm.dp.apply.persistence.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The data plane's ONE cross-tenant read for the agent staleness job (plan 015 T4.6, {@code OR-140}, which amends
 * {@code OR-44}/{@code OR-49} for the two agent jobs only). Its own class, with this one method, so that
 * {@code TenantEnumerationBoundaryTest} allow-lists exactly this statement and not a general repository.
 */
final class AgentStalenessTenants {

    private AgentStalenessTenants() {
    }

    /** Tenants with at least one probed agent placement silent since before {@code cutoff} and not already {@code unreachable}. */
    static List<UUID> tenants(NamedParameterJdbcTemplate jdbc, Instant cutoff) {
        return jdbc.queryForList("SELECT DISTINCT p.tenant_id FROM sm_data.placement p"
                        + " JOIN sm_data.target_connection t ON t.tenant_id = p.tenant_id AND t.target_id = p.target_id"
                        + " AND t.transport = 'agent'"
                        + " JOIN (SELECT tenant_id, placement_id, max(updated_at) AS last_report"
                        + "        FROM sm_data.agent_report_watermark GROUP BY tenant_id, placement_id) w"
                        + " ON w.tenant_id = p.tenant_id AND w.placement_id = p.placement_id"
                        + " LEFT JOIN sm_data.placement_health h ON h.tenant_id = p.tenant_id AND h.placement_id = p.placement_id"
                        + " WHERE p.retired_at IS NULL AND p.health_kind IS NOT NULL AND w.last_report < :cutoff"
                        + " AND (h.state IS NULL OR h.state <> 'unreachable') ORDER BY p.tenant_id",
                new MapSqlParameterSource().addValue("cutoff", AgentSql.at(cutoff)), UUID.class);
    }
}
