package com.solace.sm.dp.api.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A live target's connection record as the management read returns it (plan 013 T1.4, D-REG section 5.1 T3; OQ-13 (a)).
 *
 * <p>⛔ {@code I6}: there is no field for private-key material, for the {@code credential_ref}, or for a hash of the
 * private key. The only value derived from the key is the fingerprint of its PUBLIC half
 * ({@link CredentialSummary#publicKeyFingerprint}), which is what lets a user compare the stored key with
 * {@code authorized_keys}.
 *
 * <p>An {@code agent} target (plan 015 T3.5, {@code agentId} non-null) has NO address, user, pin or credential: {@code host},
 * {@code sshUser}, {@code hostKeyFingerprint} and {@code credential} are {@code null}, {@code port} is 0 and
 * {@code offeredHostKeys} is empty. It names the agent and the envelope and nothing else ({@code BE-D68}).
 *
 * @param offeredHostKeys the live host-key fingerprints recorded for the machine ({@code OR-89}), the pin among them
 */
public record TargetConnectionView(UUID targetId, String host, int port, String sshUser, String hostKeyFingerprint,
        List<String> offeredHostKeys, String allowedConfigRoot, String allowedContainerPrefix,
        CredentialSummary credential, UUID agentId) {

    /** An SSH-docker connection: what every caller had before the agent transport. */
    public TargetConnectionView(UUID targetId, String host, int port, String sshUser, String hostKeyFingerprint,
            List<String> offeredHostKeys, String allowedConfigRoot, String allowedContainerPrefix,
            CredentialSummary credential) {
        this(targetId, host, port, sshUser, hostKeyFingerprint, offeredHostKeys, allowedConfigRoot, allowedContainerPrefix,
                credential, null);
    }

    /** The connection of an agent target: the agent and the envelope. */
    public static TargetConnectionView ofAgent(UUID targetId, UUID agentId, String allowedConfigRoot,
            String allowedContainerPrefix) {
        return new TargetConnectionView(targetId, null, 0, null, null, List.of(), allowedConfigRoot,
                allowedContainerPrefix, null, Objects.requireNonNull(agentId, "agentId"));
    }

    public boolean isAgent() {
        return agentId != null;
    }

    /**
     * @param publicKeyFingerprint absent for a key registered before the fingerprint was recorded, until its first
     *                             replay or rotation derives it
     * @param setAt                when the stored key was set; absent only if nothing recorded it
     */
    public record CredentialSummary(boolean present, String publicKeyFingerprint, Instant setAt) {
    }

    public TargetConnectionView {
        Objects.requireNonNull(targetId, "targetId");
        if (agentId == null) {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(sshUser, "sshUser");
            Objects.requireNonNull(hostKeyFingerprint, "hostKeyFingerprint");
            Objects.requireNonNull(credential, "credential");
        }
        offeredHostKeys = List.copyOf(Objects.requireNonNull(offeredHostKeys, "offeredHostKeys"));
        Objects.requireNonNull(allowedConfigRoot, "allowedConfigRoot");
        Objects.requireNonNull(allowedContainerPrefix, "allowedContainerPrefix");
    }
}
