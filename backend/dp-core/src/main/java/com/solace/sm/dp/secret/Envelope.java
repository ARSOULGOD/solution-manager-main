package com.solace.sm.dp.secret;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The two-level envelope of D-KEY 3 (draft decision K-1): a fresh 32-byte data key per secret encrypts
 * the value, and the key-encryption key wraps the data key, both with {@code AES/GCM/NoPadding}, 96-bit
 * random IVs and 128-bit tags (all from the JDK's own provider: no dependency, {@code I8}).
 *
 * <p><b>The same AAD feeds both operations</b> (D-KEY 3.3): {@code aad_version || tenant_id || secret_id ||
 * len(purpose) || purpose}. A ciphertext moved to another tenant, row or purpose therefore fails with
 * {@link SecretException.Code#AUTHENTICATION_FAILED}. The key id is deliberately NOT in it: a re-wrap changes
 * the key id and the value ciphertext must stay byte-identical.
 *
 * <p><b>IV uniqueness is ours</b> (D-KEY 2.2): the JDK's reuse guard is per {@code Cipher} object, so two
 * fresh ciphers with one key and IV produce identical ciphertext. {@link #freshIv()} draws every IV from a
 * {@link SecureRandom}; a data key encrypts one message, so the IV that matters is the wrap IV, and NIST's
 * 2^32-invocation bound per key is about four million full rotations away at 1,000 secrets.
 *
 * <p>Failure messages carry a classifier and nothing else: no plaintext, no ciphertext, no AAD, and no
 * chained cause.
 */
public final class Envelope {

    /** The AAD layout this build writes and reads. Rows name their own, so a layout can change without a flag day. */
    public static final int AAD_VERSION = 1;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int DEK_BYTES = 32;

    private final SecureRandom random;

    private Envelope(SecureRandom random) {
        this.random = random;
    }

    /** The production instance: the platform's default {@link SecureRandom}. */
    public static Envelope standard() {
        return new Envelope(new SecureRandom());
    }

    // ---- secrets -------------------------------------------------------------------------------------------

    public SealedSecret sealSecret(MasterKey kek, UUID tenantId, UUID secretId, SecretPurpose purpose,
            byte[] plaintext) {
        return seal(kek, secretAad(AAD_VERSION, tenantId, secretId, purpose), AAD_VERSION, plaintext);
    }

    /** @return the plaintext; the caller zeroes it */
    public byte[] openSecret(MasterKey kek, UUID tenantId, UUID secretId, SecretPurpose purpose,
            SealedSecret sealed) {
        return open(kek, secretAad(sealed.aadVersion(), tenantId, secretId, purpose), sealed);
    }

    /**
     * Moves the data key's wrap from {@code from} to {@code to} under a fresh IV. The value is never
     * touched: its IV and ciphertext are the same array contents, and no plaintext is ever produced.
     */
    public SealedSecret rewrap(MasterKey from, MasterKey to, UUID tenantId, UUID secretId, SecretPurpose purpose,
            SealedSecret sealed) {
        byte[] aad = secretAad(sealed.aadVersion(), tenantId, secretId, purpose);
        byte[] dek = unwrap(from, aad, sealed);
        try {
            byte[] dekIv = freshIv();
            byte[] wrapped = gcm(Cipher.ENCRYPT_MODE, to.bytes(), dekIv, aad, dek);
            return new SealedSecret(to.id(), wrapped, dekIv, sealed.valueIv(), sealed.ciphertext(),
                    sealed.aadVersion());
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    // ---- the canary ----------------------------------------------------------------------------------------

    /** An envelope bound to its key id (there is no tenant or secret id), D-KEY 4.3. */
    public SealedSecret sealCanary(MasterKey kek, byte[] plaintext) {
        return seal(kek, canaryAad(AAD_VERSION, kek.id()), AAD_VERSION, plaintext);
    }

    public byte[] openCanary(MasterKey kek, SealedSecret sealed) {
        return open(kek, canaryAad(sealed.aadVersion(), sealed.keyId()), sealed);
    }

    // ---- mechanics -----------------------------------------------------------------------------------------

    private SealedSecret seal(MasterKey kek, byte[] aad, int aadVersion, byte[] plaintext) {
        byte[] dek = new byte[DEK_BYTES];
        random.nextBytes(dek);
        try {
            byte[] valueIv = freshIv();
            byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, dek, valueIv, aad, plaintext);
            byte[] dekIv = freshIv();
            byte[] wrapped = gcm(Cipher.ENCRYPT_MODE, kek.bytes(), dekIv, aad, dek);
            return new SealedSecret(kek.id(), wrapped, dekIv, valueIv, ciphertext, aadVersion);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    private byte[] open(MasterKey kek, byte[] aad, SealedSecret sealed) {
        byte[] dek = unwrap(kek, aad, sealed);
        try {
            return gcm(Cipher.DECRYPT_MODE, dek, sealed.valueIv(), aad, sealed.ciphertext());
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    private byte[] unwrap(MasterKey kek, byte[] aad, SealedSecret sealed) {
        return gcm(Cipher.DECRYPT_MODE, kek.bytes(), sealed.dekIv(), aad, sealed.wrappedDek());
    }

    /** 96 random bits, a new array every call. */
    private byte[] freshIv() {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        return iv;
    }

    private static byte[] gcm(int mode, byte[] key, byte[] iv, byte[] aad, byte[] input) {
        Objects.requireNonNull(key);
        SecretKeySpec spec = new SecretKeySpec(key, "AES");
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, spec, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (AEADBadTagException e) {
            // not chained, and not described: see the class javadoc
            throw new SecretException(SecretException.Code.AUTHENTICATION_FAILED);
        } catch (GeneralSecurityException | RuntimeException e) {
            // A malformed row (a wrong-length IV or key) is also a row that does not authenticate.
            throw new SecretException(SecretException.Code.AUTHENTICATION_FAILED);
        }
    }

    private static byte[] secretAad(int aadVersion, UUID tenantId, UUID secretId, SecretPurpose purpose) {
        byte[] name = purpose.name().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(1 + 16 + 16 + 1 + name.length)
                .put((byte) aadVersion)
                .putLong(tenantId.getMostSignificantBits()).putLong(tenantId.getLeastSignificantBits())
                .putLong(secretId.getMostSignificantBits()).putLong(secretId.getLeastSignificantBits())
                .put((byte) name.length).put(name)
                .array();
    }

    private static byte[] canaryAad(int aadVersion, KeyId keyId) {
        byte[] purpose = SecretPurpose.CANARY.name().getBytes(StandardCharsets.UTF_8);
        byte[] id = keyId.value().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(1 + 1 + purpose.length + 1 + id.length)
                .put((byte) aadVersion)
                .put((byte) purpose.length).put(purpose)
                .put((byte) id.length).put(id)
                .array();
    }
}
