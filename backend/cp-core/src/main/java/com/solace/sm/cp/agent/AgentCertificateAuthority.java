package com.solace.sm.cp.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Signs the agent CSR with the CP-owned CA key; the private key never crosses the CP boundary. */
public interface AgentCertificateAuthority {

    /**
     * @throws com.solace.sm.cp.api.agent.AgentEnrollmentRefusedException {@code CSR_REFUSED}: the CSR is malformed, its
     *         signature does not verify, or its key is not EC P-256 or P-384
     * @throws IllegalStateException the CA is unusable (no operator keystore, or it cannot be read)
     */
    IssuedCertificate issue(UUID agentId, byte[] csrDer, Instant now, Duration requestedLifetime);

    /** Fails with {@link IllegalStateException} unless the operator keystore is configured and loads. */
    void verifyOperable();

    record IssuedCertificate(List<byte[]> chainDer, List<byte[]> trustBundlePem,
            Instant validFrom, Instant validTo) { }
}
