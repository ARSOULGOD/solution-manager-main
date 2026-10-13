package com.solace.sm.cp.target;

import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.target.TargetView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@code sm_control.target} (plan 010 T2.7). Its JDBC adapter belongs in
 * {@code cp.persistence}. Every method names the organization, and binds it ({@code I5}).
 */
public interface TargetRepository {

    /** A stored target and, once retired, when. */
    record Stored(TargetView view, Instant retiredAt) {

        public boolean retired() {
            return retiredAt != null;
        }
    }

    /** Live (not retired) targets of the organization, ordered by name. */
    List<TargetView> listLive(UUID organizationId);

    /**
     * The target of this name in the organization: the live one when there is one (at most one can exist, V018's partial
     * {@code target_name_once}), else the newest retired one. A retired target frees its name, so several rows may
     * share it.
     */
    Optional<Stored> findByName(UUID organizationId, String name);

    /** The target with this id in the organization, retired or not. */
    Optional<Stored> findById(UUID organizationId, UUID targetId);

    /**
     * Inserts the target, creating the canonical scope row for {@code ownershipScope} when none exists. Must run in
     * the caller's transaction, after the audit context is set (OR-25).
     */
    void insert(UUID organizationId, UUID targetId, String name, String kind, String transport,
            ScopePath ownershipScope, UUID createdBy, Instant createdAt);

    default void insert(UUID organizationId, UUID targetId, String name, String kind, String transport,
            ScopePath ownershipScope, UUID createdBy, Instant createdAt, UUID connectionId, String runtime,
            String runtimeSettingsJson) {
        insert(organizationId, targetId, name, kind, transport, ownershipScope, createdBy, createdAt);
    }

    /**
     * Retires the LIVE target (plan 013 T1.5); returns rows changed: 0 when it is absent, another organization's, or
     * already retired. Must run in the caller's transaction, after the audit context is set (OR-25). The runtime role holds
     * {@code UPDATE (retired_at)} on {@code target} (V013) and nothing else on it.
     */
    int markRetired(UUID organizationId, UUID targetId, Instant at);

    /** Updates linked-target metadata without changing its ownership scope or identity. */
    default int updateMetadata(UUID organizationId, UUID targetId, String name, UUID connectionId, String runtime,
            String runtimeSettingsJson) {
        throw new UnsupportedOperationException("target metadata update is not supported");
    }

    /**
     * Whether an ACTIVE, UNCONSUMED agent enrolment registration of the organization holds this target name (plan 015 T2.2
     * review 7, T3.5): {@code agent_registration_pending_name} reserves the name for the token until the token is used or the
     * registration revoked (the index cannot read the clock, so an expired unused token holds it too). A registration that took
     * the name would otherwise strand the agent at enrolment with {@code TARGET_NAME_TAKEN}. The default is false: a store with no
     * agent registrations holds none.
     */
    default boolean nameHeldByPendingEnrolment(UUID organizationId, String name) {
        return false;
    }

    /** Whether an ACTIVE agent registration of the organization is bound to this target (its enrolment consumed it). Default: no. */
    default boolean activeAgentRegistrationHolds(UUID organizationId, UUID targetId) {
        return false;
    }

    /**
     * Takes a transaction-scoped advisory lock on (organization, target name), so that {@code registerTarget}, token issue and
     * enrolment consumption, which each check one table for the name and write another, cannot interleave (plan 015 T3.5 fix
     * round). Must run inside the caller's transaction, first. Default: nothing to lock.
     */
    default void lockName(UUID organizationId, String name) {
    }
}
