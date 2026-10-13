package com.solace.sm.dp.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D-KEY §3: the two-level envelope, its AAD, and what its failures are allowed to say. */
class EnvelopeTest {

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID SECRET_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECRET_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final byte[] PLAINTEXT = "sentinel-PLAINTEXT-value-7f3a".getBytes(StandardCharsets.UTF_8);

    private final Envelope envelope = Envelope.standard();
    private final MasterKey kek = Keys.key("k1", 1);

    @Test
    void roundTripUnderTheSameKeyRestoresTheValue() {
        SealedSecret sealed = envelope.sealSecret(kek, TENANT_A, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, PLAINTEXT);

        assertEquals(new KeyId("k1"), sealed.keyId());
        assertArrayEquals(PLAINTEXT,
                envelope.openSecret(kek, TENANT_A, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, sealed));
        assertFalse(Arrays.equals(PLAINTEXT, sealed.ciphertext()), "the stored bytes are not the value");
    }

    @Test
    void theStoredShapeIsTheFixedOneTheSchemaChecks() {
        SealedSecret sealed = envelope.sealSecret(kek, TENANT_A, SECRET_1, SecretPurpose.CONFIG_FIELD, PLAINTEXT);

        assertEquals(48, sealed.wrappedDek().length, "32-byte DEK plus the 16-byte tag (measured, D-KEY 2.2)");
        assertEquals(12, sealed.dekIv().length);
        assertEquals(12, sealed.valueIv().length);
        assertEquals(PLAINTEXT.length + 16, sealed.ciphertext().length);
        assertEquals(1, sealed.aadVersion());
    }

    @Test
    void aCiphertextMovedToAnotherTenantOrRowFailsAuthentication() {
        SealedSecret sealed = envelope.sealSecret(kek, TENANT_A, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, PLAINTEXT);

        assertAuthenticationFails(() ->
                envelope.openSecret(kek, TENANT_B, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, sealed), "tenant");
        assertAuthenticationFails(() ->
                envelope.openSecret(kek, TENANT_A, SECRET_2, SecretPurpose.SSH_PRIVATE_KEY, sealed), "secret id");
        assertAuthenticationFails(() ->
                envelope.openSecret(kek, TENANT_A, SECRET_1, SecretPurpose.CONFIG_FIELD, sealed), "purpose");
        // same-length purpose names (15 bytes each): the AAD must carry the NAME, not just its length
        assertAuthenticationFails(() ->
                envelope.openSecret(kek, TENANT_A, SECRET_1, SecretPurpose.CONFIG_HASH_KEY, sealed),
                "a purpose of the same length");
    }

    @Test
    void theWrapAndTheValueEachAuthenticateOnTheirOwn() {
        SealedSecret sealed = envelope.sealSecret(kek, TENANT_A, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, PLAINTEXT);
        SealedSecret other = envelope.sealSecret(kek, TENANT_A, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, PLAINTEXT);

        // a wrap from one sealing with the value of another: each half is individually valid, the pair is not
        SealedSecret spliced = new SealedSecret(sealed.keyId(), other.wrappedDek(), other.dekIv(),
                sealed.valueIv(), sealed.ciphertext(), sealed.aadVersion());
        assertAuthenticationFails(() ->
                envelope.openSecret(kek, TENANT_A, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, spliced), "spliced wrap");

        byte[] flipped = sealed.ciphertext().clone();
        flipped[0] ^= 1;
        SealedSecret tampered = new SealedSecret(sealed.keyId(), sealed.wrappedDek(), sealed.dekIv(),
                sealed.valueIv(), flipped, sealed.aadVersion());
        assertAuthenticationFails(() ->
                envelope.openSecret(kek, TENANT_A, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, tampered), "bit flip");
    }

    @Test
    void aWrongKeyFailsAuthentication() {
        SealedSecret sealed = envelope.sealSecret(kek, TENANT_A, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, PLAINTEXT);

        assertAuthenticationFails(() -> envelope.openSecret(Keys.key("k1", 2), TENANT_A, SECRET_1,
                SecretPurpose.SSH_PRIVATE_KEY, sealed), "same id, different bytes");
    }

    @Test
    void everyEncryptionUsesAFreshIv() {
        Set<String> ivs = new HashSet<>();
        int rounds = 300;
        for (int i = 0; i < rounds; i++) {
            SealedSecret s = envelope.sealSecret(kek, TENANT_A, SECRET_1, SecretPurpose.CONFIG_FIELD, PLAINTEXT);
            ivs.add(HexFormat.of().formatHex(s.valueIv()));
            ivs.add(HexFormat.of().formatHex(s.dekIv()));
        }
        assertEquals(rounds * 2, ivs.size(),
                "a repeated IV under one key is a forgery vector (Cipher javadoc); the JDK guards only one Cipher object");
    }

