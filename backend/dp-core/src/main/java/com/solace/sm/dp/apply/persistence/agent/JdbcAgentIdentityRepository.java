package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentIdentity;
import com.solace.sm.dp.api.agw.AgentIdentityPort;
import com.solace.sm.dp.api.agw.AgentRegistrationSource;
import com.solace.sm.dp.api.agw.AgentRegistrationState;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.IdentityReconciliation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code sm_data.agent_identity} (plan 015 T2.1b, {@code BE-D68}): the agent's identity as the data plane holds it, and the
 * authority sweep against the control plane's registration.
 *
 * <p>{@link #resolveByThumbprint} is the ONE named unbound read ({@code I5}): a certificate thumbprint is globally UNIQUE
 * ({@code agent_identity_thumbprint_global}), so it names exactly one organization's identity, and the tenant is whatever that
 * row says. It is held to {@link JdbcAgentSessionRepository} and {@link JdbcAgentEnrollmentRepository} by
 * {@code TenantEnumerationBoundaryTest}. Revocation is sticky in the database ({@code agent_identity_revocation}); this class
 * never tries to undo it.
 *
 * <p>What it deliberately does not do: re-issue a lease on a permission change. A lease is stored as the digest of its renewal
 * nonce only ({@code V028}), so the data plane cannot present the previous nonce to the issuer; a changed registration answers
 * {@code RENEWAL_REQUIRED} and the agent's renewal (which carries its own nonce) re-issues under the new authority.
 */
public final class JdbcAgentIdentityRepository implements AgentIdentityPort {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcAgentIdentityRepository.class);

    private static final String IDENTITY = "SELECT tenant_id, agent_id, environment_id, target_id, certificate_thumbprint,"
            + " agent_epoch, fleet_epoch, capabilities, (revoked_at IS NOT NULL) AS revoked FROM sm_data.agent_identity";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AgentAuthority authority;
    private final Supplier<AgentRegistrationSource> registrations;

    public JdbcAgentIdentityRepository(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
            AgentAuthority authority, Supplier<AgentRegistrationSource> registrations) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.authority = authority;
        this.registrations = registrations;
    }

    @Override
    public Optional<AgentIdentity> resolve(String certificateThumbprint) {
        return resolveByThumbprint(certificateThumbprint);
    }

    /** The one named unbound read (see the class comment). */
    public Optional<AgentIdentity> resolveByThumbprint(String certificateThumbprint) {
        if (certificateThumbprint == null || !certificateThumbprint.matches("[0-9a-f]{64}")) {
            return Optional.empty();
        }
        return jdbc.query(IDENTITY + " WHERE certificate_thumbprint = :thumbprint",
                new MapSqlParameterSource("thumbprint", certificateThumbprint), (rs, n) -> row(rs)).stream().findFirst();
    }

    private static AgentIdentity row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AgentIdentity(rs.getObject("agent_id", UUID.class), rs.getString("certificate_thumbprint"),
                rs.getObject("tenant_id", UUID.class), rs.getObject("environment_id", UUID.class),
                rs.getObject("target_id", UUID.class), rs.getLong("agent_epoch"), rs.getLong("fleet_epoch"),
                AgentSql.capabilities(rs, "capabilities"), rs.getBoolean("revoked"));
    }

    // ---- the control plane's registration --------------------------------------------------------------------------

    /**
     * Brings the data plane's identity row in line with the control plane and returns the result. A revocation is sticky: an
     * identity the data plane has marked revoked stays so even if the control plane flips back (the remedy is a fresh
     * registration). An agent the control plane no longer knows is treated as revoked.
     *
     * @throws GatewayPortException {@code INTERNAL_UNAVAILABLE} when the control plane cannot be read; nothing changes
     */
    AgentIdentity synced(AgentIdentity stored) {
        return sync(stored, fetch(List.of(stored.agentId())).get(stored.agentId()));
    }

    /**
     * The agent-target retire gate's revocation check (plan 015 W4 integration, review F1): revoked when the data plane already holds
     * the revocation, or when the control plane's registration says so, in which case it is applied here exactly as a reconciliation
     * applies it (identity revoked, lease and session closed), inside the caller's transaction. {@code revokeAgent} writes only the
     * control plane, and an offline agent has no session to reconcile, so without this its target could never be retired. The tenant
     * and agent come from the data plane's own connection record, never from a request (I5). A control plane that cannot be read
     * answers "not revoked": the retire is refused and can be retried (fail closed).
     */
    public boolean revokedApplyingTheControlPlane(UUID tenantId, UUID agentId) {
        Optional<AgentIdentity> stored = jdbc.query(IDENTITY + " WHERE tenant_id = :tenant AND agent_id = :agent",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("agent", agentId), (rs, n) -> row(rs))
                .stream().findFirst();
        if (stored.isEmpty()) {
            return false;
        }
        if (stored.get().revoked()) {
            return true;
        }
        try {
            return synced(stored.get()).revoked();
        } catch (RuntimeException e) {
            LOG.warn("retire gate: the control-plane registration could not be read (the retire is refused): {}", e.getClass().getName());
            return false;
        }
    }

    private Map<UUID, AgentRegistrationState> fetch(java.util.Collection<UUID> agentIds) {
        try {
            return registrations.get().current(agentIds);
        } catch (GatewayPortException e) {
            throw e;
        } catch (RuntimeException e) {
            throw AgentSql.refused(GatewayCode.INTERNAL_UNAVAILABLE, "control-plane registration is unavailable");
        }
    }

    private AgentIdentity sync(AgentIdentity stored, AgentRegistrationState cp) {
        if (stored.revoked()) {
            return stored;
        }
        if (cp == null || !cp.active()) {
            long agentEpoch = cp == null ? stored.agentRevocationEpoch() : Math.max(stored.agentRevocationEpoch(), cp.agentRevocationEpoch());
            long fleetEpoch = cp == null ? stored.fleetRevocationEpoch() : Math.max(stored.fleetRevocationEpoch(), cp.fleetRevocationEpoch());
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenant", stored.tenantId())
                    .addValue("agent", stored.agentId()).addValue("now", AgentSql.at(clock.instant()))
                    .addValue("agentEpoch", agentEpoch).addValue("fleetEpoch", fleetEpoch);
            tx.executeWithoutResult(status -> {
                AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_identity SET revoked_at = :now,"
                        + " agent_epoch = GREATEST(agent_epoch, :agentEpoch), fleet_epoch = GREATEST(fleet_epoch, :fleetEpoch)"
                        + " WHERE tenant_id = :tenant AND agent_id = :agent AND revoked_at IS NULL", p));
                // The runtime role cannot delete leases (least privilege); a REVOKED lease is not ACTIVE, so it admits nothing.
                jdbc.update("UPDATE sm_data.agent_lease SET state = 'REVOKED' WHERE tenant_id = :tenant AND agent_id = :agent"
                        + " AND state = 'ACTIVE'", p);
                jdbc.update("UPDATE sm_data.agent_session SET closed_at = :now WHERE tenant_id = :tenant AND agent_id = :agent"
                        + " AND closed_at IS NULL", p);
            });
            return new AgentIdentity(stored.agentId(), stored.certificateThumbprint(), stored.tenantId(),
                    stored.environmentId(), stored.targetId(), agentEpoch, fleetEpoch, stored.capabilities(), true);
        }
        // A registration's epochs only rise (the sticky revocation's cousin): a control-plane read that is BEHIND what the data
        // plane already holds (a stale replica) must not lower them, so a lease minted under the higher epoch keeps matching.
        long agentEpoch = Math.max(stored.agentRevocationEpoch(), cp.agentRevocationEpoch());
        long fleetEpoch = Math.max(stored.fleetRevocationEpoch(), cp.fleetRevocationEpoch());
        List<AgentCapability> caps = cp.capabilities().stream().sorted().toList();
        if (stored.capabilities().equals(caps) && stored.agentRevocationEpoch() == agentEpoch
                && stored.fleetRevocationEpoch() == fleetEpoch) {
            return stored;
        }
        AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_identity SET capabilities = CAST(:caps AS text[]),"
                + " agent_epoch = :agentEpoch, fleet_epoch = :fleetEpoch WHERE tenant_id = :tenant AND agent_id = :agent"
                + " AND revoked_at IS NULL", new MapSqlParameterSource().addValue("tenant", stored.tenantId())
                .addValue("agent", stored.agentId()).addValue("caps", AgentSql.capabilityArray(caps))
                .addValue("agentEpoch", agentEpoch).addValue("fleetEpoch", fleetEpoch)));
        return new AgentIdentity(stored.agentId(), stored.certificateThumbprint(), stored.tenantId(),
                stored.environmentId(), stored.targetId(), agentEpoch, fleetEpoch, caps, false);
    }

    @Override
    public List<IdentityReconciliation> reconcile(List<AgentSessionRef> sessions) {
        if (sessions.isEmpty()) {
            return List.of();
        }
        Set<UUID> agentIds = sessions.stream().map(AgentSessionRef::agentId).collect(Collectors.toSet());
        Map<UUID, AgentRegistrationState> cp = fetch(agentIds);      // ONE batched read serves every session
        List<IdentityReconciliation> results = new ArrayList<>();
        List<Held> held = new ArrayList<>();
        for (AgentSessionRef session : sessions) {
            AgentAuthority.Standing standing;
            try {
                standing = authority.requireEpochHolder(session);
            } catch (GatewayPortException stale) {
                results.add(new IdentityReconciliation(session, IdentityReconciliation.Status.STALE, null, 0, 0));
                continue;
            }
            if (standing.sessionClosed() && !standing.revoked()) {
                results.add(new IdentityReconciliation(session, IdentityReconciliation.Status.STALE, null, 0, 0));
                continue;
            }
            AgentIdentity identity;
            try {
                identity = sync(standing.toIdentity(), cp.get(session.agentId()));
            } catch (RuntimeException e) {
                // One agent's failed write must not abort the pass for every other session; the next pass retries.
                LOG.warn("identity sync failed (will retry): {}", e.getClass().getName());
                results.add(IdentityReconciliation.unchanged(session));
                continue;
            }
            if (identity.revoked()) {
                results.add(new IdentityReconciliation(session, IdentityReconciliation.Status.REVOKED, null,
                        identity.agentRevocationEpoch(), identity.fleetRevocationEpoch()));
                continue;
            }
            Optional<AgentAuthority.StoredLease> lease = authority.activeRow(identity.tenantId(), identity.agentId());
            boolean needsRenewal = lease.isPresent() && (!lease.get().matches(identity)
                    || !lease.get().expiresAt().isAfter(clock.instant()));
            if (!needsRenewal && lease.isPresent()) {
                held.add(new Held(results.size(), session, identity, lease.get()));     // judged below, against the live placements
            }
            results.add(needsRenewal ? renewalRequired(session, identity) : IdentityReconciliation.unchanged(session));
        }
        renewForPlacementChanges(results, held);
        return results;
    }

    private static IdentityReconciliation renewalRequired(AgentSessionRef session, AgentIdentity identity) {
        return new IdentityReconciliation(session, IdentityReconciliation.Status.RENEWAL_REQUIRED, null,
                identity.agentRevocationEpoch(), identity.fleetRevocationEpoch());
    }

    /** A session whose epochs, capabilities and expiry are fine, awaiting the placement-set judgement. */
    private record Held(int slot, AgentSessionRef session, AgentIdentity identity, AgentAuthority.StoredLease lease) {
    }

    /**
     * Plan 015 W4 lane D: a lease's {@code placement_ids} is a snapshot taken when it was minted, so a placement added to the agent's
     * target since (a confirmed discovery, a new instance) is in no lease and is never declared until the agent happens to renew. When
     * the live set the agent should hold (the one {@code JdbcAgentLeaseRepository} would sign: the target's unretired placements, at
     * most {@value JdbcAgentLeaseRepository#MAX_PLACEMENTS}) differs from the stored lease's, ask the agent to renew, which is the
     * existing {@code RENEWAL_REQUIRED} path (single-use nonce, epochs and replay rules untouched: the renewal mints under the
     * current set, as any renewal does). ONE batched read serves the whole pass.
     */
    private void renewForPlacementChanges(List<IdentityReconciliation> results, List<Held> held) {
        if (held.isEmpty()) {
            return;
        }
        Set<UUID> targets = held.stream().map(h -> h.identity().targetId()).collect(Collectors.toSet());
        Set<UUID> tenants = held.stream().map(h -> h.identity().tenantId()).collect(Collectors.toSet());   // I5, in the SQL too
        // Ordered by the DATABASE, as the lease's own LIMIT is: Java's UUID order is signed and PostgreSQL's is not, so a capped
        // subset taken in Java order differs from the lease's on a target past the cap and would ask for a renewal every pass.
        Map<String, java.util.LinkedHashSet<UUID>> live = new java.util.HashMap<>();
        try {
            jdbc.query("SELECT tenant_id, target_id, placement_id FROM sm_data.placement WHERE retired_at IS NULL"
                    + " AND tenant_id IN (:tenants) AND target_id IN (:targets) ORDER BY tenant_id, target_id, placement_id",
                    new MapSqlParameterSource().addValue("tenants", tenants).addValue("targets", targets), rs -> {
                        live.computeIfAbsent(rs.getObject("tenant_id", UUID.class) + "/" + rs.getObject("target_id", UUID.class),
                                k -> new java.util.LinkedHashSet<>()).add(rs.getObject("placement_id", UUID.class));
                    });
        } catch (RuntimeException e) {
            LOG.warn("placement-set check failed (will retry): {}", e.getClass().getName());
            return;
        }
        for (Held h : held) {
            Set<UUID> all = live.getOrDefault(h.identity().tenantId() + "/" + h.identity().targetId(),
                    new java.util.LinkedHashSet<>());
            Set<UUID> wanted = all.stream().limit(JdbcAgentLeaseRepository.MAX_PLACEMENTS).collect(Collectors.toSet());
            if (!wanted.equals(new java.util.HashSet<>(h.lease().placementIds()))) {
                results.set(h.slot(), renewalRequired(h.session(), h.identity()));
            }
        }
    }
}
