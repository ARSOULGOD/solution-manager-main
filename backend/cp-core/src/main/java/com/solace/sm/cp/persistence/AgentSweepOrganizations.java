package com.solace.sm.cp.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The control plane's ONE cross-organization read for the enrolment sweep job (plan 015 T4.6, {@code OR-140}, which amends
 * {@code OR-44}/{@code OR-49} for the two agent jobs only). Its own class, with this one method, so that
 * {@code TenantEnumerationBoundaryTest} allow-lists exactly this statement and not a general repository: a future method of
 * {@code JdbcAgentRegistry} that enumerates organizations is a red build.
 */
final class AgentSweepOrganizations {

    private AgentSweepOrganizations() {
    }

    /** Organizations with a consumed registration whose live agent target's token expired before {@code expiredBefore}. */
    static List<UUID> organizations(NamedParameterJdbcTemplate jdbc, Instant expiredBefore) {
        return jdbc.queryForList("SELECT DISTINCT r.organization_id FROM sm_control.agent_registration r JOIN sm_control.target t"
                        + " ON t.organization_id = r.organization_id AND t.target_id = r.target_id"
                        + " WHERE r.enrollment_used_at IS NOT NULL AND t.retired_at IS NULL"
                        + " AND t.transport = 'agent' AND r.expires_at < :cutoff ORDER BY r.organization_id",
                new MapSqlParameterSource("cutoff", Timestamp.from(expiredBefore)), UUID.class);
    }
}
