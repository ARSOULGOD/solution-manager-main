package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.api.agent.AgentSessionFacts;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link AgentSessionFacts} over {@code sm_data.agent_session} and the agent's one ACTIVE lease (plan 015 T4.3). Read-only; every
 * statement is bound by tenant and binds its parameters (I5). It selects no column that holds a secret or a digest (I6).
 */
public final class JdbcAgentSessionFacts implements AgentSessionFacts {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final Duration liveWindow;

    public JdbcAgentSessionFacts(NamedParameterJdbcTemplate jdbc, Clock clock, Duration liveWindow) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.liveWindow = Objects.requireNonNull(liveWindow, "liveWindow");
    }

    @Override
    public Map<UUID, Session> sessionsOf(UUID tenantId, Collection<UUID> agentIds) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(agentIds, "agentIds");
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        Timestamp now = Timestamp.from(clock.instant());
        Map<UUID, Session> result = new LinkedHashMap<>();
        jdbc.query("SELECT s.agent_id, s.agent_version, s.last_seen_at, s.closed_at, l.expires_at AS lease_expires_at"
                        + " FROM sm_data.agent_session s LEFT JOIN sm_data.agent_lease l ON l.tenant_id = s.tenant_id"
                        + " AND l.agent_id = s.agent_id AND l.state = 'ACTIVE' AND l.expires_at > :now"
                        + " WHERE s.tenant_id = :tenant AND s.agent_id IN (:agents)",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("agents", agentIds).addValue("now", now),
                (rs, row) -> {
                    java.time.Instant seen = rs.getTimestamp("last_seen_at").toInstant();
                    boolean open = rs.getTimestamp("closed_at") == null
                            && seen.isAfter(clock.instant().minus(liveWindow));
                    Timestamp lease = rs.getTimestamp("lease_expires_at");
                    result.put(rs.getObject("agent_id", UUID.class), new Session(open, seen, rs.getString("agent_version"),
                            lease == null ? null : lease.toInstant()));
                    return null;
                });
        return result;
    }
}
