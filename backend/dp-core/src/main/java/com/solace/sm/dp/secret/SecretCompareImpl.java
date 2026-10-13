package com.solace.sm.dp.secret;

import com.solace.sm.dp.api.secret.SecretCompare;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link SecretCompare} over the secret store's read side (plan 010 T10.4, {@code BE-D41}, D-ACCEPT's {@code V-4}).
 *
 * <p>The ONLY thing here that can open a stored secret on behalf of a read-back, and it opens it for one comparison: the
 * material is decrypted into the store's own buffer, compared in constant time, and closed (zeroed) before this method
 * returns. The result is {@link Outcome#SAME} or {@link Outcome#DIFFERS} and nothing else of either side, so the stored
 * value cannot leave through this port and the candidate cannot be adopted through it ({@code I6}).
 *
 * <p>Any secret-store failure (not found, wrong tenant or purpose, key unavailable, authentication failed) is
 * {@link Outcome#UNAVAILABLE}: "could not compare" is a different fact from "differs", and claiming {@code DIFFERS} for
 * an unreadable secret would assert something no one measured. The exception's text is not forwarded.
 */
public final class SecretCompareImpl implements SecretCompare {

    private final SecretResolver resolver;

    public SecretCompareImpl(SecretResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public Outcome compare(UUID organizationId, UUID secretRef, byte[] candidate) {
        Objects.requireNonNull(organizationId, "organization");
        Objects.requireNonNull(secretRef, "secretRef");
        Objects.requireNonNull(candidate, "candidate");
        try (SecretMaterial stored = resolver.resolve(organizationId, secretRef, SecretPurpose.CONFIG_FIELD)) {
            return MessageDigest.isEqual(stored.bytes(), candidate) ? Outcome.SAME : Outcome.DIFFERS;
        } catch (SecretException e) {
            return Outcome.UNAVAILABLE;
        }
    }

    /** Names nothing. */
    @Override
    public String toString() {
        return "SecretCompareImpl";
    }
}
