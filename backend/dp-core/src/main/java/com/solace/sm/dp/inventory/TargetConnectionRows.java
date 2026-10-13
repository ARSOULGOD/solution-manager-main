package com.solace.sm.dp.inventory;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The persistence port for {@code target_connection}, its host keys and its change events.
 *
 * <p>The methods after {@link #updateCredential(UUID, UUID, UUID)} are plan 013 T1.4's (registration over HTTP, the
 * fingerprint column and the event table of {@code sm_data} V022). They are {@code default} methods that FAIL LOUDLY, so
 * that the stores and fakes that predate them (several tests implement this port) keep compiling without any of them
 * silently doing nothing; the JDBC store overrides every one.
 */
public interface TargetConnectionRows {

    default void upsertDeploymentTargetRuntime(UUID tenantId,
            com.solace.sm.dp.api.inventory.DeploymentTargetRuntimeSpec spec) {
        throw new UnsupportedOperationException("this store cannot persist deployment target runtime metadata");
    }

    default void retireDeploymentTargetRuntime(UUID tenantId, UUID targetId) {
        throw new UnsupportedOperationException("this store cannot retire deployment target runtime metadata");
    }

    default Optional<com.solace.sm.dp.api.inventory.DeploymentTargetRuntimeSpec> findDeploymentTargetRuntime(
            UUID tenantId, UUID targetId) {
        return Optional.empty();
    }

    void insert(TargetConnectionRecord row);

    /** The record, retired or not; empty when the tenant has none for this target. */
    Optional<TargetConnectionRecord> find(UUID tenantId, UUID targetId);

    /**
     * Records fingerprints of the target's host (OR-89's residual); one already recorded for THIS target is a no-op.
     * A fingerprint that a different LIVE target of the same tenant already holds is a named constraint violation
     * ({@code target_host_key_once}).
     */
    void addHostKeys(UUID tenantId, UUID targetId, Collection<String> fingerprints);

    /** Every fingerprint recorded for the target (the pinned one included), retired or not. */
    Set<String> hostKeys(UUID tenantId, UUID targetId);

    /**
     * Repoints the live record at a new secret; returns rows changed (0 when absent or retired). The recorded public
     * fingerprint described the old key, so this form CLEARS it (the next compare derives and back-fills it); a caller
     * that knows the new key's fingerprint uses {@link #updateCredential(UUID, UUID, UUID, String)} (review W3C-5).
     */
    int updateCredential(UUID tenantId, UUID targetId, UUID credentialRef);

    // ---- plan 013 T1.4 ------------------------------------------------------------------------------------------

    /**
     * Inserts the record, or reports that it is already there. A unique violation on the PRIMARY KEY (another identical
     * registration committed first) is {@code false}, not an exception, so the caller can roll back and re-run as a
     * replay; a refusal because ANOTHER live target holds the pinned key is
     * {@link com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException}.
     *
     * @return {@code true} when this call inserted the row
     */
    default boolean insertOnce(TargetConnectionRecord row) {
        throw new UnsupportedOperationException("this store cannot register a connection once");
    }

    /**
     * As {@link #addHostKeys}, but a fingerprint another live target holds is
     * {@link com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException} rather than a database-shaped exception.
     */
    default void addHostKeysOnce(UUID tenantId, UUID targetId, Collection<String> fingerprints) {
        throw new UnsupportedOperationException("this store cannot add host keys once");
    }

    /**
     * The target (other than {@code exceptTargetId}, which may be null) of this tenant that holds any of these
     * fingerprints as a LIVE host key, if there is one. Read-only: the pre-check's probe.
     */
    default Optional<UUID> liveHolderOfAny(UUID tenantId, Collection<String> fingerprints, UUID exceptTargetId) {
        throw new UnsupportedOperationException("this store cannot probe host keys");
    }

    /** The target's LIVE host-key fingerprints (a retired key is not offered by the machine any more). */
    default Set<String> liveHostKeys(UUID tenantId, UUID targetId) {
        throw new UnsupportedOperationException("this store cannot list live host keys");
    }

    /**
     * Back-fills the stored key's public fingerprint of a row registered before the column existed. Only a {@code NULL}
     * is ever filled in, so a concurrent writer's value is never overwritten.
     */
    default void backfillCredentialFingerprint(UUID tenantId, UUID targetId, String fingerprint) {
        throw new UnsupportedOperationException("this store cannot back-fill a fingerprint");
    }

    /** As {@link #updateCredential(UUID, UUID, UUID)}, also recording the new key's public fingerprint. */
    default int updateCredential(UUID tenantId, UUID targetId, UUID credentialRef, String publicFingerprint) {
        return updateCredential(tenantId, targetId, credentialRef);
    }

