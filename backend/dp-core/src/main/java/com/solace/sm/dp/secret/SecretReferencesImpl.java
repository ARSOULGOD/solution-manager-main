package com.solace.sm.dp.secret;

import com.solace.sm.dp.api.config.SecretReferences;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link SecretReferences} over the secret persistence port (plan 010 T5.3). Reads the sealed row to learn its
 * purpose and nothing else: no key is loaded and nothing is decrypted.
 */
public final class SecretReferencesImpl implements SecretReferences {

    private final SecretRows rows;

    public SecretReferencesImpl(SecretRows rows) {
        this.rows = Objects.requireNonNull(rows, "rows");
    }

    @Override
    public boolean exists(UUID organizationId, UUID secretRef) {
        Objects.requireNonNull(organizationId, "organization");
        Objects.requireNonNull(secretRef, "secretRef");
        return rows.find(organizationId, secretRef).filter(row -> row.tenantId().equals(organizationId))
                .map(row -> row.purpose() == SecretPurpose.CONFIG_FIELD).orElse(false);
    }
}
