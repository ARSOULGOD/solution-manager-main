package com.solace.sm.dp.api.inventory;

import java.util.Objects;
import java.util.UUID;

/** Non-secret runtime projection values supplied by the gateway for a remote-server Connection. */
public record ConnectionRuntimeSpec(UUID connectionId, String host, int port, String sshUser,
        String hostKeyFingerprint, UUID credentialRef, UUID passphraseRef, String authMethod,
        String allowedConfigRoot, String allowedContainerPrefix) {

    /** Compatibility constructor for projections created before passphrase references were carried. */
    public ConnectionRuntimeSpec(UUID connectionId, String host, int port, String sshUser,
            String hostKeyFingerprint, UUID credentialRef, String allowedConfigRoot, String allowedContainerPrefix) {
        this(connectionId, host, port, sshUser, hostKeyFingerprint, credentialRef, null, "private-key",
                allowedConfigRoot, allowedContainerPrefix);
    }

    public ConnectionRuntimeSpec {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(sshUser, "sshUser");
        Objects.requireNonNull(hostKeyFingerprint, "hostKeyFingerprint");
        Objects.requireNonNull(credentialRef, "credentialRef");
        if (!"basic".equals(authMethod) && !"private-key".equals(authMethod)) {
            throw new IllegalArgumentException("authMethod must be basic or private-key");
        }
        // These are target settings, not Connection settings. Legacy direct-connection placement may still carry them;
        // a deployment target supplies them before an apply is allowed.
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }
}
