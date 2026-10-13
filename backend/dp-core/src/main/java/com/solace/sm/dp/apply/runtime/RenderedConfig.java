package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.TenantId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Generation {@code G}'s rendered bytes, with the hash of exactly those bytes.
 *
 * <p>One owner for the hash ({@code BE-D21}), kept <b>by construction</b>: there is no public constructor, so
 * the only way to obtain a {@code RenderedConfig} is {@link #of}, which asks a {@link ConfigHasher} to hash the
 * bytes. The engine binds the hash into {@code apply_step_intent.config_hash} before the adapter is invoked
 * (&sect;2.2 rule 0), and the adapter reports it back from {@link RuntimeAdapter#materializeConfig}; a second,
 * independent hashing would let those two disagree about what was written, which is precisely the disagreement
 * the intent row exists to make impossible across a crash.
 *
 * <p>The hash is keyed (OR-32), so this class can no longer re-hash its own bytes to verify a supplied hash, as
 * the earlier record did with SHA-256: a keyed hash cannot be recomputed without the key.
 *
 * <p><b>Declared non-secret paths (OR-102).</b> A config also names the dotted paths, among the ones it renders, that
 * the package declares NON-secret. They are paths only (never a value), and only a path the package did not flag secret
 * is ever listed: they are what an adapter may digest, at the moment it retains the file this config is about to
 * replace, so that a later change to that retained copy can be reported by path. They are not part of the config's
 * identity: {@link #equals} and {@link #hashCode} ignore them, because the bytes and their hash already are.
 */
public final class RenderedConfig {

    private final byte[] bytes;
    private final String hash;
    private final Set<String> nonSecretPaths;

    private RenderedConfig(byte[] bytes, String hash, Set<String> nonSecretPaths) {
        this.bytes = bytes;
        this.hash = hash;
        this.nonSecretPaths = nonSecretPaths;
    }

    /** Hashes {@code bytes} for {@code tenant} with {@code hasher}; the only way to make one. */
    public static RenderedConfig of(TenantId tenant, byte[] bytes, ConfigHasher hasher) {
        return of(tenant, bytes, hasher, Set.of());
    }

    /**
     * As {@link #of(TenantId, byte[], ConfigHasher)}, also naming the declared NON-secret dotted paths (OR-102).
     * A path with an empty segment is refused with fixed text that does not repeat it.
     */
    public static RenderedConfig of(TenantId tenant, byte[] bytes, ConfigHasher hasher, Set<String> nonSecretPaths) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(bytes, "rendered bytes");
        Objects.requireNonNull(hasher, "hasher");
        Objects.requireNonNull(nonSecretPaths, "non-secret paths");
        TreeSet<String> paths = new TreeSet<>();
        for (String path : nonSecretPaths) {
            if (path == null || path.isEmpty() || path.startsWith(".") || path.endsWith(".") || path.contains("..")) {
                throw new IllegalArgumentException("a declared config path must be dotted, with no empty segment");
            }
            paths.add(path);
        }
        byte[] copy = bytes.clone();
        return new RenderedConfig(copy, Objects.requireNonNull(hasher.hash(tenant, copy), "hash"),
                Collections.unmodifiableSet(paths));
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String hash() {
        return hash;
    }

    /** The declared non-secret dotted paths this config renders, sorted; empty when none were declared. Paths only. */
    public Set<String> nonSecretPaths() {
        return nonSecretPaths;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RenderedConfig c && Arrays.equals(bytes, c.bytes) && hash.equals(c.hash);
    }

    @Override
    public int hashCode() {
        return hash.hashCode();
    }

    /** Length only &mdash; the bytes are configuration and never render into a log line ({@code I6}). */
    @Override
    public String toString() {
        return "RenderedConfig[" + bytes.length + " bytes, " + hash + "]";
    }
}
