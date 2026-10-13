package com.solace.sm.dp.secret;

import java.util.UUID;

/** The read side of the DP secret store. */
public interface SecretResolver {

    /**
     * Decrypts for ONE use. Close the result; its buffer is zeroised on close.
     *
     * @throws SecretException not found (also for another tenant's id), purpose mismatch, key unavailable,
     *         or authentication failed
     */
    SecretMaterial resolve(UUID tenantId, UUID secretId, SecretPurpose expected);
}
