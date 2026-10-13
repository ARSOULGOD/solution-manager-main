package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.api.agw.AgentAuditPort;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.GatewayCode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_data.agent_event} (plan 015 T2.1b, {@code AGW-D29}): append-only (the runtime role holds SELECT and INSERT only), one
 * row per request id. Idempotent: a second record under the same request id is a no-op ({@code agent_event_request_once}).
 *
 * <p>Checks that the session is still the current owner and NOT that the identity is in good standing: a revocation is the
 * event that most needs a durable record. A refusal before any identity resolved has no tenant and is a metric, not a row.
 *
 * <p>The row carries the gateway-minted origin's correlation id and address. The FIRST writer of a request id wins: a second
 * record under the same id for a DIFFERENT event is a no-op, not an overwrite (the kit pins this). {@code subject} is accepted and DROPPED: the table has no column for it and it may be agent-influenced text. No payload, token
 * or digest is ever recorded.
 */
public final class JdbcAgentAuditRepository implements AgentAuditPort {

    private final NamedParameterJdbcTemplate jdbc;
    private final AgentAuthority authority;

    public JdbcAgentAuditRepository(NamedParameterJdbcTemplate jdbc, AgentAuthority authority) {
        this.jdbc = jdbc;
        this.authority = authority;
    }

    @Override
    public void record(String requestId, AgentSessionRef session, AgentRequestOrigin origin, String eventType,
            GatewayCode code, String subject, Instant at) {
        String request = requestId.length() > 128 ? requestId.substring(0, 128) : requestId;
        authority.underEpochHolder(session, standing -> AgentSql.write(() -> jdbc.update("INSERT INTO sm_data.agent_event"
                + " (tenant_id, event_id, agent_id, request_id, code, actor_kind, actor_id, correlation_id, client_address,"
                + " detail_code, occurred_at) VALUES (:tenant, :event, :agent, :request, :code, 'AGENT', :agent, :correlation,"
                + " :address, :detail, :at) ON CONFLICT (tenant_id, request_id) DO NOTHING", new MapSqlParameterSource()
                .addValue("tenant", standing.tenantId()).addValue("event", UUID.randomUUID())
                .addValue("agent", standing.agentId()).addValue("request", request).addValue("code", eventType)
                .addValue("correlation", origin.correlationId()).addValue("address", origin.clientAddress())
                .addValue("detail", code == null ? null : code.wire()).addValue("at", AgentSql.at(at)))));
    }
}
