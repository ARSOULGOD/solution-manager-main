package com.solace.sm.dp.secret;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link SecretReferencesImpl} (plan 010 T5.3): a draft may reference only a CONFIG_FIELD secret of its own
 * organization. Referencing any other purpose would have the apply render, say, an SSH private key into a file on a
 * customer host.
 */
class SecretReferencesImplTest {

    private final InMemoryRows rows = new InMemoryRows();
    private final SecretReferencesImpl references = new SecretReferencesImpl(rows);
    private final UUID tenant = UUID.randomUUID();

    private UUID store(UUID owner, SecretPurpose purpose) {
        UUID id = UUID.randomUUID();
        rows.insert(new SecretRow(owner, id, purpose,
                new SealedSecret(new KeyId("k1"), new byte[1], new byte[1], new byte[1], new byte[1], 1),
                purpose == SecretPurpose.CONFIG_HASH_KEY ? 1 : null, Instant.EPOCH,
                purpose == SecretPurpose.CONFIG_HASH_KEY ? null : UUID.randomUUID()));
        return id;
    }

    @Test
    void aConfigFieldSecretOfTheOrganizationIsReferenceable() {
        assertTrue(references.exists(tenant, store(tenant, SecretPurpose.CONFIG_FIELD)));
    }

    @Test
    void aSecretOfAnyOtherPurposeIsNotReferenceableFromConfiguration() {
        assertFalse(references.exists(tenant, store(tenant, SecretPurpose.SSH_PRIVATE_KEY)));
        assertFalse(references.exists(tenant, store(tenant, SecretPurpose.CONFIG_HASH_KEY)));
    }

    @Test
    void anotherOrganizationsSecretIsNotReferenceable() {
        assertFalse(references.exists(tenant, store(UUID.randomUUID(), SecretPurpose.CONFIG_FIELD)));
    }

    @Test
    void aRowThePersistenceLayerReturnsForAnotherOrganizationIsStillNotReferenceable() {
        // Defence in depth (I5): the query is tenant-bound, but a port that ever returned a foreign row must not
        // turn it into a reference. A stub that answers every lookup with the same foreign row proves the check.
        UUID foreignOwner = UUID.randomUUID();
        UUID id = store(foreignOwner, SecretPurpose.CONFIG_FIELD);
        SecretRow foreignRow = rows.find(foreignOwner, id).orElseThrow();
        SecretRows leaky = new SecretRows() {
            @Override
            public void insert(SecretRow row) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Optional<SecretRow> find(UUID tenantId, UUID secretId) {
                return java.util.Optional.of(foreignRow);
            }

            @Override
            public boolean exists(UUID tenantId, UUID secretId) {
                return true;
            }

            @Override
            public java.util.Optional<SecretRow> findHashKey(UUID tenantId, int keyVersion) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Optional<SecretRow> latestHashKey(UUID tenantId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean insertHashKeyIfAbsent(SecretRow row) {
                throw new UnsupportedOperationException();
            }
        };

        assertFalse(new SecretReferencesImpl(leaky).exists(tenant, id));
    }

    @Test
    void anUnknownIdIsNotReferenceable() {
        assertFalse(references.exists(tenant, UUID.randomUUID()));
    }
}
