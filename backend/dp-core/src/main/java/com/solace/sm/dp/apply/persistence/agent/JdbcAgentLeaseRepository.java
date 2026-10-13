package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentIdentity;
import com.solace.sm.dp.api.agw.AgentLeasePort;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.LeaseGrant;
import com.solace.sm.dp.api.agw.LeaseIssuer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code sm_data.agent_lease} (plan 015 T2.1b, {@code AGW-D24}): the lease the control plane signs and the data plane persists.
 * The DP supplies the placement set (the live placements of the agent's own target) and the issuer signs exactly that.
 *
 * <p><b>Only the digest of the renewal nonce is stored</b> ({@code renewal_nonce_digest}, the unkeyed SHA-256 of a one-shot
 * bearer secret; {@code V028}). Consequences:
 * <ul>
 *   <li>{@link #current} never returns a grant: it answers {@code LEASE_RENEW_REQUIRED} when any lease row exists and empty when
 *       none ever did. A reconnecting agent renews with the nonce it holds.</li>
 *   <li>{@link #issue} is the FIRST issuance only: it is refused {@code ISSUE_NOT_ALLOWED} whenever any lease row exists for the
 *       agent (design 4.6, {@code AGW-D15}; the stricter reading of {@code AGW-D19} item 3), EXCEPT the replay of a lost reply.</li>
 *   <li><b>Lost reply.</b> The issuer is deterministic in its inputs (identical inputs return an identical grant), so a retry whose
 *       first answer was lost asks it again with the same inputs: if the digest of the nonce it returns equals the ACTIVE row's, that
 *       grant is returned and nothing is written; otherwise the call is refused. That is what lets {@code issue} and {@code renew}
 *       be idempotent by request id without keeping a request id or a nonce.</li>
 * </ul>
 * Every grant is validated BEFORE any write ({@code GRANT_INVALID}): thumbprint, target, epochs, capabilities within the identity's,
 * non-negative ceilings and margin, a lifetime that covers the longest ceiling plus the margin, and placements within the set the data
 * plane supplied.
 */
public final class JdbcAgentLeaseRepository implements AgentLeasePort {

    static final int MAX_PLACEMENTS = 64;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AgentAuthority authority;
    private final JdbcAgentIdentityRepository identities;
    private final Supplier<LeaseIssuer> issuer;

    public JdbcAgentLeaseRepository(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
            AgentAuthority authority, JdbcAgentIdentityRepository identities, Supplier<LeaseIssuer> issuer) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.authority = authority;
        this.identities = identities;
        this.issuer = issuer;
    }

    @Override
    public Optional<LeaseGrant> current(AgentSessionRef session) {
        AgentAuthority.Standing standing = authority.requireSession(session);
        if (authority.anyLeaseRow(standing.tenantId(), standing.agentId())) {
            throw AgentSql.refused(GatewayCode.LEASE_RENEW_REQUIRED, "a lease exists; renew it with its nonce");
        }
        return Optional.empty();
    }

    @Override
    public LeaseGrant issue(String requestId, AgentSessionRef session, Instant agentClock, AgentRequestOrigin origin) {
        AgentAuthority.Standing before = authority.requireSession(session);
        AgentAuthority.Standing synced = synced(before);
        return tx.execute(status -> {
            AgentAuthority.Standing standing = authority.requireSession(session);      // FOR SHARE, to the end of this transaction
            standing = adopt(standing, synced);
            lock(standing);
            Optional<AgentAuthority.StoredLease> held = authority.activeRow(standing.tenantId(), standing.agentId());
            if (authority.anyLeaseRow(standing.tenantId(), standing.agentId())) {
                // The only way back in is a lost reply: ask the issuer again with the same inputs.
                if (held.isPresent()) {
                    Optional<LeaseGrant> replay = replay(standing, session, agentClock, requestId, null, origin, held.get());
                    if (replay.isPresent()) {
                        return replay.get();
                    }
                }
                throw AgentSql.refused(GatewayCode.ISSUE_NOT_ALLOWED, "identity already has a lease");
            }
            return mint(standing, session, agentClock, requestId, null, origin);
        });
    }

    @Override
    public LeaseGrant renew(String requestId, AgentSessionRef session, String consumedNonce, Instant agentClock,
            AgentRequestOrigin origin) {
        AgentAuthority.Standing before = authority.requireSession(session);
        AgentAuthority.Standing synced = synced(before);
        return tx.execute(status -> {
            AgentAuthority.Standing current = adopt(authority.requireSession(session), synced);
            lock(current);
            AgentAuthority.StoredLease held = authority.activeRow(current.tenantId(), current.agentId())
                    .orElseThrow(() -> AgentSql.refused(GatewayCode.LEASE_RENEW_REQUIRED, "no prior lease exists"));
            if (consumedNonce != null && MessageDigest.isEqual(AgentSql.sha256Hex(consumedNonce).getBytes(StandardCharsets.US_ASCII),
                    held.nonceDigest().getBytes(StandardCharsets.US_ASCII))) {
                return mint(current, session, agentClock, requestId, consumedNonce, origin);
            }
            // The nonce is not the ACTIVE one. Either it is wrong, or it is the nonce this lease REPLACED and the reply carrying
            // the new one was lost: the deterministic issuer answers the second case with the grant whose digest is held.
            Optional<LeaseGrant> replay = consumedNonce == null ? Optional.empty()
                    : replay(current, session, agentClock, requestId, consumedNonce, origin, held);
            return replay.orElseThrow(() -> AgentSql.refused(GatewayCode.LEASE_RENEW_REFUSED, "lease nonce mismatch"));
        });
    }

    /** The standing after the control plane's registration was applied (epochs and capabilities), refusing a revoked identity. */
    private AgentAuthority.Standing synced(AgentAuthority.Standing standing) {
        AgentIdentity synced = identities.synced(standing.toIdentity());
        if (synced.revoked()) {
            throw AgentSql.refused(GatewayCode.IDENTITY_REVOKED, "agent identity is revoked");
        }
        return new AgentAuthority.Standing(standing.tenantId(), standing.agentId(), standing.environmentId(), standing.targetId(),
                standing.certificateThumbprint(), false, synced.agentRevocationEpoch(), synced.fleetRevocationEpoch(),
                synced.capabilities(), false);
    }

    /** The locked standing re-based on the synced epochs and capabilities (the identity row was updated by the sync). */
    private static AgentAuthority.Standing adopt(AgentAuthority.Standing locked, AgentAuthority.Standing synced) {
        return new AgentAuthority.Standing(locked.tenantId(), locked.agentId(), locked.environmentId(), locked.targetId(),
                locked.certificateThumbprint(), locked.revoked(), synced.agentEpoch(), synced.fleetEpoch(),
                synced.capabilities(), locked.sessionClosed());
    }

    private void lock(AgentAuthority.Standing standing) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(:key))",
                new MapSqlParameterSource("key", "agw-lease|" + standing.agentId()), rs -> { });
    }

    /** Asks the issuer again; returns its grant only if it is the one whose nonce digest the ACTIVE row holds. Writes nothing. */
    private Optional<LeaseGrant> replay(AgentAuthority.Standing standing, AgentSessionRef session, Instant agentClock,
            String requestId, String consumedNonce, AgentRequestOrigin origin, AgentAuthority.StoredLease held) {
        List<UUID> placements = livePlacements(standing);
        LeaseGrant grant;
        try {
            grant = issuer.get().issue(standing.toIdentity(), session, agentClock, requestId, consumedNonce, placements, origin);
        } catch (GatewayPortException e) {
            if (e.code() == GatewayCode.INTERNAL_UNAVAILABLE) {
                throw e;
            }
            return Optional.empty();
        }
        if (!AgentSql.sha256Hex(grant.nonce()).equals(held.nonceDigest())) {
            return Optional.empty();
        }
        validate(grant, standing, placements);
        // The stored row is the authority that admission checks, so a replay must not promise the agent more than it holds: it is
        // returned with the ROW's times and, whenever the live permissions have moved on since (the placement set, a capability, a
        // ceiling), with the ROW's permissions too. Refusing instead (the earlier rule) stranded the agent: the digest match proves
        // this is the request whose reply was lost, and the nonce in that reply is the only one the agent can renew with, so a
        // refusal left it resending the same request forever. Returning the stored grant gives it the nonce back, writes nothing and
        // asks nothing of the control plane (no nonce-free re-issue, AGW-D15); the agent's next renewal, under the new nonce, mints
        // the grant under the current permissions. Only an unchanged replay is re-validated: a changed one holds exactly what was
        // validated when the row was minted.
        boolean unchanged = new HashSet<>(grant.capabilities()).equals(new HashSet<>(held.capabilities()))
                && grant.verbCeilings().equals(held.verbCeilings())
                && new HashSet<>(grant.placementIds()).equals(new HashSet<>(held.placementIds()));
        LeaseGrant stored = unchanged
                ? new LeaseGrant(grant.agentId(), grant.certificateThumbprint(), grant.targetId(), grant.placementIds(),
                        grant.agentRevocationEpoch(), grant.fleetRevocationEpoch(), held.issuedAt(), held.expiresAt(),
                        grant.audience(), grant.nonce(), grant.capabilities(), grant.verbCeilings(), grant.admissionMargin(),
                        clock.instant())
                : new LeaseGrant(grant.agentId(), grant.certificateThumbprint(), grant.targetId(), held.placementIds(),
                        held.agentEpoch(), held.fleetEpoch(), held.issuedAt(), held.expiresAt(), grant.audience(), grant.nonce(),
                        held.capabilities(), held.verbCeilings(), held.admissionMargin(), clock.instant());
        if (unchanged) {
            validate(stored, standing, placements);
        }
        return Optional.of(stored);
    }

    private LeaseGrant mint(AgentAuthority.Standing standing, AgentSessionRef session, Instant agentClock, String requestId,
            String consumedNonce, AgentRequestOrigin origin) {
        List<UUID> placements = livePlacements(standing);
        LeaseGrant grant = issuer.get().issue(standing.toIdentity(), session, agentClock, requestId, consumedNonce, placements, origin);
        validate(grant, standing, placements);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenant", standing.tenantId())
                .addValue("agent", standing.agentId());
        jdbc.update("UPDATE sm_data.agent_lease SET state = 'SUPERSEDED' WHERE tenant_id = :tenant AND agent_id = :agent"
                + " AND state = 'ACTIVE'", p);
        AgentSql.write(() -> jdbc.update("INSERT INTO sm_data.agent_lease (tenant_id, agent_id, lease_id, certificate_thumbprint,"
                + " agent_epoch, fleet_epoch, issued_at, expires_at, audience, renewal_nonce_digest, capabilities,"
                + " placement_ids, verb_ceilings, admission_margin_ms, server_clock, state)"
                + " VALUES (:tenant, :agent, :lease, :thumbprint, :agentEpoch, :fleetEpoch, :issued, :expires, :audience,"
                + " :digest, CAST(:caps AS text[]), CAST(:placements AS uuid[]), CAST(:ceilings AS jsonb), :margin,"
                + " :serverClock, 'ACTIVE')", p.addValue("lease", UUID.randomUUID())
                .addValue("thumbprint", standing.certificateThumbprint())
                .addValue("agentEpoch", grant.agentRevocationEpoch()).addValue("fleetEpoch", grant.fleetRevocationEpoch())
                .addValue("issued", AgentSql.at(grant.issuedAt())).addValue("expires", AgentSql.at(grant.expiresAt()))
                .addValue("audience", grant.audience()).addValue("digest", AgentSql.sha256Hex(grant.nonce()))
                .addValue("caps", AgentSql.capabilityArray(grant.capabilities()))
                .addValue("placements", AgentSql.uuidArray(grant.placementIds()))
                .addValue("ceilings", ceilingsJson(grant.verbCeilings()))
                .addValue("margin", grant.admissionMargin().toMillis())
                .addValue("serverClock", AgentSql.at(grant.serverClock()))));
        return grant;
    }

    /** Everything a grant must satisfy BEFORE a row is written (finding 8); {@code GRANT_INVALID} otherwise. */
    private static void validate(LeaseGrant grant, AgentAuthority.Standing standing, List<UUID> placements) {
        if (!standing.agentId().equals(grant.agentId())) {
            throw AgentSql.refused(GatewayCode.LEASE_KEY_MISMATCH, "lease agent mismatch");
        }
        if (!standing.certificateThumbprint().equals(grant.certificateThumbprint())
                || !standing.targetId().equals(grant.targetId())) {
            throw AgentSql.refused(GatewayCode.GRANT_INVALID, "lease is bound to another certificate or target");
        }
        if (grant.agentRevocationEpoch() != standing.agentEpoch() || grant.fleetRevocationEpoch() != standing.fleetEpoch()) {
            throw AgentSql.refused(GatewayCode.GRANT_INVALID, "lease epochs are not the ones requested");
        }
        if (!new HashSet<AgentCapability>(standing.capabilities()).containsAll(grant.capabilities())) {
            throw AgentSql.refused(GatewayCode.GRANT_INVALID, "lease grants a capability the identity does not hold");
        }
        if (!new HashSet<>(placements).containsAll(grant.placementIds())) {
            throw AgentSql.refused(GatewayCode.GRANT_INVALID, "lease names a placement the data plane did not supply");
        }
        Duration margin = grant.admissionMargin();
        if (margin == null || margin.isNegative()
                || grant.verbCeilings().values().stream().anyMatch(d -> d == null || d.isNegative())) {
            throw AgentSql.refused(GatewayCode.GRANT_INVALID, "lease ceilings and margin must not be negative");
        }
        Duration longest = grant.verbCeilings().values().stream().max(Duration::compareTo).orElse(Duration.ZERO);
        if (Duration.between(grant.issuedAt(), grant.expiresAt()).compareTo(longest.plus(margin)) < 0) {
            throw AgentSql.refused(GatewayCode.GRANT_INVALID, "lease lifetime is shorter than its longest ceiling plus margin");
        }
    }

    /** The live placements of the agent's own target: the set the data plane lets the control plane sign. */
    private List<UUID> livePlacements(AgentAuthority.Standing standing) {
        return jdbc.queryForList("SELECT placement_id FROM sm_data.placement WHERE tenant_id = :tenant AND target_id = :target"
                        + " AND retired_at IS NULL ORDER BY placement_id LIMIT " + MAX_PLACEMENTS,
                new MapSqlParameterSource().addValue("tenant", standing.tenantId()).addValue("target", standing.targetId()),
                UUID.class);
    }

    private static String ceilingsJson(Map<AgentOperation, Duration> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> "\"" + e.getKey().name() + "\":" + e.getValue().toMillis())
                .collect(Collectors.joining(",", "{", "}"));
    }
}
