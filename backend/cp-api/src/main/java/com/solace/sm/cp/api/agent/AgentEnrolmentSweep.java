package com.solace.sm.cp.api.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The control plane's half of the scheduled sweep of an interrupted agent enrolment (plan 015 T3.5, T4.6; {@code BE-D71},
 * {@code BE-D32}'s pattern; {@code OR-140}). An enrolment writes the control plane's agent target first and the data plane's
 * connection second; a crash between the two leaves a target the data plane lacks, which {@code listTargets} shows degraded. If
 * the agent never retries, the gateway's SCHEDULED JOB (the gateway is the one module permitted both {@code -api}s) asks
 * {@link #organizations} which organizations have any candidate, and per organization pages through {@link #candidates}, asks the
 * data plane which of them hold a connection, {@link #revoke}s the registration of the rest, asks the data plane AGAIN, and only
 * if the target is still absent {@link #retire}s it. No request path calls this: a reach-level GET never triggers a revocation.
 *
 * <p><b>Why revoke first, and why look again.</b> A replay of a consumed token is honoured until the certificate ends, long after
 * the token's own window, so "the agent has stopped retrying" is a presumption, not a fact. Revoking makes the replay refused (the
 * registry reads only ACTIVE registrations), which closes the door; the second data-plane read then catches a replay whose data
 * plane write landed in between, and the target is left alive with its registration revoked, both planes agreeing and the
 * operator's own revoke-and-retire completing it. What remains is a data-plane write already in flight at the second read.
 *
 * <p><b>Attribution ({@code OR-140}, plan 015 section 10 Q-C1).</b> Both writes are audited to the SYSTEM ACTOR
 * ({@code sm_control.system_actor}, {@code V022}), under their own operations ({@code AGENT_REVOKED}, {@code TARGET_RETIRED}),
 * with the job pass's own correlation id and the client address {@code system-job}. Never the principal who issued the token (who
 * did not act), never a requester (there is none). The database admits the system actor for exactly these two writes.
 */
public interface AgentEnrolmentSweep {

    /** An agent target whose enrolment consumed its token and which is not retired; also the keyset cursor of the next page. */
    record Candidate(UUID targetId, UUID agentId, Instant tokenExpiresAt) { }

    /**
     * The organizations that have at least one {@linkplain #candidates candidate} older than {@code olderThan} (by the control
     * plane's own clock). <b>The control plane's cross-organization read for the sweep job ({@code OR-140}, which amends
     * {@code OR-44}/{@code OR-49} to this extent): the job is its only caller</b> ({@code TenantEnumerationBoundaryTest});
     * anything else that needs an organization list is a new enumeration and needs an owner ruling. Healthy enrolments match too
     * (the control plane cannot see the data plane), so an organization with one is visited and finds nothing to abandon.
     */
    List<UUID> organizations(Duration olderThan);

    /**
     * This organization's live agent targets whose registration's TOKEN expired more than {@code olderThan} ago (by the control
     * plane's own clock), oldest token first, KEYSET-paged after {@code after} ({@code null} for the first page), at most
     * {@code limit}. A short page is the last. Healthy enrolments match too (the control plane cannot see the data plane), which is
     * why the caller must page and not take only the first batch.
     */
    List<Candidate> candidates(UUID organizationId, Duration olderThan, Candidate after, int limit);

    /**
     * Revokes the candidate's registration if it is still active, in a transaction of its own, audited to the system actor under
     * {@code correlationId}. Idempotent: a registration already revoked changes nothing and writes no audit row.
     */
    void revoke(UUID organizationId, Candidate candidate, UUID correlationId);

    /**
     * Retires the candidate's control-plane target, in a transaction of its own, audited to the system actor under
     * {@code correlationId}. Idempotent: a target already retired changes nothing and writes no audit row.
     */
    void retire(UUID organizationId, Candidate candidate, UUID correlationId);
}
