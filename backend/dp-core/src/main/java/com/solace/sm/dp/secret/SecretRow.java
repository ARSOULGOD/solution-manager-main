package com.solace.sm.dp.secret;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code sm_secret.secret} row. {@code keyVersion} is non-null exactly for {@code CONFIG_HASH_KEY}; {@code
 * createdBy} is null only for that purpose (a system mint). Ciphertext only: the string form names no value.
 */
public record SecretRow(UUID tenantId, UUID secretId, SecretPurpose purpose, SealedSecret sealed,
        Integer keyVersion, Instant createdAt, UUID createdBy) {

    @Override
    public String toString() {
        return "SecretRow[purpose=" + purpose + ", sealed=" + sealed + "]";
    }
}