    @Test
    void sealingTheSameValueTwiceNeverRepeatsAnyStoredByte() {
        SealedSecret a = envelope.sealSecret(kek, TENANT_A, SECRET_1, SecretPurpose.CONFIG_FIELD, PLAINTEXT);
        SealedSecret b = envelope.sealSecret(kek, TENANT_A, SECRET_1, SecretPurpose.CONFIG_FIELD, PLAINTEXT);

        assertFalse(Arrays.equals(a.ciphertext(), b.ciphertext()));
        assertFalse(Arrays.equals(a.wrappedDek(), b.wrappedDek()));
    }

    @Test
    void rewrapMovesTheWrapToTheNewKeyAndLeavesTheValueCiphertextByteIdentical() {
        SealedSecret sealed = envelope.sealSecret(kek, TENANT_A, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, PLAINTEXT);
        MasterKey next = Keys.key("k2", 2);

        SealedSecret moved = envelope.rewrap(kek, next, TENANT_A, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, sealed);

        assertEquals(new KeyId("k2"), moved.keyId());
        assertFalse(Arrays.equals(sealed.wrappedDek(), moved.wrappedDek()));
        assertFalse(Arrays.equals(sealed.dekIv(), moved.dekIv()), "a re-wrap is a new GCM invocation: fresh IV");
        assertArrayEquals(sealed.valueIv(), moved.valueIv());
        assertArrayEquals(sealed.ciphertext(), moved.ciphertext());
        assertArrayEquals(PLAINTEXT,
                envelope.openSecret(next, TENANT_A, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, moved));
    }

    @Test
    void rewrapIsAuthenticatedToo() {
        SealedSecret sealed = envelope.sealSecret(kek, TENANT_A, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, PLAINTEXT);

        assertAuthenticationFails(() -> envelope.rewrap(kek, Keys.key("k2", 2), TENANT_B, SECRET_1,
                SecretPurpose.SSH_PRIVATE_KEY, sealed), "re-wrapping under another tenant");
    }

    @Test
    void theCanaryIsABoundEnvelopeOfItsOwnKeyId() {
        SealedSecret canary = envelope.sealCanary(kek, "canary-plaintext".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals("canary-plaintext".getBytes(StandardCharsets.UTF_8), envelope.openCanary(kek, canary));
        assertAuthenticationFails(() -> envelope.openCanary(Keys.key("k1", 9), canary), "wrong key");
        SealedSecret relabelled = new SealedSecret(new KeyId("k2"), canary.wrappedDek(), canary.dekIv(),
                canary.valueIv(), canary.ciphertext(), canary.aadVersion());
        assertAuthenticationFails(() -> envelope.openCanary(Keys.key("k2", 1), relabelled),
                "a canary moved to another key id is not that key's canary");
    }

    @Test
    void noExceptionMessageOrToStringCarriesPlaintextOrCiphertext() {
        SealedSecret sealed = envelope.sealSecret(kek, TENANT_A, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, PLAINTEXT);
        SecretException failure = assertThrows(SecretException.class, () ->
                envelope.openSecret(kek, TENANT_B, SECRET_1, SecretPurpose.SSH_PRIVATE_KEY, sealed));

        assertEquals("secret.authentication.failed", failure.getMessage());
        assertNull(failure.getCause(), "a chained cause's message is a leak path");
        assertEquals(SecretException.Code.AUTHENTICATION_FAILED, failure.code());

        for (String text : new String[] {failure.getMessage(), failure.toString(), sealed.toString(),
                kek.toString()}) {
            assertNoSecretBytes(text, sealed);
        }
    }

    static void assertNoSecretBytes(String text, SealedSecret sealed) {
        assertFalse(text.contains("sentinel-PLAINTEXT"), text);
        for (byte[] part : new byte[][] {sealed.ciphertext(), sealed.wrappedDek(), sealed.dekIv(), sealed.valueIv()}) {
            assertFalse(text.contains(Base64.getEncoder().encodeToString(part)), text);
            assertFalse(text.contains(HexFormat.of().formatHex(part)), text);
        }
        assertFalse(text.contains(Keys.base64(1)), "the key itself");
    }

    @Test
    void aMasterKeyRefusesTheWrongLengthWithoutNamingTheValue() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MasterKey.of(new KeyId("k"), new byte[7]));
        assertTrue(e.getMessage().contains("32"), e.getMessage());
        assertNotEquals("7", e.getMessage());
    }

    private void assertAuthenticationFails(org.junit.jupiter.api.function.Executable e, String because) {
        SecretException ex = assertThrows(SecretException.class, e, because);
        assertEquals(SecretException.Code.AUTHENTICATION_FAILED, ex.code(), because);
    }
}
