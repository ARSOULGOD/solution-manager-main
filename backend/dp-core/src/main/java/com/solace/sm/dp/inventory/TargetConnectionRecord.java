package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.inventory.HostKeyType;
import java.time.Instant;
import java.util.UUID;

/**
 * One {@code sm_data.target_connection} row, read fresh per SSH session (OR-28). {@code credentialRef} names an
 * {@code sm_secret} row and is INTERNAL to the data plane: no {@code dp-api} view carries it.
 *
 * <p>{@code credentialPublicFingerprint} is the fingerprint of the stored key's PUBLIC half ({@code sm_data} V022, plan
 * 013): what a replay and a rotation compare. {@code null} for a row registered before the column existed, until its first
 * replay or rotation derives and back-fills it.
 *
 * <p>{@code hostKeyType} is the type of the pinned key ({@code sm_data} V027, OR-125 (2)): what every SSH connector
 * negotiates directly. {@code null} until it is known: a row registered before V027, or by the operator command; the
 * connector then discovers it and records it on its first success, and a replay of the registration back-fills it.
 *
 * <p>{@code agentId} is non-null exactly for a target whose transport is {@code agent} (V028, plan 015 T3.5). Such a row has NO
 * address, user, pin or credential: {@code host}, {@code sshUser}, {@code hostKeyFingerprint}, {@code credentialRef} and
 * {@code hostKeyType} are {@code null} and {@code port} is {@code 0}. Every reader that opens an SSH session or compares a
 * connection must test {@link #isAgent()} first and refuse the agent variant by name; it can never be handed an SSH binding.
 */
public record TargetConnectionRecord(UUID tenantId, UUID targetId, String host, int port, String sshUser,
        String hostKeyFingerprint, UUID credentialRef, String allowedConfigRoot, String allowedContainerPrefix,
        UUID createdBy, Instant createdAt, Instant retiredAt, String credentialPublicFingerprint,
        HostKeyType hostKeyType, UUID agentId) {

    /** The SSH-transport row every caller had before V028: no agent. */
    public TargetConnectionRecord(UUID tenantId, UUID targetId, String host, int port, String sshUser,
            String hostKeyFingerprint, UUID credentialRef, String allowedConfigRoot, String allowedContainerPrefix,
            UUID createdBy, Instant createdAt, Instant retiredAt, String credentialPublicFingerprint,
            HostKeyType hostKeyType) {
        this(tenantId, targetId, host, port, sshUser, hostKeyFingerprint, credentialRef, allowedConfigRoot,
                allowedContainerPrefix, createdBy, createdAt, retiredAt, credentialPublicFingerprint, hostKeyType, null);
    }

    /** True for an {@code agent}-transport row (V028): the SSH fields above are absent. */
    public boolean isAgent() {
        return agentId != null;
    }

    /** The transport's wire token: {@code agent} or {@code ssh-docker}. */
    public String transport() {
        return isAgent() ? "agent" : "ssh-docker";
    }

    /** A row whose pinned key's type is not (yet) known: the arity every caller had before V027. */
    public TargetConnectionRecord(UUID tenantId, UUID targetId, String host, int port, String sshUser,
            String hostKeyFingerprint, UUID credentialRef, String allowedConfigRoot, String allowedContainerPrefix,
            UUID createdBy, Instant createdAt, Instant retiredAt, String credentialPublicFingerprint) {
        this(tenantId, targetId, host, port, sshUser, hostKeyFingerprint, credentialRef, allowedConfigRoot,
                allowedContainerPrefix, createdBy, createdAt, retiredAt, credentialPublicFingerprint, null);
    }

    /** A row whose key's public fingerprint is not (yet) known: the arity every caller had before V022. */
    public TargetConnectionRecord(UUID tenantId, UUID targetId, String host, int port, String sshUser,
            String hostKeyFingerprint, UUID credentialRef, String allowedConfigRoot, String allowedContainerPrefix,
            UUID createdBy, Instant createdAt, Instant retiredAt) {
        this(tenantId, targetId, host, port, sshUser, hostKeyFingerprint, credentialRef, allowedConfigRoot,
                allowedContainerPrefix, createdBy, createdAt, retiredAt, null, null);
    }

    /** The same record with its stored key's public fingerprint set. */
    public TargetConnectionRecord withCredentialPublicFingerprint(String fingerprint) {
        return new TargetConnectionRecord(tenantId, targetId, host, port, sshUser, hostKeyFingerprint, credentialRef,
                allowedConfigRoot, allowedContainerPrefix, createdBy, createdAt, retiredAt, fingerprint, hostKeyType, agentId);
    }

    /** The same record with its pinned key's type set. */
    public TargetConnectionRecord withHostKeyType(HostKeyType type) {
        return new TargetConnectionRecord(tenantId, targetId, host, port, sshUser, hostKeyFingerprint, credentialRef,
                allowedConfigRoot, allowedContainerPrefix, createdBy, createdAt, retiredAt, credentialPublicFingerprint,
                type, agentId);
    }
}
