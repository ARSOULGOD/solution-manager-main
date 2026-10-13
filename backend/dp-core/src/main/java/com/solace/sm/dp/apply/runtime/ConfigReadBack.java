package com.solace.sm.dp.apply.runtime;

import java.util.Arrays;
import java.util.Objects;

/**
 * One replica's config file, read back: the bytes the host holds and the keyed hash (OR-32) of exactly those bytes,
 * computed in the data plane ({@code BE-D39}, D-ACCEPT).
 *
 * <p>⛔ <b>{@code I6}: the bytes are the host's own file and carry every secret SM ever rendered into it, in clear.</b>
 * So this type is deliberately small and inert: it has <b>no {@code toString} that names a byte</b> (length only), no
 * {@code equals}/{@code hashCode} over the bytes, hands out a <b>copy</b> and never its own array, and
 * {@link #close()} zeroes the array it owns. The caller closes it in a {@code finally}.
 *
 * <p>The guarantee ends where the JDK's does: a {@code String} a parser builds from these bytes cannot be zeroed. That is
 * the same residual {@code SecretMaterial} states, and it is not a persistence.
 */
public final class ConfigReadBack implements AutoCloseable {

    private byte[] bytes;
    private final String hash;

    /** Takes ownership of {@code bytes}: they are this object's buffer, and {@link #close} zeroes them. */
    public ConfigReadBack(byte[] bytes, String hash) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
        this.hash = Objects.requireNonNull(hash, "hash");
    }

    /** A copy the caller owns and should zero. */
    public byte[] bytes() {
        if (bytes == null) {
            throw new IllegalStateException("the read-back is closed");
        }
        return bytes.clone();
    }

    /** The keyed hash of the bytes, for the tenant the placement belongs to. */
    public String hash() {
        return hash;
    }

    public boolean isClosed() {
        return bytes == null;
    }

    @Override
    public void close() {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
            bytes = null;
        }
    }

    /** Length and the keyed hash only: the bytes are configuration and never render into a log line. */
    @Override
    public String toString() {
        return "ConfigReadBack[" + (bytes == null ? "closed" : bytes.length + " bytes") + ", " + hash + "]";
    }
}