    /** Appends one change event. Same transaction as the change it records (the service's job). */
    default void recordEvent(TargetConnectionEvent event) {
        throw new UnsupportedOperationException("this store cannot record a connection event");
    }

    /**
     * When the target's stored key was set: the newest {@code registered} or {@code credential_rotated} event, else the
     * record's own creation.
     */
    default Optional<Instant> credentialSetAt(UUID tenantId, UUID targetId) {
        throw new UnsupportedOperationException("this store cannot say when a credential was set");
    }

    // ---- plan 013 T1.5 ------------------------------------------------------------------------------------------

    /**
     * The live record, read {@code FOR NO KEY UPDATE}: held until the caller's transaction ends, so two edits or rotations
     * of one target serialize (T1.1's residual: two concurrent rotations to one new key each stored a secret row). A
     * placement insert's foreign key ({@code FOR KEY SHARE}) does not conflict with it, but V016's {@code BEFORE INSERT}
     * trigger takes the row {@code FOR UPDATE}, which does (review W4C-2): a placement insert and an edit or rotation of the
     * same target wait for each other. They cannot deadlock, because the edit holds the {@code apply_scope} rows first.
     * Empty when the tenant has no LIVE record for the target.
     */
    default Optional<TargetConnectionRecord> findLiveForChange(UUID tenantId, UUID targetId) {
        throw new UnsupportedOperationException("this store cannot lock a connection record");
    }

    /**
     * Changes the connection fields of the live record; returns rows changed (0 when absent or retired). The envelope is in
     * no UPDATE grant and has no parameter here. The database mirrors a changed pin into {@code target_host_key} (V012),
     * and a pin or a machine another live target holds is {@link com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException}.
     *
     * <p>{@code hostKeyType} is written in the SAME statement as the pin it describes (OR-125 (2)), so the pair is never
     * half-changed: the type of the pin now in the record, or {@code null} when it is not known.
     */
    default int updateConnection(UUID tenantId, UUID targetId, String host, int port, String sshUser,
            String hostKeyFingerprint, com.solace.sm.dp.api.inventory.HostKeyType hostKeyType) {
        throw new UnsupportedOperationException("this store cannot update a connection record");
    }

    /**
     * Records the type of the pin {@code fingerprint}, for the tenant's LIVE target pinned to it, only while no type is
     * recorded ({@code host_key_algorithm IS NULL}) and only for THAT pin: a row re-pinned meanwhile is not touched, and a
     * recorded type is never overwritten by a guess. Returns rows changed (0 when nothing matched). This is how a row
     * registered before V027 gets its type, on the first successful connection (OR-125 (2)).
     */
    default int recordHostKeyType(UUID tenantId, String fingerprint, com.solace.sm.dp.api.inventory.HostKeyType type) {
        throw new UnsupportedOperationException("this store cannot record a host key type");
    }

    /**
     * Retires the target's LIVE host keys that are not in {@code keep} (a re-pin: the machine no longer offers them);
     * returns rows retired. Leaves a key already retired untouched.
     */
    default int retireHostKeysNotIn(UUID tenantId, UUID targetId, Collection<String> keep, Instant at) {
        throw new UnsupportedOperationException("this store cannot retire host keys");
    }

    /**
     * Revives the target's OWN retired host keys among {@code offered} ({@code retired_at = NULL}); returns rows revived.
     * The primary key makes the pinned-key trigger's {@code DO NOTHING} keep a once-retired key retired, so a host that
     * offers it again needs this. A key another live target holds meanwhile is
     * {@link com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException}.
     */
    default int reviveHostKeys(UUID tenantId, UUID targetId, Collection<String> offered) {
        throw new UnsupportedOperationException("this store cannot revive host keys");
    }

    /**
     * Retires the live record; returns rows changed (0 when absent or already retired). V007's guard refuses it while a
     * live placement names the target: {@link com.solace.sm.dp.api.inventory.TargetHasLivePlacementsException}.
     */
    default int retire(UUID tenantId, UUID targetId, Instant at) {
        throw new UnsupportedOperationException("this store cannot retire a connection record");
    }

    /**
     * The record, retired or not, read {@code FOR UPDATE} for a RETIREMENT (review {@code W4C-2}). The root cause of the
     * race was on the PLACEMENT side, which {@link #holdLive} fixes: V016's {@code BEFORE INSERT} trigger on {@code placement}
     * locks the target {@code FOR UPDATE}, but only AFTER V007's envelope trigger has read {@code retired_at} without a lock,
     * so a retirement already in flight made the placement wait and then proceed without looking again. Measured on
     * PostgreSQL 16 (indicative): the retirement committed and the placement landed on the retired target, V007's
     * {@code target_connection_retire_guard} having seen no placement.
     *
     * <p>With {@link #holdLive} in place a retirement by a plain {@code UPDATE} (which takes {@code FOR NO KEY UPDATE}) is
     * already safe in both directions (measured). This {@code FOR UPDATE} is kept as defence in depth, as the reviewer
     * proposed: the retirement then holds the strongest lock before it looks at placements, whatever mode a future writer
     * of rows that reference the target takes. Empty when the tenant has no such record.
     */
    default Optional<TargetConnectionRecord> findForRetirement(UUID tenantId, UUID targetId) {
        throw new UnsupportedOperationException("this store cannot lock a connection record for retirement");
    }

