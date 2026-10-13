package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.AgentTelemetryBatch;
import com.solace.sm.dp.api.agw.GatewayCode;
import java.time.Clock;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code sm_data.agent_telemetry_watermark} (plan 015 T2.1b): the per-stream batch watermark that makes a redelivered batch a
 * no-op. The organization is the identity's ({@link AgentAuthority}); a label on a sample is agent-supplied and is never read
 * here ({@code I5}).
 *
 * <p>This is a PERSISTENCE HELPER, not the {@code AgentTelemetryPort} bean: it forwards no sample, and acknowledging a batch it
 * did not forward would lose data (it has no {@code ingest} method for that reason; the contract kit runs over the bridge). T3.4's {@code AgentTelemetryBridge} owns that port, maps samples to the built
 * {@code TelemetryIngestPort} and advances the watermark ({@link #acknowledge}) in the same transaction as, or after, {@code Committed}.
 * {@link #admit} and {@link #leased} are the bridge's and the declarations service's reads: every id they return is derived from the
 * session and its lease, never from a value the agent sent ({@code I5}).
 */
public final class JdbcAgentTelemetryWatermarkRepository {

    private static final Pattern STREAM = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AgentAuthority authority;

    public JdbcAgentTelemetryWatermarkRepository(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
            AgentAuthority authority) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.authority = authority;
    }

    /**
     * What a telemetry call may act on, all derived server-side from the session and its ACTIVE lease. {@code clockOffsetMs} is the
     * DP clock minus the agent's at the last open or heartbeat, or {@code null} when not measured (OR-134).
     */
    public record Scope(UUID tenantId, UUID agentId, UUID placementId, Long clockOffsetMs, OptionalLong watermark) {
    }

    /** The placements an ACTIVE lease names, with the organization the identity belongs to. */
    public record Leased(UUID tenantId, UUID agentId, List<UUID> placementIds) {
    }

    /**
     * The agents enrolled on the target of any placement of the instance, retired placements included (so a placement that just left
     * the declared set is told to clear it). Plan 015 W4 lane D: found from the PLACEMENT'S TARGET, which is an agent target, and not
     * from an agent's lease, because a lease's {@code placement_ids} is a snapshot taken at issue and a placement added since is in
     * no lease yet. Scoped to the organization of the data plane's own change (never an agent-supplied value: I5). Writes nothing.
     */
    public List<UUID> agentsOnTargetsOf(UUID tenantId, UUID instanceId) {
        return jdbc.queryForList("SELECT DISTINCT i.agent_id FROM sm_data.placement p JOIN sm_data.agent_identity i"
                + " ON i.tenant_id = p.tenant_id AND i.target_id = p.target_id"
                + " WHERE p.tenant_id = :tenant AND p.instance_id = :instance AND i.revoked_at IS NULL",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId), UUID.class);
    }

    /**
     * Admission for one batch: the session is the current open owner, the lease is active, and the stream names a placement the lease
     * holds ({@code BINDING_NOT_IN_LEASE} otherwise, a malformed stream id included). The stream id is the agent's binding, which is a
     * placement id; it is compared with the lease, never trusted to find a tenant. Writes nothing.
     */
    public Scope admit(AgentSessionRef ref, String streamId) {
        return authority.underSession(ref, standing -> {
            AgentAuthority.StoredLease lease = authority.requireLease(standing);
            UUID placement = placementOf(streamId);
            if (placement == null || !lease.placementIds().contains(placement)) {
                throw AgentSql.refused(GatewayCode.BINDING_NOT_IN_LEASE, "the stream does not name a placement of the lease");
            }
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenant", standing.tenantId())
                    .addValue("agent", standing.agentId()).addValue("epoch", ref.fencingEpoch()).addValue("stream", placement.toString());
            List<Long> offset = jdbc.query("SELECT clock_offset_ms FROM sm_data.agent_session WHERE tenant_id = :tenant"
                    + " AND agent_id = :agent AND fencing_epoch = :epoch", p, (rs, n) -> {
                        long value = rs.getLong(1);
                        return rs.wasNull() ? null : value;
                    });
            List<Long> mark = jdbc.queryForList("SELECT last_batch_seq FROM sm_data.agent_telemetry_watermark WHERE tenant_id = :tenant"
                    + " AND agent_id = :agent AND stream_id = :stream", p, Long.class);
            return new Scope(standing.tenantId(), standing.agentId(), placement, offset.isEmpty() ? null : offset.get(0),
                    mark.isEmpty() ? OptionalLong.empty() : OptionalLong.of(mark.get(0)));
        });
    }

    /**
     * As {@link #admit} without a stream: the placements the ACTIVE lease names whose INSTANCE is {@code active} (declarations; the pull
     * path plans only active instances too, so a pending, abandoned or retiring instance declares nothing). Writes nothing.
     */
    public Leased leased(AgentSessionRef ref) {
        return authority.underSession(ref, standing -> {
            List<UUID> named = authority.requireLease(standing).placementIds();
            if (named.isEmpty()) {
                return new Leased(standing.tenantId(), standing.agentId(), List.of());
            }
            List<UUID> active = jdbc.queryForList("SELECT p.placement_id FROM sm_data.placement p JOIN sm_data.instance_state i"
                    + " ON i.tenant_id = p.tenant_id AND i.instance_id = p.instance_id WHERE p.tenant_id = :tenant"
                    + " AND p.placement_id IN (:placements) AND i.lifecycle = 'active'", new MapSqlParameterSource()
                    .addValue("tenant", standing.tenantId()).addValue("placements", named), UUID.class);
            return new Leased(standing.tenantId(), standing.agentId(), named.stream().filter(active::contains).toList());
        });
    }

    /**
     * Raises the stream's watermark after its batch is Committed (or permanently rejected and counted), re-checking the session in
     * the same transaction: a stream superseded meanwhile is refused {@code STALE_EPOCH} and the batch is redelivered, which the
     * built ingest port absorbs as an exact duplicate. Never called before the outcome is durable.
     */
    public long acknowledge(AgentSessionRef ref, String streamId, long batchSeq) {
        UUID placement = placementOf(streamId);
        if (placement == null) {
            throw AgentSql.refused(GatewayCode.BINDING_NOT_IN_LEASE, "the stream does not name a placement");
        }
        return authority.underSession(ref, standing -> advance(standing, placement.toString(), batchSeq));
    }

    private static UUID placementOf(String streamId) {
        if (streamId == null || !STREAM.matcher(streamId).matches() || streamId.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(streamId);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** Raises the stream's watermark to {@code batchSeq} if it is higher; returns the watermark either way. */
    public long advance(AgentAuthority.Standing standing, String streamId, long batchSeq) {
        if (streamId == null || !STREAM.matcher(streamId).matches() || batchSeq < 0) {
            throw new IllegalArgumentException("a telemetry stream id or batch sequence is malformed");
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenant", standing.tenantId())
                .addValue("agent", standing.agentId()).addValue("stream", streamId).addValue("seq", batchSeq)
                .addValue("now", AgentSql.at(clock.instant()));
        AgentSql.write(() -> jdbc.update("INSERT INTO sm_data.agent_telemetry_watermark (tenant_id, agent_id, stream_id,"
                + " last_batch_seq, updated_at) VALUES (:tenant, :agent, :stream, :seq, :now)"
                + " ON CONFLICT (tenant_id, agent_id, stream_id) DO UPDATE SET last_batch_seq = EXCLUDED.last_batch_seq,"
                + " updated_at = EXCLUDED.updated_at WHERE agent_telemetry_watermark.last_batch_seq < EXCLUDED.last_batch_seq", p));
        return jdbc.queryForObject("SELECT last_batch_seq FROM sm_data.agent_telemetry_watermark WHERE tenant_id = :tenant"
                + " AND agent_id = :agent AND stream_id = :stream", p, Long.class);
    }
}
