package com.solace.sm.dp.apply.production;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ssh.SshCredentialSource;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretResolver;
import java.util.Objects;
import java.util.UUID;

/**
 * Where production's SSH transport gets a private key (plan 010 T6.2): the DP secret store, by reference, for one use.
 *
 * <p>The request names the placement's own tenant and the purpose bound to that placement. Legacy target bindings use
 * {@link SecretPurpose#SSH_PRIVATE_KEY}; connection-backed bindings use {@link SecretPurpose#CONNECTION_CREDENTIAL}.
 * Another tenant's key or a secret stored for any other purpose fails authentication instead of being read as a key
 * (the purpose is part of the authenticated data, D-KEY 3.3). The caller closes the result, which zeroes the buffer;
 * nothing here writes the key anywhere (T6.1: the transport authenticates from memory).
 */
public final class SecretStoreCredentialSource implements SshCredentialSource {

    private final SecretResolver secrets;

    public SecretStoreCredentialSource(SecretResolver secrets) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    @Override
    public SecretMaterial privateKey(TenantId tenant, UUID credentialRef) {
        return privateKey(tenant, credentialRef, SecretPurpose.SSH_PRIVATE_KEY);
    }

    @Override
    public SecretMaterial privateKey(TenantId tenant, UUID credentialRef, SecretPurpose purpose) {
        return secrets.resolve(Objects.requireNonNull(tenant, "tenant").value(),
                Objects.requireNonNull(credentialRef, "credential reference"),
                Objects.requireNonNull(purpose, "credential purpose"));
    }

    @Override
    public SecretMaterial passphrase(TenantId tenant, UUID passphraseRef, SecretPurpose purpose) {
        return secrets.resolve(Objects.requireNonNull(tenant, "tenant").value(),
                Objects.requireNonNull(passphraseRef, "passphrase reference"),
                Objects.requireNonNull(purpose, "credential purpose"));
    }

    @Override
    public SecretMaterial password(TenantId tenant, UUID passwordRef, SecretPurpose purpose) {
        return secrets.resolve(Objects.requireNonNull(tenant, "tenant").value(),
                Objects.requireNonNull(passwordRef, "password reference"),
                Objects.requireNonNull(purpose, "credential purpose"));
    }

    /** Names nothing. */
    @Override
    public String toString() {
        return "SecretStoreCredentialSource";
    }
}
