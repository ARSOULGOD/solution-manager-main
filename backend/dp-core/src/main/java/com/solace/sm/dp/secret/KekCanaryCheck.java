package com.solace.sm.dp.secret;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;

/**
 * The boot check of D-KEY 4.3 (draft decision K-2): the master key is validated against a canary before
 * anything uses it, and a canary is minted only on a fresh install or a proven rotation.
 *
 * <p>The check reads {@code kek_canary}, plus one boolean {@code EXISTS} over the secret table to tell a fresh
 * install from a restore: it is given no handle on tenant secrets and no tenant id, key id or value leaves the
 * database, so it cannot read across tenants (B4). It also exercises 256-bit GCM on this JVM, which the platform does not
 * list as mandatory (D-KEY 2.3).
 *
 * <table>
 * <caption>The decision table</caption>
 * <tr><th>canary table</th><th>previous key</th><th>boot does</th></tr>
 * <tr><td>any</td><td>configured, canary absent or failing</td><td>refuse, naming the previous variable</td></tr>
 * <tr><td>row for the current id</td><td>any (verified)</td><td>decrypt it; failure means refuse</td></tr>
 * <tr><td>empty, and no secret row exists</td><td>none</td><td>fresh install: mint, re-read, verify</td></tr>
 * <tr><td>empty, but secret rows exist</td><td>none</td><td>refuse: not a fresh install (restore, or an edited table)</td></tr>
 * <tr><td>non-empty, no row for the current id</td><td>verified</td><td>rotation starting: mint, re-read, verify</td></tr>
 * <tr><td>non-empty, no row for the current id</td><td>none</td><td>refuse</td></tr>
 * </table>
 *
 * <p>Messages name variables and key ids (labels) and never a key, a length or any ciphertext.
 */
public final class KekCanaryCheck {

    /** The fixed known plaintext every canary seals. Not a secret. */
    static final byte[] CANARY_PLAINTEXT = "sm-dp-kek-canary-v1".getBytes(StandardCharsets.US_ASCII);

    private final MasterKeyRing ring;
    private final CanaryRows rows;
    private final Envelope envelope;
    private final Clock clock;

    public KekCanaryCheck(MasterKeyRing ring, CanaryRows rows, Envelope envelope, Clock clock) {
        this.ring = ring;
        this.rows = rows;
        this.envelope = envelope;
        this.clock = clock;
    }

    /** @throws IllegalStateException when the keys do not match what the database was written under */
    public void run() {
        MasterKey current = ring.current();
        Optional<MasterKey> previous = ring.previous();

        if (previous.isPresent() && !verifies(previous.get())) {
            throw refusal(EnvironmentMasterKeyProvider.PREVIOUS + " is not the key that "
                    + EnvironmentMasterKeyProvider.PREVIOUS_ID + " ('" + previous.get().id()
                    + "') names, or that id has no canary");
        }
        if (rows.find(current.id()).isPresent()) {
            if (!verifies(current)) {
                throw notTheKey(current);
            }
            return;
        }
        if (previous.isPresent() || (!rows.anyExists() && !rows.anySecretExists())) {
            mint(current);
            if (!verifies(current)) {
                // another boot, holding a different key for the same id, won the insert
                throw notTheKey(current);
            }
            return;
        }
        throw refusal("the database has no canary for key id '" + current.id() + "' (or no canary at all while"
                + " secrets exist), so "
                + EnvironmentMasterKeyProvider.KEY + " names a key this install has never used; if this is a "
                + "rotation, set " + EnvironmentMasterKeyProvider.PREVIOUS + " and "
                + EnvironmentMasterKeyProvider.PREVIOUS_ID + " to the old key");
    }

    private void mint(MasterKey key) {
        rows.insertIfAbsent(new CanaryRow(envelope.sealCanary(key, CANARY_PLAINTEXT), clock.instant()));
    }

    private boolean verifies(MasterKey key) {
        return canaryVerifies(envelope, key, rows.find(key.id()));
    }

    /** Whether {@code row} is a canary that {@code key} opens to the known plaintext. Absent is false. */
    public static boolean canaryVerifies(Envelope envelope, MasterKey key, Optional<CanaryRow> row) {
        if (row.isEmpty()) {
            return false;
        }
        try {
            byte[] plain = envelope.openCanary(key, row.get().sealed());
            boolean ok = Arrays.equals(plain, CANARY_PLAINTEXT);
            Arrays.fill(plain, (byte) 0);
            return ok;
        } catch (SecretException e) {
            return false;
        }
    }

    private static IllegalStateException notTheKey(MasterKey key) {
        return refusal(EnvironmentMasterKeyProvider.KEY + " is not the key that "
                + EnvironmentMasterKeyProvider.KEY_ID + " ('" + key.id() + "') names");
    }

    private static IllegalStateException refusal(String message) {
        return new IllegalStateException(message);
    }
}
