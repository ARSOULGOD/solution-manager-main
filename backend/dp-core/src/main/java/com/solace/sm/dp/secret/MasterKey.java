package com.solace.sm.dp.secret;

import java.util.Arrays;
import java.util.Objects;

/**
 * One 256-bit key-encryption key and its id. The bytes are reachable only from this package
 * ({@link Envelope}); {@link #toString} names the id and nothing else.
 *
 * <p>A JDK key object cannot be zeroed (measured, D-KEY 2.2), and this key lives for the life of the
 * process, so this class does not pretend to. The residual is stated in D-KEY 10.
 */
public final class MasterKey {

    /** AES-256. */
    public static final int LENGTH = 32;

    private final KeyId id;
    private final byte[] bytes;

    private MasterKey(KeyId id, byte[] bytes) {
        this.id = id;
        this.bytes = bytes;
    }

    /** Copies {@code bytes}; the caller zeroes its own buffer. */
    public static MasterKey of(KeyId id, byte[] bytes) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != LENGTH) {
            throw new IllegalArgumentException("a master key is exactly " + LENGTH + " bytes");
        }
        return new MasterKey(id, Arrays.copyOf(bytes, LENGTH));
    }

    public KeyId id() {
        return id;
    }

    byte[] bytes() {
        return bytes;
    }

    @Override
    public String toString() {
        return "MasterKey[id=" + id + "]";
    }
}
