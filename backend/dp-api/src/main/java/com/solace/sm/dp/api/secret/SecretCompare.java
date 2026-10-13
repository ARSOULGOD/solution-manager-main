package com.solace.sm.dp.api.secret;

import java.util.UUID;

/**
 * The <b>compare-only</b> port to the DP secret store (plan 010 T10.4, {@code BE-D41}, D-ACCEPT's {@code V-4}).
 *
 * <p>It answers one question, "does this candidate equal the stored secret?", and has no operation that returns,
 * decrypts-to-the-caller or stores a value. That is the point of its shape ({@code I6}): the code that reads a host's
 * file back holds THIS port and nothing that can fetch a secret, so a read-back value can be compared and then dropped
 * but cannot be adopted. The stored value never leaves the implementation, and the candidate is never kept.
 */
public interface SecretCompare {

    /** What a comparison can say. Never the value of either side. */
    enum Outcome {
        /** The candidate equals the stored secret. */
        SAME,
        /** It does not. */
        DIFFERS,
        /** There was nothing to compare against, or the stored secret could not be opened: no claim is made either way. */
        UNAVAILABLE
    }

    /**
     * Compares {@code candidate} (UTF-8 bytes) with the stored {@code CONFIG_FIELD} secret {@code secretRef} of this
     * organization, in memory, in constant time. A secret of another tenant, or of another purpose, is
     * {@link Outcome#UNAVAILABLE}, as is an absent one.
     *
     * <p>The caller owns {@code candidate} and zeroes it; this method keeps no copy.
     */
    Outcome compare(UUID organizationId, UUID secretRef, byte[] candidate);
}
