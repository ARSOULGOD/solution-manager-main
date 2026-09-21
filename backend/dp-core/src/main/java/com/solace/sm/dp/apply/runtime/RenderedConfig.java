package com.solace.sm.dp.apply.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Generation {@code G}'s rendered bytes, with the hash of exactly those bytes.
 *
 * <p>One owner for the hash ({@code BE-D21}). The engine binds it into
 * {@code apply_step_intent.config_hash} before the adapter is invoked (§2.2 rule 0), and the adapter
 * reports it back from {@link RuntimeAdapter#materializeConfig}; a second, independent hashing would
 * let those two disagree about what was written, which is precisely the disagreement the intent row
 * exists to make impossible across a crash.
 */
public record RenderedConfig(byte[] bytes, String hash) {

    public RenderedConfig {
        Objects.requireNonNull(bytes, "rendered bytes");
        Objects.requireNonNull(hash, "hash");
        bytes = bytes.clone();
        String actual = sha256(bytes);
        if (!actual.equals(hash)) {
            throw new IllegalArgumentException("the hash does not describe these bytes — one owner "
                    + "computes it (RenderedConfig.of) and nobody supplies it. The offending values "
                    + "are deliberately not repeated here (I6)");
        }
    }

    public static RenderedConfig of(byte[] bytes) {
        return new RenderedConfig(bytes, sha256(bytes));
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK's own MessageDigest contract; this branch is
            // unreachable on any conformant JVM and is not a caller-recoverable failure.
            throw new IllegalStateException("SHA-256 is not available on this JVM");
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RenderedConfig c && Arrays.equals(bytes, c.bytes) && hash.equals(c.hash);
    }

    @Override
    public int hashCode() {
        return hash.hashCode();
    }

    /** Length only — the bytes are configuration and never render into a log line ({@code I6}). */
    @Override
    public String toString() {
        return "RenderedConfig[" + bytes.length + " bytes, " + hash + "]";
    }
}
