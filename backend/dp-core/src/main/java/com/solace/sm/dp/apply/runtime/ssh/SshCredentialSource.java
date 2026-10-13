package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import java.util.UUID;

/**
 * Where the SSH transport gets a private key from: by reference, for one use, in memory.
 *
 * <p>The transport authenticates from the returned material and closes it, which zeroises the buffer. No
 * implementation may write the key to a file the transport can see, and the transport never asks for one.
 * Production reads the secret store ({@code SecretPurpose.SSH_PRIVATE_KEY}, tenant-scoped, T6.2); the demo
 * serves its vault-materialized key through the same seam.
 */
@FunctionalInterface
public interface SshCredentialSource {

    /**
     * The key named by {@code credentialRef} for {@code tenant}. The caller closes the result.
     *
     * @throws com.solace.sm.dp.secret.SecretException when the reference is not found for that tenant
     */
    SecretMaterial privateKey(TenantId tenant, UUID credentialRef);

    /**
     * Resolves a key using the purpose stored with its reference. Legacy target bindings use
     * {@link SecretPurpose#SSH_PRIVATE_KEY}; connection-backed targets use
     * {@link SecretPurpose#CONNECTION_CREDENTIAL}.
     *
     * <p>The default preserves the original two-argument seam for legacy implementations and tests.
     */
    default SecretMaterial privateKey(TenantId tenant, UUID credentialRef, SecretPurpose purpose) {
        return privateKey(tenant, credentialRef);
    }

    /** Resolves an optional private-key passphrase by reference; callers close the returned material. */
    default SecretMaterial passphrase(TenantId tenant, UUID passphraseRef, SecretPurpose purpose) {
        return privateKey(tenant, passphraseRef, purpose);
    }

    /** Resolves a password credential by reference; callers close the returned material. */
    default SecretMaterial password(TenantId tenant, UUID passwordRef, SecretPurpose purpose) {
        return privateKey(tenant, passwordRef, purpose);
    }
}