    /**
     * Whether the target is LIVE, re-read {@code FOR UPDATE} and held until the caller's transaction ends (review
     * {@code W4C-2}): the counterpart of {@link #findForRetirement}. A retirement in flight is waited for and then seen
     * (READ COMMITTED re-evaluates the row), so a placement is never inserted on a target that has just been retired; and
     * once this returns {@code true} no retirement proceeds until the caller commits, after which V007's guard sees the
     * placement.
     *
     * <p>{@code FOR UPDATE} and not {@code FOR KEY SHARE}, on purpose: V016's trigger asks for {@code FOR UPDATE} at the
     * placement insert, and a transaction that holds only the weaker lock while it wants the stronger one deadlocks with
     * a twin (V016's own comment; measured on PostgreSQL 16, two such placement transactions on one target: "deadlock
     * detected"). Two placements on one target already serialize on that trigger's lock, so taking it first costs nothing.
     * An edit or a rotation ({@code FOR NO KEY UPDATE}) waits for it, as it already waits for the trigger.
     */
    default boolean holdLive(UUID tenantId, UUID targetId) {
        throw new UnsupportedOperationException("this store cannot hold a connection record live");
    }

    // ---- plan 014: a remote-server Connection's DP projection (sm_data.connection_runtime, V038) -------------------

    /**
     * The envelope of the connection's LIVE runtime projection, re-read {@code FOR UPDATE} and held until the caller's
     * transaction ends: {@link #holdLive}'s counterpart for a connection-backed placement, for the same reason (a
     * retirement in flight is waited for and then seen; once this returns, V039's {@code connection_runtime_retire_guard}
     * sees the placement). Empty when the tenant has no live projection for the connection, which a declare treats as
     * fail-closed (plan 014): there is no fallback to a Target.
     */
    default Optional<ConnectionRuntimeEnvelope> holdConnectionRuntimeLive(UUID tenantId, UUID connectionId) {
        throw new UnsupportedOperationException("this store cannot hold a connection runtime projection live");
    }

    /** Whether the tenant has a LIVE runtime projection for the connection. Unlocked: a pre-check only. */
    default boolean connectionRuntimeLive(UUID tenantId, UUID connectionId) {
        throw new UnsupportedOperationException("this store cannot read a connection runtime projection");
    }

    /**
     * Reads the complete LIVE runtime projection used by the apply adapter. Unlike the envelope above, this includes
     * the connection address and credential reference. It is intentionally a DP-internal port: callers must never
     * expose the returned credential reference or the host details through a browser response.
     */
    default Optional<com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec> findConnectionRuntime(
            UUID tenantId, UUID connectionId) {
        throw new UnsupportedOperationException("this store cannot read a connection runtime binding");
    }

    /**
     * Plan 015: writes the gateway's non-secret projection of one remote-server Connection — insert on first
     * sight, replace in place on every later call (an edit re-upserts with the new values; {@code created_at} is
     * preserved by the conflict clause, not reset). {@code tenantId} is a parameter, never read from {@code spec}
     * ({@code I5}).
     */
    default void upsertConnectionRuntime(UUID tenantId, com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec spec) {
        throw new UnsupportedOperationException("this store cannot write a connection runtime projection");
    }

    /** Retires the projection after the control plane's Connection becomes inactive or deleted. A no-op if absent. */
    default void retireConnectionRuntime(UUID tenantId, UUID connectionId) {
        throw new UnsupportedOperationException("this store cannot retire a connection runtime projection");
    }

    /**
     * Whether the agent's identity is revoked (plan 015 T3.5): the condition under which its target may be retired. Revocation
     * is sticky, so a {@code true} stays true. The default throws: a store that cannot answer must not retire an agent target.
     */
    default boolean agentRevoked(UUID tenantId, UUID agentId) {
        throw new UnsupportedOperationException("this store cannot say whether an agent is revoked");
    }

    /**
     * The transport ({@code ssh-docker} or {@code agent}) of each of these targets, in one read, for the tenant (plan 015 T3.3). A
     * target the store does not know is absent from the map, and a caller treats absence as {@code ssh-docker}. The default
     * answers none: a store with no agent targets has nothing to say.
     */
    default java.util.Map<java.util.UUID, String> transportsOf(java.util.UUID tenantId, java.util.Collection<java.util.UUID> targetIds) {
        return java.util.Map.of();
    }
}
