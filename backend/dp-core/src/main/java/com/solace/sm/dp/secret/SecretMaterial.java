package com.solace.sm.dp.secret;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A decrypted secret for ONE use. Closing it zeroes the buffer it owns.
 *
 * <p>The guarantee ends where the JDK's does (D-KEY 9 note 7, 10): it cannot reach a {@code String} the
 * caller makes from {@link #chars()}, and sshj may need the private key as a {@code String}.
 */
public final class SecretMaterial implements AutoCloseable {

    private byte[] plaintext;

    /** Takes ownership of {@code plaintext}: it is this object's buffer, and {@link #close} zeroes it. */
    SecretMaterial(byte[] plaintext) {
        this.plaintext = plaintext;
    }

    /**
     * Wraps a buffer the caller hands over, for a {@code SecretResolver}-shaped source that is not the secret
     * store (the demo's key vault; tests). Takes ownership exactly as the package's own constructor does.
     */
    public static SecretMaterial adopt(byte[] plaintext) {
        return new SecretMaterial(java.util.Objects.requireNonNull(plaintext, "plaintext"));
    }

    /** The live buffer (UTF-8). Do not retain it past {@link #close}. */
    public byte[] bytes() {
        if (plaintext == null) {
            throw new IllegalStateException("secret material is closed");
        }
        return plaintext;
    }

    /** A fresh copy the caller owns and should zero. */
    public char[] chars() {
        CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes()));
        try {
            return Arrays.copyOfRange(decoded.array(), decoded.arrayOffset() + decoded.position(),
                    decoded.arrayOffset() + decoded.limit());
        } finally {
            Arrays.fill(decoded.array(), '\0');
        }
    }

    public boolean isClosed() {
        return plaintext == null;
    }

    @Override
    public void close() {
        if (plaintext != null) {
            Arrays.fill(plaintext, (byte) 0);
            plaintext = null;
        }
    }

    @Override
    public String toString() {
        return "SecretMaterial[" + (plaintext == null ? "closed" : "open") + "]";
    }
}
