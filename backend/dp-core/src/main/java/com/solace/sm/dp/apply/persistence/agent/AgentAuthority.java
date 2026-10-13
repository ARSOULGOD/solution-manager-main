package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentIdentity;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.GatewayCode;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one standing check every agent-bound call makes first (plan 015 T2.1b, {@code I5}): an {@link AgentSessionRef} carries
 * no tenant ({@code AGW-D3}), so the organization, environment and target are DERIVED here, from the identity the session
 * belongs to — never from a caller's value — and a session that is not the current, open owner of its agent is refused
 * {@code STALE_EPOCH} before any other read or write.
 *
 * <p><b>The second named unbound read.</b> {@link JdbcAgentIdentityRepository#resolveByThumbprint} resolves a certificate to
 * its identity once, at {@code claim}. Every later call carries only {@code (agentId, fencingEpoch)}, so this class reads
 * {@code agent_session JOIN agent_identity} by {@code agent_id} alone. That is sound for the same reason as the first:
 * {@code agent_identity.agent_id} is globally UNIQUE ({@code agent_identity_agent_id_global}), so the read can return the
 * rows of exactly one organization. {@code TenantEnumerationBoundaryTest} holds both reads to the classes named there.
 */
public final class AgentAuthority {

    /** What the identity and session say about an agent right now. Every id here is derived server-side. */
    public record Standing(UUID tenantId, UUID agentId, UUID environmentId, UUID targetId, String certificateThumbprint,
            boolean revoked, long agentEpoch, long fleetEpoch, List<AgentCapability> capabilities, boolean sessionClosed) {

        public AgentIdentity toIdentity() {
            return new AgentIdentity(agentId, certificateThumbprint, tenantId, environmentId, targetId, agentEpoch,
                    fleetEpoch, capabilities, revoked);
        }
    }

    /** The agent's lease as stored: never the nonce (only its digest is kept, {@code V028}). */
    public record StoredLease(UUID leaseId, java.time.Instant issuedAt, java.time.Instant expiresAt,
            List<UUID> placementIds, List<AgentCapability> capabilities, Map<AgentOperation, Duration> verbCeilings,
            Duration admissionMargin, long agentEpoch, long fleetEpoch, String nonceDigest) {

        boolean matches(Standing identity) {
            return agentEpoch == identity.agentEpoch() && fleetEpoch == identity.fleetEpoch()
                    && capabilities.equals(identity.capabilities());
        }

        boolean matches(AgentIdentity identity) {
            return agentEpoch == identity.agentRevocationEpoch() && fleetEpoch == identity.fleetRevocationEpoch()
                    && capabilities.equals(identity.capabilities());
        }
    }

    private static final String STANDING = "SELECT i.tenant_id, i.environment_id, i.target_id, i.certificate_thumbprint,"
            + " i.agent_epoch, i.fleet_epoch, i.capabilities, (i.revoked_at IS NOT NULL) AS revoked,"
            + " (s.closed_at IS NOT NULL) AS closed"
            + " FROM sm_data.agent_session s JOIN sm_data.agent_identity i"
            + " ON i.tenant_id = s.tenant_id AND i.agent_id = s.agent_id"
            + " WHERE s.agent_id = :agent AND s.fencing_epoch = :epoch FOR SHARE OF s, i";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate tx;

    public AgentAuthority(NamedParameterJdbcTemplate jdbc, Clock clock, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.tx = tx;
    }

    /**
     * Runs {@code body} in ONE transaction that begins with the standing check and holds {@code FOR SHARE} on the session and
     * identity rows to its end (plan 015 T2.1b fix round, finding 1). A {@code claim}'s upsert and a revocation both take a row lock
     * that conflicts with it, so neither can land between the check and the write: they wait for this transaction, and the NEXT
     * write by this (now superseded) stream is refused. Joins an enclosing transaction if there is one.
     */
    public <T> T underSession(AgentSessionRef ref, java.util.function.Function<Standing, T> body) {
        return tx.execute(status -> body.apply(requireSession(ref)));
    }

    /** As {@link #underSession}, with the audit path's weaker check (epoch holder, open or closed, identity standing not checked). */
    public <T> T underEpochHolder(AgentSessionRef ref, java.util.function.Function<Standing, T> body) {
        return tx.execute(status -> body.apply(requireEpochHolder(ref)));
    }

    /** The session is the current open owner AND the identity is in good standing; else a coded refusal. One statement. */
    public Standing requireSession(AgentSessionRef ref) {
        Standing standing = requireOwner(ref);
        if (standing.revoked()) {
            throw AgentSql.refused(GatewayCode.IDENTITY_REVOKED, "agent identity is revoked");
        }
        return standing;
    }

    /** The session is the current OPEN owner (identity standing not checked): the reconcile pass uses this. */
    public Standing requireOwner(AgentSessionRef ref) {
        Standing standing = requireEpochHolder(ref);
        if (standing.sessionClosed()) {
            throw AgentSql.refused(GatewayCode.STALE_EPOCH, "session is no longer current");
        }
        return standing;
    }

    /**
     * Only that this epoch is the one the agent's session row holds, open or closed, and NOT that the identity is in good
     * standing: a revocation is the event that most needs a durable record, and it ends the session first, so the audit path
     * uses this and no other.
     */
    public Standing requireEpochHolder(AgentSessionRef ref) {
        List<Standing> rows = jdbc.query(STANDING, new MapSqlParameterSource().addValue("agent", ref.agentId())
                .addValue("epoch", ref.fencingEpoch()), (rs, n) -> new Standing(rs.getObject("tenant_id", UUID.class),
                ref.agentId(), rs.getObject("environment_id", UUID.class), rs.getObject("target_id", UUID.class),
                rs.getString("certificate_thumbprint"), rs.getBoolean("revoked"), rs.getLong("agent_epoch"),
                rs.getLong("fleet_epoch"), AgentSql.capabilities(rs, "capabilities"), rs.getBoolean("closed")));
        if (rows.size() != 1) {
            throw AgentSql.refused(GatewayCode.STALE_EPOCH, "session is no longer current");
        }
        return rows.get(0);
    }

    /** The ACTIVE, unexpired lease that still matches the identity's epochs and capabilities, or empty. */
    public Optional<StoredLease> activeLease(Standing standing) {
        return jdbc.query("SELECT lease_id, issued_at, expires_at, placement_ids, capabilities, verb_ceilings::text AS ceilings,"
                        + " admission_margin_ms, agent_epoch, fleet_epoch, renewal_nonce_digest FROM sm_data.agent_lease"
                        + " WHERE tenant_id = :tenant AND agent_id = :agent AND state = 'ACTIVE' AND expires_at > :now",
                new MapSqlParameterSource().addValue("tenant", standing.tenantId()).addValue("agent", standing.agentId())
                        .addValue("now", AgentSql.at(clock.instant())), (rs, n) -> lease(rs)).stream()
                .filter(lease -> lease.matches(standing)).findFirst();
    }

    /** As {@link #activeLease}, or {@code LEASE_RENEW_REQUIRED}. */
    public StoredLease requireLease(Standing standing) {
        return activeLease(standing).orElseThrow(
                () -> AgentSql.refused(GatewayCode.LEASE_RENEW_REQUIRED, "lease is not active"));
    }

    /** True if the agent has EVER held a lease (any state). A lease row is never deleted while it is ACTIVE or recent (V029). */
    boolean anyLeaseRow(UUID tenantId, UUID agentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data.agent_lease WHERE tenant_id = :tenant"
                + " AND agent_id = :agent)", new MapSqlParameterSource().addValue("tenant", tenantId).addValue("agent", agentId),
                Boolean.class));
    }

    /** The ACTIVE row whether or not it has expired or still matches (the lease repository's own view). */
    Optional<StoredLease> activeRow(UUID tenantId, UUID agentId) {
        return jdbc.query("SELECT lease_id, issued_at, expires_at, placement_ids, capabilities, verb_ceilings::text AS ceilings,"
                        + " admission_margin_ms, agent_epoch, fleet_epoch, renewal_nonce_digest FROM sm_data.agent_lease"
                        + " WHERE tenant_id = :tenant AND agent_id = :agent AND state = 'ACTIVE'",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("agent", agentId),
                (rs, n) -> lease(rs)).stream().findFirst();
    }

    private static StoredLease lease(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredLease(rs.getObject("lease_id", UUID.class), AgentSql.instant(rs, "issued_at"),
                AgentSql.instant(rs, "expires_at"), AgentSql.uuids(rs, "placement_ids"),
                AgentSql.capabilities(rs, "capabilities"), ceilings(rs.getString("ceilings")),
                Duration.ofMillis(rs.getLong("admission_margin_ms")), rs.getLong("agent_epoch"),
                rs.getLong("fleet_epoch"), rs.getString("renewal_nonce_digest"));
    }

    private static Map<AgentOperation, Duration> ceilings(String json) {
        Map<AgentOperation, Duration> result = new LinkedHashMap<>();
        if (json == null) {
            return result;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([A-Z_]+)\"\\s*:\\s*(\\d+)").matcher(json);
        while (m.find()) {
            try {
                result.put(AgentOperation.valueOf(m.group(1)), Duration.ofMillis(Long.parseLong(m.group(2))));
            } catch (IllegalArgumentException ignored) {
                // an operation this build does not know is a ceiling it cannot apply; the lease is read, not trusted blindly
            }
        }
        return Map.copyOf(result);
    }
}
