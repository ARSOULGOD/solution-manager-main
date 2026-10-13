package com.solace.sm.dp.secret;

import java.util.UUID;

/** The write side of the DP secret store (plan 010 T2.4; D-KEY). */
public interface SecretStore {

    /**
     * Encrypts and stores; returns the reference. The value is zeroised by the caller after this returns.
     *
     * @throws IllegalArgumentException for a purpose that is not stored this way ({@code CANARY},
     *         {@code CONFIG_HASH_KEY}), or an empty or oversized value
     */
    UUID put(UUID tenantId, SecretPurpose purpose, char[] value, UUID actor);

    boolean exists(UUID tenantId, UUID secretId);
}
