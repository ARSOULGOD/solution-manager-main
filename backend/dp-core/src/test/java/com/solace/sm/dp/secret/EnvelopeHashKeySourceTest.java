package com.solace.sm.dp.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.HashKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D-KEY 7.2: the per-tenant hash key, minted lazily as an envelope-encrypted secret. */
class EnvelopeHashKeySourceTest {

    private static final TenantId T1 = new TenantId(UUID.randomUUID());
    private static final TenantId T2 = new TenantId(UUID.randomUUID());

    private final InMemoryRows rows = new InMemoryRows();
    private final Envelope envelope = Envelope.standard();
    private final MasterKey q3 = Keys.key("2026-q3", 3);
    private final MasterKey q4 = Keys.key("2026-q4", 4);

    private EnvelopeHashKeySource source(MasterKey current, MasterKey previous) {
        return new EnvelopeHashKeySource(rows, new MasterKeyRing(current, Optional.ofNullable(previous)), envelope,
                Clock.systemUTC());
    }

    @Test
    void theFirstUseMintsVersionOneAsASystemActionAndLaterUsesReturnTheSameKey() {
        EnvelopeHashKeySource s = source(q3, null);

        byte[] first;
        try (HashKey k = s.current(T1)) {
            assertEquals(1, k.version());
            assertEquals(32, k.material().length);
            first = k.material().clone();
        }
        try (HashKey k = s.current(T1)) {
            assertArrayEquals(first, k.material(), "minted once, not per call");
        }

        SecretRow row = rows.latestHashKey(T1.value()).orElseThrow();
        assertEquals(SecretPurpose.CONFIG_HASH_KEY, row.purpose());
        assertEquals(1, row.keyVersion());
        assertNull(row.createdBy(), "a lazy mint has no operator behind it");
        assertEquals(1, rows.secrets.size());
    }

    @Test
    void eachTenantHasItsOwnKey() {
        EnvelopeHashKeySource s = source(q3, null);

        try (HashKey a = s.current(T1); HashKey b = s.current(T2)) {
            assertFalse(java.util.Arrays.equals(a.material(), b.material()));
        }
    }

    @Test
    void theRowHoldsOnlyCiphertext() {
        EnvelopeHashKeySource s = source(q3, null);
        String keyBase64;
        try (HashKey k = s.current(T1)) {
            keyBase64 = java.util.Base64.getEncoder().encodeToString(k.material());
        }

        SecretRow row = rows.latestHashKey(T1.value()).orElseThrow();
        assertFalse(new String(row.sealed().ciphertext(), StandardCharsets.ISO_8859_1).contains(keyBase64));
        assertFalse(row.toString().contains(keyBase64));
        assertFalse(s.toString().contains(keyBase64));
    }

    @Test
    void twoCallersRacingToMintBothEndUpWithTheWinnersKey() {
        EnvelopeHashKeySource winnerSide = source(q3, null);
        byte[] winnersKey;
        try (HashKey k = winnerSide.current(T1)) {
            winnersKey = k.material().clone();
        }
        // The loser read "no key yet" and is about to insert its own: it must lose, discard it, and use the winner's.
        InMemoryRows empty = new InMemoryRows();
        SecretRow winner = rows.latestHashKey(T1.value()).orElseThrow();
        empty.racingHashKey = winner;
        EnvelopeHashKeySource loser = new EnvelopeHashKeySource(empty,
                new MasterKeyRing(q3, Optional.empty()), envelope, Clock.systemUTC());

        try (HashKey k = loser.current(T1)) {
            assertArrayEquals(winnersKey, k.material());
            assertEquals(1, k.version());
        }
        assertEquals(1, empty.secrets.size(), "the loser's key was discarded, not stored");
    }

    @Test
    void aKekRotationChangesTheWrapButNotTheKey() {
        EnvelopeHashKeySource before = source(q3, null);
        byte[] key;
        try (HashKey k = before.current(T1)) {
            key = k.material().clone();
        }
        SecretRow old = rows.latestHashKey(T1.value()).orElseThrow();
        // what KeyRotationMain does to every row: a new wrap, the value ciphertext untouched
        SealedSecret moved = envelope.rewrap(q3, q4, T1.value(), old.secretId(), old.purpose(), old.sealed());
        rows.secrets.put(T1.value() + "/" + old.secretId(), new SecretRow(old.tenantId(), old.secretId(),
                old.purpose(), moved, old.keyVersion(), old.createdAt(), old.createdBy()));

        try (HashKey k = source(q4, null).current(T1)) {
            assertArrayEquals(key, k.material(), "the hash key survives a KEK rotation unchanged");
            assertEquals(1, k.version());
        }
    }

    @Test
    void aVersionIsNeverMintedByAskingForIt() {
        EnvelopeHashKeySource s = source(q3, null);

        assertTrue(s.version(T1, 1).isEmpty());
        assertTrue(rows.secrets.isEmpty(), "version() reads; only current() mints");
        s.current(T1).close();
        assertTrue(s.version(T1, 1).isPresent());
        assertTrue(s.version(T1, 2).isEmpty());
        assertTrue(s.version(T2, 1).isEmpty(), "another tenant's key is not this tenant's version");
    }

    @Test
    void aKeyWrappedUnderAnUnloadedKekIsAbsentToVersionAndUnavailableToCurrent() {
        source(q3, null).current(T1).close();
        EnvelopeHashKeySource withoutQ3 = source(q4, null);

        assertTrue(withoutQ3.version(T1, 1).isEmpty(), "cannot be loaded, so cannot be checked");
        SecretException e = assertThrows(SecretException.class, () -> withoutQ3.current(T1));
        assertEquals(SecretException.Code.KEY_UNAVAILABLE, e.code());
    }

    @Test
    void aRowMovedToAnotherTenantFailsAuthenticationRatherThanYieldingThatTenantsKey() {
        source(q3, null).current(T1).close();
        SecretRow row = rows.latestHashKey(T1.value()).orElseThrow();
        rows.secrets.put(T2.value() + "/" + row.secretId(), new SecretRow(T2.value(), row.secretId(), row.purpose(),
                row.sealed(), row.keyVersion(), row.createdAt(), row.createdBy()));

        SecretException e = assertThrows(SecretException.class, () -> source(q3, null).version(T2, 1));
        assertEquals(SecretException.Code.AUTHENTICATION_FAILED, e.code());
        assertNotEquals(null, e.getMessage());
    }

    @Test
    void aQueryThatReturnsAnotherTenantsKeyRowDoesNotYieldItsKey() {
        EnvelopeHashKeySource writer = source(q3, null);
        try (HashKey ignored = writer.current(T1)) {
            // tenant one now has a key row
        }
        EnvelopeHashKeySource leaky = new EnvelopeHashKeySource(EnvelopeSecretStoreTest.ignoringTheTenant(rows),
                new MasterKeyRing(q3, Optional.empty()), envelope, Clock.systemUTC());

        assertThrows(SecretException.class, () -> leaky.current(T2));
        assertTrue(leaky.version(T2, 1).isEmpty(), "another tenant's row is cannot-be-checked, never its key");
    }
}
