package com.solace.sm.dp.apply.runtime;

import java.util.Arrays;

/**
 * One version of a tenant's config-hash key, for ONE use. Closing it zeroes the buffer it owns.
 *
 * <p>The guarantee ends where the JDK's does: {@code javax.crypto.spec.SecretKeySpec} copies the bytes and
 * cannot be zeroed (D-KEY 9 note 7).
 */
public final class HashKey implements AutoCloseable {

    private final int version;
    private byte[] material;

    /** Takes ownership of {@code material}: it is this object's buffer, and {@link #close} zeroes it. */
    public HashKey(int version, byte[] material) {
        if (version < 1) {
            throw new IllegalArgumentException("a key version is a positive number");
        }
        if (material == null || material.length == 0) {
            throw new IllegalArgumentException("key material is required");
        }
        this.version = version;
        this.material = material;
    }

    public int version() {
        return version;
    }

    /** The live buffer. Do not retain it past {@link #close}. */
    public byte[] material() {
        if (material == null) {
            throw new IllegalStateException("hash key is closed");
        }
        return material;
    }

    @Override
    public void close() {
        if (material != null) {
            Arrays.fill(material, (byte) 0);
            material = null;
        }
    }

    /** The version only, never the material. */
    @Override
    public String toString() {
        return "HashKey[v" + version + ", " + (material == null ? "closed" : "open") + "]";
    }
}
