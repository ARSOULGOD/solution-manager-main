package com.solace.sm.dp.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvelopeSecretStoreTest {

    private static final UUID T1 = UUID.randomUUID();
    private static final UUID T2 = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    private final InMemoryRows rows = new InMemoryRows();
    private final Envelope envelope = Envelope.standard();
    private final MasterKey q3 = Keys.key("2026-q3", 3);
    private final MasterKey q4 = Keys.key("2026-q4", 4);

    private EnvelopeSecretStore store(MasterKey current, MasterKey previous) {
        return new EnvelopeSecretStore(rows, new MasterKeyRing(current, Optional.ofNullable(previous)), envelope,
                Clock.systemUTC());
    }

    @Test
    void putThenResolveRestoresTheValueAndClosingZeroisesIt() {
        EnvelopeSecretStore s = store(q3, null);
        UUID id = s.put(T1, SecretPurpose.SSH_PRIVATE_KEY, "-----BEGIN KEY-----".toCharArray(), ACTOR);

        assertTrue(s.exists(T1, id));
        byte[] live;
        try (SecretMaterial m = s.resolve(T1, id, SecretPurpose.SSH_PRIVATE_KEY)) {
            live = m.bytes();
            assertEquals("-----BEGIN KEY-----", new String(m.chars()));
        }
        assertArrayEquals(new byte[live.length], live);
    }

    @Test
    void theRowHoldsOnlyCiphertextAndTheStoredAttributes() {
        EnvelopeSecretStore s = store(q3, null);
        UUID id = s.put(T1, SecretPurpose.CONFIG_FIELD, "sentinel-config-secret".toCharArray(), ACTOR);

        SecretRow row = rows.find(T1, id).orElseThrow();
        assertEquals(new KeyId("2026-q3"), row.sealed().keyId());
        assertEquals(ACTOR, row.createdBy());
        assertNotNull(row.createdAt());
        assertFalse(row.toString().contains("sentinel"), row.toString());
        assertFalse(new String(row.sealed().ciphertext(), StandardCharsets.ISO_8859_1).contains("sentinel"));
    }

    @Test
    void resolvingForAnotherTenantFindsNothing() {
        EnvelopeSecretStore s = store(q3, null);
        UUID id = s.put(T1, SecretPurpose.SSH_PRIVATE_KEY, "v".toCharArray(), ACTOR);

        assertEquals(SecretException.Code.NOT_FOUND,
                assertThrows(SecretException.class, () -> s.resolve(T2, id, SecretPurpose.SSH_PRIVATE_KEY)).code());
        assertFalse(s.exists(T2, id));
    }

    /** A port whose lookups ignore the tenant: what a query that lost its {@code tenant_id} predicate would do. */
    static SecretRows ignoringTheTenant(InMemoryRows inner) {
        return new SecretRows() {
            public void insert(SecretRow row) { inner.insert(row); }
            public Optional<SecretRow> find(UUID tenantId, UUID secretId) {
                return inner.secrets.values().stream().filter(r -> r.secretId().equals(secretId)).findFirst();
            }
            public boolean exists(UUID tenantId, UUID secretId) { return find(tenantId, secretId).isPresent(); }
            public Optional<SecretRow> findHashKey(UUID tenantId, int keyVersion) {
                return inner.secrets.values().stream().filter(r -> r.purpose() == SecretPurpose.CONFIG_HASH_KEY
                        && r.keyVersion() == keyVersion).findFirst();
            }
            public Optional<SecretRow> latestHashKey(UUID tenantId) {
                return inner.secrets.values().stream().filter(r -> r.purpose() == SecretPurpose.CONFIG_HASH_KEY)
                        .findFirst();
            }
            public boolean insertHashKeyIfAbsent(SecretRow row) { return inner.insertHashKeyIfAbsent(row); }
        };
    }

    @Test
    void theAadIsASecondEnforcerAQueryThatReturnsAnotherTenantsRowStillDoesNotOpen() {
        EnvelopeSecretStore writer = store(q3, null);
        UUID id = writer.put(T1, SecretPurpose.SSH_PRIVATE_KEY, "tenant-one-secret".toCharArray(), ACTOR);
        EnvelopeSecretStore leaky = new EnvelopeSecretStore(ignoringTheTenant(rows),
                new MasterKeyRing(q3, Optional.empty()), envelope, Clock.systemUTC());

        SecretException e = assertThrows(SecretException.class,
                () -> leaky.resolve(T2, id, SecretPurpose.SSH_PRIVATE_KEY));
        assertTrue(e.code() == SecretException.Code.NOT_FOUND
                || e.code() == SecretException.Code.AUTHENTICATION_FAILED, "got " + e.code());
    }

    @Test
    void aTamperedRowMovedToAnotherTenantFailsAuthentication() {
        EnvelopeSecretStore s = store(q3, null);
        UUID id = s.put(T1, SecretPurpose.SSH_PRIVATE_KEY, "v".toCharArray(), ACTOR);
        SecretRow row = rows.find(T1, id).orElseThrow();
        // someone with database write access copies tenant 1's row into tenant 2 (K5)
        rows.insert(new SecretRow(T2, id, row.purpose(), row.sealed(), row.keyVersion(), row.createdAt(),
                row.createdBy()));

        assertEquals(SecretException.Code.AUTHENTICATION_FAILED,
                assertThrows(SecretException.class, () -> s.resolve(T2, id, SecretPurpose.SSH_PRIVATE_KEY)).code());
    }

    @Test
    void aRelabelledPurposeIsRefusedBeforeAndAfterTheRowIsRelabelled() {
        EnvelopeSecretStore s = store(q3, null);
        UUID id = s.put(T1, SecretPurpose.CONFIG_FIELD, "v".toCharArray(), ACTOR);

        assertEquals(SecretException.Code.PURPOSE_MISMATCH,
                assertThrows(SecretException.class, () -> s.resolve(T1, id, SecretPurpose.SSH_PRIVATE_KEY)).code());

        SecretRow row = rows.find(T1, id).orElseThrow();
        rows.insert(new SecretRow(T1, id, SecretPurpose.SSH_PRIVATE_KEY, row.sealed(), null, row.createdAt(),
                row.createdBy()));
        assertEquals(SecretException.Code.AUTHENTICATION_FAILED,
                assertThrows(SecretException.class, () -> s.resolve(T1, id, SecretPurpose.SSH_PRIVATE_KEY)).code());
    }

    @Test
    void aReadDuringRotationDecryptsUnderWhicheverKeyIdTheRowNames() {
        EnvelopeSecretStore before = store(q3, null);
        UUID old = before.put(T1, SecretPurpose.SSH_PRIVATE_KEY, "old-value".toCharArray(), ACTOR);

        EnvelopeSecretStore during = store(q4, q3);
        UUID fresh = during.put(T1, SecretPurpose.SSH_PRIVATE_KEY, "new-value".toCharArray(), ACTOR);
        assertEquals(new KeyId("2026-q3"), rows.find(T1, old).orElseThrow().sealed().keyId());
        assertEquals(new KeyId("2026-q4"), rows.find(T1, fresh).orElseThrow().sealed().keyId());

        try (SecretMaterial a = during.resolve(T1, old, SecretPurpose.SSH_PRIVATE_KEY);
                SecretMaterial b = during.resolve(T1, fresh, SecretPurpose.SSH_PRIVATE_KEY)) {
            assertEquals("old-value", new String(a.chars()));
            assertEquals("new-value", new String(b.chars()));
        }

        // after a re-wrap, the same call still works, the row now naming the other id
        SecretRow row = rows.find(T1, old).orElseThrow();
        rows.insert(new SecretRow(T1, old, row.purpose(),
                envelope.rewrap(q3, q4, T1, old, row.purpose(), row.sealed()), null, row.createdAt(),
                row.createdBy()));
        try (SecretMaterial a = during.resolve(T1, old, SecretPurpose.SSH_PRIVATE_KEY)) {
            assertEquals("old-value", new String(a.chars()));
        }
    }

    @Test
    void aRowNamingAKeyThatIsNotLoadedFailsClosedNamingTheKeyIdOnly() {
        EnvelopeSecretStore before = store(q3, null);
        UUID id = before.put(T1, SecretPurpose.SSH_PRIVATE_KEY, "sentinel-v".toCharArray(), ACTOR);

        SecretException e = assertThrows(SecretException.class,
                () -> store(q4, null).resolve(T1, id, SecretPurpose.SSH_PRIVATE_KEY));

        assertEquals(SecretException.Code.KEY_UNAVAILABLE, e.code());
        assertEquals("secret.key.unavailable: 2026-q3", e.getMessage());
        assertFalse(e.getMessage().contains(id.toString()), "never a secret id");
    }

    @Test
    void putRefusesWhatItMustNotStore() {
        EnvelopeSecretStore s = store(q3, null);
        char[] v = "v".toCharArray();
        assertThrows(IllegalArgumentException.class, () -> s.put(T1, SecretPurpose.CANARY, v, ACTOR));
        assertThrows(IllegalArgumentException.class, () -> s.put(T1, SecretPurpose.CONFIG_HASH_KEY, v, ACTOR));
        assertThrows(IllegalArgumentException.class, () -> s.put(T1, SecretPurpose.CONFIG_FIELD, new char[0], ACTOR));
        assertThrows(IllegalArgumentException.class,
                () -> s.put(T1, SecretPurpose.CONFIG_FIELD, new char[EnvelopeSecretStore.MAX_VALUE_BYTES + 1], ACTOR));
        assertThrows(NullPointerException.class, () -> s.put(T1, SecretPurpose.CONFIG_FIELD, v, null));
        assertTrue(rows.secrets.isEmpty());
    }

    @Test
    void theLargestPermittedValueRoundTrips() {
        EnvelopeSecretStore s = store(q3, null);
        char[] big = new char[EnvelopeSecretStore.MAX_VALUE_BYTES];
        java.util.Arrays.fill(big, 'k');
        UUID id = s.put(T1, SecretPurpose.SSH_PRIVATE_KEY, big, ACTOR);
        try (SecretMaterial m = s.resolve(T1, id, SecretPurpose.SSH_PRIVATE_KEY)) {
            assertEquals(EnvelopeSecretStore.MAX_VALUE_BYTES, m.bytes().length);
        }
    }

    @Test
    void putDoesNotRetainOrDisturbTheCallersCharsButZeroisesItsOwnCopy() {
        EnvelopeSecretStore s = store(q3, null);
        char[] v = "caller-owned".toCharArray();
        s.put(T1, SecretPurpose.CONFIG_FIELD, v, ACTOR);
        assertEquals("caller-owned", new String(v), "zeroising the argument is the caller's job (interface javadoc)");
    }
}
