package com.solace.sm.dp.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.secret.SecretCompare;
import com.solace.sm.dp.api.secret.SecretCompare.Outcome;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** D-ACCEPT's V-4: compare in memory, report only same or differs, keep nothing. */
class SecretCompareImplTest {

    private static final UUID T1 = UUID.randomUUID();
    private static final UUID T2 = UUID.randomUUID();
    private static final String STORED = "stored-secret-sentinel-3d9a";

    private final InMemoryRows rows = new InMemoryRows();
    private final EnvelopeSecretStore store = new EnvelopeSecretStore(rows,
            new MasterKeyRing(Keys.key("k1", 3), Optional.empty()), Envelope.standard(), Clock.systemUTC());
    private final SecretCompareImpl compare = new SecretCompareImpl(store);
    private final UUID ref = store.put(T1, SecretPurpose.CONFIG_FIELD, STORED.toCharArray(), UUID.randomUUID());

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anEqualCandidateIsSameAndADifferentOneDiffers() {
        assertEquals(Outcome.SAME, compare.compare(T1, ref, utf8(STORED)));
        assertEquals(Outcome.DIFFERS, compare.compare(T1, ref, utf8("a different value")));
        assertEquals(Outcome.DIFFERS, compare.compare(T1, ref, utf8(STORED + "x")), "a longer candidate with the same prefix");
        assertEquals(Outcome.DIFFERS, compare.compare(T1, ref, new byte[0]));
    }

    @Test
    void anotherTenantsSecretAnAbsentOneOrAnotherPurposeIsUnavailableNeverDiffers() {
        assertEquals(Outcome.UNAVAILABLE, compare.compare(T2, ref, utf8(STORED)), "I5: a foreign tenant finds nothing");
        assertEquals(Outcome.UNAVAILABLE, compare.compare(T1, UUID.randomUUID(), utf8(STORED)));
        UUID sshKey = store.put(T1, SecretPurpose.SSH_PRIVATE_KEY, "-----BEGIN-----".toCharArray(), UUID.randomUUID());
        assertEquals(Outcome.UNAVAILABLE, compare.compare(T1, sshKey, utf8("-----BEGIN-----")),
                "only a config-field secret is comparable: a read-back must never probe an SSH key");
    }

    /** The stored material is closed (its buffer zeroed) before compare returns, whichever way it answers. */
    @Test
    void theStoredMaterialIsZeroedBeforeTheAnswerIsReturned() {
        AtomicReference<byte[]> opened = new AtomicReference<>();
        SecretResolver spying = (tenant, id, purpose) -> {
            SecretMaterial material = store.resolve(tenant, id, purpose);
            opened.set(material.bytes());
            return material;
        };
        assertEquals(Outcome.SAME, new SecretCompareImpl(spying).compare(T1, ref, utf8(STORED)));
        assertEquals(STORED.length(), opened.get().length);
        assertTrue(Arrays.equals(new byte[opened.get().length], opened.get()), "the buffer it opened is zero afterwards");
    }

    @Test
    void thePortHasNoOperationThatReturnsOrStoresAValue() {
        for (var method : SecretCompare.class.getDeclaredMethods()) {
            assertEquals(Outcome.class, method.getReturnType(), method.getName());
        }
        assertEquals(1, SecretCompare.class.getDeclaredMethods().length);
        assertFalse(compare.toString().contains(STORED));
    }
}
