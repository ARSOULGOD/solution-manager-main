package com.solace.sm.dp.secret;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The DP secret store: {@link SecretStore} and {@link SecretResolver} over the {@link Envelope} and the
 * {@link SecretRows} port (plan 010 T2.4; D-KEY draft decision K-1).
 *
 * <p><b>A value is write-once.</b> {@link #put} always inserts a new row under a new secret id; changing a
 * credential means a new row and a re-pointed referrer. A read decrypts under whichever key id the row
 * names, which is what makes a read during rotation safe (D-KEY 5.2).
 */
public final class EnvelopeSecretStore implements SecretStore, SecretResolver {

    /**
     * The largest value, in UTF-8 bytes. A private key is a few kilobytes (an RSA-4096 PEM is about 3.3 KB);
     * 16 KiB leaves headroom without letting the table hold arbitrary blobs. Mirrored by {@code V009}'s
     * {@code secret_ciphertext_length} (this plus the 16-byte tag).
     */
    public static final int MAX_VALUE_BYTES = 16384;

    private final SecretRows rows;
    private final MasterKeyRing ring;
    private final Envelope envelope;
    private final Clock clock;

    public EnvelopeSecretStore(SecretRows rows, MasterKeyRing ring, Envelope envelope, Clock clock) {
        this.rows = rows;
        this.ring = ring;
        this.envelope = envelope;
        this.clock = clock;
    }

    @Override
    public UUID put(UUID tenantId, SecretPurpose purpose, char[] value, UUID actor) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(actor, "actor");
        if (purpose == SecretPurpose.CANARY || purpose == SecretPurpose.CONFIG_HASH_KEY) {
            throw new IllegalArgumentException(purpose + " is not stored through put");
        }
        byte[] plain = utf8(value);
        try {
            if (plain.length == 0 || plain.length > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("a secret value is 1 to " + MAX_VALUE_BYTES + " bytes");
            }
            UUID secretId = UUID.randomUUID();
            SealedSecret sealed = envelope.sealSecret(ring.current(), tenantId, secretId, purpose, plain);
            rows.insert(new SecretRow(tenantId, secretId, purpose, sealed, null, clock.instant(), actor));
            return secretId;
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    @Override
    public boolean exists(UUID tenantId, UUID secretId) {
        return rows.exists(tenantId, secretId);
    }

    @Override
    public SecretMaterial resolve(UUID tenantId, UUID secretId, SecretPurpose expected) {
        SecretRow row = rows.find(tenantId, secretId)
                .orElseThrow(() -> new SecretException(SecretException.Code.NOT_FOUND));
        // Defence in depth (I5): a lookup that returns a row for another tenant or id is a miss, and the envelope
        // is opened with the CALLER's tenant and id, never the row's own, so the AAD still refuses a foreign row
        // if this check were ever removed.
        if (!row.tenantId().equals(tenantId) || !row.secretId().equals(secretId)) {
            throw new SecretException(SecretException.Code.NOT_FOUND);
        }
        if (row.purpose() != expected) {
            throw new SecretException(SecretException.Code.PURPOSE_MISMATCH);
        }
        Optional<MasterKey> kek = ring.find(row.sealed().keyId());
        if (kek.isEmpty()) {
            throw new SecretException(SecretException.Code.KEY_UNAVAILABLE, row.sealed().keyId());
        }
        return new SecretMaterial(
                envelope.openSecret(kek.get(), tenantId, secretId, row.purpose(), row.sealed()));
    }

    private static byte[] utf8(char[] value) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        try {
            return Arrays.copyOfRange(encoded.array(), encoded.arrayOffset() + encoded.position(),
                    encoded.arrayOffset() + encoded.limit());
        } finally {
            Arrays.fill(encoded.array(), (byte) 0);
        }
    }
}
