package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.agw.AgentLogRetentionSource;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.logs.PlacementLogRetentionRows;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_data.placement_log_retention} over the runtime credential (plan 016 task 9), and the stored caps the gateway reads
 * when an agent session opens ({@link AgentLogRetentionSource}).
 *
 * <p>{@code I5}: every statement binds its parameters and names {@code tenant_id}. The upsert writes only the two columns the
 * runtime role may UPDATE (V053); the key is never re-pointed. A session's caps are selected by the tenant THE SESSION ROW
 * names, never by anything the gateway supplied.
 */
public final class JdbcPlacementLogRetentionRepository implements PlacementLogRetentionRows, AgentLogRetentionSource {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPlacementLogRetentionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean isLiveAgentPlacement(UUID tenantId, UUID placementId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data.placement p"
                + " JOIN sm_data.target_connection tc ON tc.tenant_id = p.tenant_id AND tc.target_id = p.target_id"
                + " WHERE p.tenant_id = :tenant AND p.placement_id = :placement AND p.retired_at IS NULL"
                + " AND tc.transport = 'agent' AND tc.retired_at IS NULL)", key(tenantId, placementId), Boolean.class));
    }

    @Override
    public void upsert(UUID tenantId, UUID placementId, long maxBytes, Instant at) {
        Sql.write(() -> jdbc.update("INSERT INTO sm_data.placement_log_retention (tenant_id, placement_id, log_max_bytes, updated_at)"
                + " VALUES (:tenant, :placement, :max, :at) ON CONFLICT (tenant_id, placement_id)"
                + " DO UPDATE SET log_max_bytes = EXCLUDED.log_max_bytes, updated_at = EXCLUDED.updated_at",
                key(tenantId, placementId).addValue("max", maxBytes).addValue("at", Sql.at(at))));
    }

    @Override
    public Optional<Long> find(UUID tenantId, UUID placementId) {
        return jdbc.query("SELECT log_max_bytes FROM sm_data.placement_log_retention"
                + " WHERE tenant_id = :tenant AND placement_id = :placement", key(tenantId, placementId),
                (rs, n) -> rs.getLong(1)).stream().findFirst();
    }

    @Override
    public Map<UUID, Long> capsFor(AgentSessionRef ref) {
        Map<UUID, Long> caps = new HashMap<>();
        // the tenant comes from the OPEN session row of this agent and epoch; the agent owns a placement through its target
        jdbc.query("SELECT r.placement_id, r.log_max_bytes FROM sm_data.agent_session s"
                + " JOIN sm_data.target_connection tc ON tc.tenant_id = s.tenant_id AND tc.agent_id = s.agent_id"
                + " AND tc.transport = 'agent' AND tc.retired_at IS NULL"
                + " JOIN sm_data.placement p ON p.tenant_id = tc.tenant_id AND p.target_id = tc.target_id AND p.retired_at IS NULL"
                + " JOIN sm_data.placement_log_retention r ON r.tenant_id = p.tenant_id AND r.placement_id = p.placement_id"
                + " WHERE s.agent_id = :agent AND s.fencing_epoch = :epoch AND s.closed_at IS NULL",
                new MapSqlParameterSource().addValue("agent", ref.agentId()).addValue("epoch", ref.fencingEpoch()),
                rs -> {
                    caps.put(rs.getObject(1, UUID.class), rs.getLong(2));
                });
        return Map.copyOf(caps);
    }

    private static MapSqlParameterSource key(UUID tenantId, UUID placementId) {
        return new MapSqlParameterSource().addValue("tenant", tenantId).addValue("placement", placementId);
    }
}
