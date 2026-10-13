package com.solace.sm.dp.apply.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** OR-32: the keyed hash. D-KEY 7.2 (spelling, per-tenant key) and 7.3 (verification across key versions). */
class HmacConfigHasherTest {

    private static final TenantId T1 = new TenantId(UUID.randomUUID());
    private static final TenantId T2 = new TenantId(UUID.randomUUID());
    private static final byte[] BYTES = "password: hunter2\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void theSameBytesUnderTheSameKeyHashIdentically() {
        ConfigHasher h = new HmacConfigHasher(new InMemoryHashKeys());

        assertEquals(h.hash(T1, BYTES), h.hash(T1, BYTES.clone()));
        assertNotEquals(h.hash(T1, BYTES), h.hash(T1, "password: hunter3\n".getBytes(StandardCharsets.UTF_8)));
    }

    /** A holder of the hash alone cannot test a guessed secret: a guess hashes differently without the key. */
    @Test
    void aDifferentKeyGivesADifferentHashForTheSameBytes() {
        ConfigHasher keyed = new HmacConfigHasher(new InMemoryHashKeys());
        ConfigHasher otherKey = new HmacConfigHasher(new HashKeySource() {
            @Override
            public HashKey current(TenantId tenant) {
                return new HashKey(1, new byte[32]);
            }

            @Override
            public Optional<HashKey> version(TenantId tenant, int keyVersion) {
                return Optional.of(new HashKey(keyVersion, new byte[32]));
            }
        });

        assertNotEquals(keyed.hash(T1, BYTES), otherKey.hash(T1, BYTES));
    }

    /** m5: two tenants staging identical bytes must not be able to tell that the other did. */
    @Test
    void twoTenantsNeverShareAHashForTheSameBytes() {
        ConfigHasher h = new HmacConfigHasher(new InMemoryHashKeys());

        assertNotEquals(h.hash(T1, BYTES), h.hash(T2, BYTES));
        assertFalse(h.verifies(T2, h.hash(T1, BYTES), BYTES),
                "tenant 2's key does not verify a hash made under tenant 1's");
    }

    @Test
    void verificationAcceptsAnyStillLoadableKeyVersion() {
        InMemoryHashKeys keys = new InMemoryHashKeys();
        String underV1 = new HmacConfigHasher(keys).hash(T1, BYTES);

        keys.currentIs(2);
        ConfigHasher h = new HmacConfigHasher(keys);

        assertTrue(h.hash(T1, BYTES).startsWith("hmacsha256v2:"), "new hashes use the current version");
        assertTrue(h.verifies(T1, underV1, BYTES), "a version-1 hash still verifies while version 1 is loadable");
        assertTrue(h.verifies(T1, h.hash(T1, BYTES), BYTES));
        assertFalse(h.verifies(T1, underV1, "other bytes".getBytes(StandardCharsets.UTF_8)));
    }

    /** The resumed apply of a crash: its intent was bound under a key version that is no longer current. */
    @Test
    void aResumedApplyVerifiesAHashBoundUnderAPreviousKeyVersion() {
        InMemoryHashKeys keys = new InMemoryHashKeys();
        String boundBeforeTheCrash = new HmacConfigHasher(keys).hash(T1, BYTES);
        keys.currentIs(2);

        assertTrue(new HmacConfigHasher(keys).verifies(T1, boundBeforeTheCrash, BYTES));
    }

    @Test
    void theHashStringNamesItsKeyVersionButNeverTheKey() {
        InMemoryHashKeys keys = new InMemoryHashKeys();
        String hash = new HmacConfigHasher(keys).hash(T1, BYTES);

        assertTrue(Pattern.matches("hmacsha256v1:[0-9a-f]{64}", hash), hash);
        try (HashKey k = keys.current(T1)) {
            String keyHex = java.util.HexFormat.of().formatHex(k.material());
            assertFalse(hash.contains(keyHex));
            assertFalse(k.toString().contains(keyHex));
        }
    }

    /** A version that cannot be loaded is "could not check", which the ladder must not read as "diverged". */
    @Test
    void aHashUnderAVersionThatCannotBeLoadedIsUnverifiableNotAMismatch() {
        InMemoryHashKeys keys = new InMemoryHashKeys();
        String hash = new HmacConfigHasher(keys).hash(T1, BYTES);
        keys.forget(1);

        ConfigHashUnverifiableException e = assertThrows(ConfigHashUnverifiableException.class,
                () -> new HmacConfigHasher(keys).verifies(T1, hash, BYTES));
        assertEquals(1, e.keyVersion());
        assertEquals("config.hash.unverifiable: key version 1", e.getMessage(),
                "a classifier and a key version, nothing else");
        assertEquals(null, e.getCause());
    }

    @Test
    void aHashNotSpelledInThisGrammarIsAMismatchNotAnError() {
        ConfigHasher h = new HmacConfigHasher(new InMemoryHashKeys());

        assertFalse(h.verifies(T1, "sha256:" + "0".repeat(64), BYTES));
        assertFalse(h.verifies(T1, "hmac-sha256:1:" + "0".repeat(64), BYTES));
        assertFalse(h.verifies(T1, "hmacsha256v0:" + "0".repeat(64), BYTES), "versions start at 1");
        assertFalse(h.verifies(T1, null, BYTES));
    }

    @Test
    void hashingClosesAndZeroisesTheKeyItResolved() {
        java.util.List<HashKey> resolved = new java.util.ArrayList<>();
        InMemoryHashKeys inner = new InMemoryHashKeys();
        ConfigHasher h = new HmacConfigHasher(new HashKeySource() {
            @Override
            public HashKey current(TenantId tenant) {
                HashKey k = inner.current(tenant);
                resolved.add(k);
                return k;
            }

            @Override
            public Optional<HashKey> version(TenantId tenant, int keyVersion) {
                return inner.version(tenant, keyVersion).map(k -> {
                    resolved.add(k);
                    return k;
                });
            }
        });

        String hash = h.hash(T1, BYTES);
        h.verifies(T1, hash, BYTES);

        assertEquals(2, resolved.size());
        for (HashKey k : resolved) {
            assertThrows(IllegalStateException.class, k::material, "a resolved key is closed after one use");
        }
    }
}
