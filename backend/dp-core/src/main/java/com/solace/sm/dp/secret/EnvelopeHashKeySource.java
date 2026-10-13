package com.solace.sm.dp.secret;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.HashKey;
import com.solace.sm.dp.apply.runtime.HashKeySource;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * OR-32's per-tenant hash key, held as one more envelope-encrypted secret of purpose
 * {@link SecretPurpose#CONFIG_HASH_KEY} (D-KEY 7.1 option (b)): one root of trust, and a KEK rotation re-wraps
 * its data key without changing the key or any hash.
 *
 * <p><b>Minted lazily, as a system action, by whichever caller needs it first</b> (D-KEY 7.2): the first caller
 * may be the adapter's read-back during a health poll, a path with no operator behind it, so the row's
 * {@code created_by} is null (a CHECK permits that for this purpose only). Two callers racing both try
 * {@code INSERT ... ON CONFLICT (tenant_id, key_version) DO NOTHING}; the loser discards its key and reads the
 * winner's, so every caller ends up with the same key.
 *
 * <p><b>v1 mints version 1 and no other</b> (owner ruling OR-85, OQ-5: no hash-key rotation). The version is in
 * the hash's spelling, so adding rotation later changes no stored byte.
 *
 * <p><b>Not cached</b> (D-KEY 7.2): each call reads the row and opens the envelope for ONE use; the caller
 * closes the {@link HashKey}. The key is 32 random bytes, stored as base64 text.
 */
public final class EnvelopeHashKeySource implements HashKeySource {

    /** The only version v1 mints. */
    public static final int FIRST_VERSION = 1;

    private static final int KEY_BYTES = 32;

    private final SecretRows rows;
    private final MasterKeyRing ring;
    private final Envelope envelope;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public EnvelopeHashKeySource(SecretRows rows, MasterKeyRing ring, Envelope envelope, Clock clock) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.ring = Objects.requireNonNull(ring, "ring");
        this.envelope = Objects.requireNonNull(envelope, "envelope");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public HashKey current(TenantId tenant) {
        UUID tenantId = Objects.requireNonNull(tenant, "tenant").value();
        Optional<SecretRow> latest = rows.latestHashKey(tenantId);
        if (latest.isEmpty()) {
            mint(tenantId);
            latest = rows.latestHashKey(tenantId);
        }
        // Present now: either our insert or the racing winner's. Absent would mean a row vanished between two
        // reads, which the runtime role cannot do (no DELETE).
        return open(tenantId, latest.orElseThrow(() -> new SecretException(SecretException.Code.NOT_FOUND)));
    }

    @Override
    public Optional<HashKey> version(TenantId tenant, int keyVersion) {
        UUID tenantId = Objects.requireNonNull(tenant, "tenant").value();
        Optional<SecretRow> row = rows.findHashKey(tenantId, keyVersion);
        if (row.isEmpty() || !row.get().tenantId().equals(tenantId) || ring.find(row.get().sealed().keyId()).isEmpty()) {
            // Unloadable is "cannot be checked", never a guess: the hasher turns this into its own classifier.
            return Optional.empty();
        }
        return Optional.of(open(tenantId, row.get()));
    }

    private void mint(UUID tenantId) {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        byte[] text = Base64.getEncoder().encode(key);
        try {
            UUID secretId = UUID.randomUUID();
            SealedSecret sealed = envelope.sealSecret(ring.current(), tenantId, secretId,
                    SecretPurpose.CONFIG_HASH_KEY, text);
            rows.insertHashKeyIfAbsent(new SecretRow(tenantId, secretId, SecretPurpose.CONFIG_HASH_KEY, sealed,
                    FIRST_VERSION, clock.instant(), null));
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(text, (byte) 0);
        }
    }

    /** Opens with the REQUESTED tenant and the row's id: a row for another tenant is refused (I5, twice over). */
    private HashKey open(UUID tenantId, SecretRow row) {
        if (!row.tenantId().equals(tenantId)) {
            throw new SecretException(SecretException.Code.NOT_FOUND);
        }
        MasterKey kek = ring.find(row.sealed().keyId())
                .orElseThrow(() -> new SecretException(SecretException.Code.KEY_UNAVAILABLE, row.sealed().keyId()));
        byte[] text = envelope.openSecret(kek, tenantId, row.secretId(), row.purpose(), row.sealed());
        try {
            byte[] key = Base64.getDecoder().decode(text);
            if (key.length != KEY_BYTES) {
                Arrays.fill(key, (byte) 0);
                throw new SecretException(SecretException.Code.AUTHENTICATION_FAILED);
            }
            return new HashKey(row.keyVersion(), key);
        } catch (IllegalArgumentException e) {
            throw new SecretException(SecretException.Code.AUTHENTICATION_FAILED);
        } finally {
            Arrays.fill(text, (byte) 0);
        }
    }

    /** Names no key and no tenant. */
    @Override
    public String toString() {
        return "EnvelopeHashKeySource";
    }
}
