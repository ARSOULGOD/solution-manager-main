package com.solace.sm.gateway.http;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A request whose body can be LOOKED AT once before the handler reads it (plan 013 T1.4).
 *
 * <p>Why it exists: {@code scanHostKeys} and {@code registerTarget} are authorized at the scope their BODY names
 * ({@code ownershipScope}), and {@link AuthenticationInterceptor} authorizes in {@code preHandle}, before Spring has
 * read the body. A {@link ResourceResolver} for them must therefore see the body, and the handler must then see the same
 * bytes. This wrapper reads the body into memory <b>only if asked to</b> ({@link #peek}); a request nobody peeks at is
 * passed straight through, untouched, so wrapping every unsafe request changes nothing for the others.
 *
 * <p>The peek is bounded ({@link #LIMIT_BYTES}); a body past the bound is refused as {@code 400}, not buffered. The bound
 * is well above the largest body here (a 16 KiB key plus its envelope) and well below anything worth an attack.
 *
 * <p>⛔ {@code I6}: the bytes may hold a private key. They live in this request's memory for the request's life and are
 * written nowhere; this class never logs, prints or copies them beyond the one array, which {@link #scrub} zeroes when the
 * request is done.
 */
final class PeekableBodyRequest extends HttpServletRequestWrapper {

    /** The most body a peek will hold: 64 KiB. */
    static final int LIMIT_BYTES = 64 * 1024;

    private byte[] buffered;

    PeekableBodyRequest(HttpServletRequest request) {
        super(request);
    }

    /**
     * The whole body, read once and kept so the handler can read it again.
     *
     * @throws GatewayRejectionException {@code 400}: the body is over {@link #LIMIT_BYTES}, or cannot be read
     */
    byte[] peek() {
        if (buffered == null) {
            if (getContentLengthLong() > LIMIT_BYTES) {
                throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
            }
            try (InputStream in = super.getInputStream()) {
                byte[] bytes = in.readNBytes(LIMIT_BYTES + 1);
                if (bytes.length > LIMIT_BYTES) {
                    throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
                }
                buffered = bytes;
            } catch (IOException e) {
                throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
            }
        }
        return buffered;
    }

    /**
     * Zeroes the peeked bytes (review W3C-8, {@code I6}): the body may hold a private key, and this array is the one copy
     * this class made. {@link PeekableBodyFilter} calls it when the request is done, so the key does not wait for the
     * garbage collector in this buffer as well as in whatever the handler parsed it into.
     */
    void scrub() {
        byte[] held = buffered;
        buffered = null;
        if (held != null) {
            java.util.Arrays.fill(held, (byte) 0);
        }
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (buffered == null) {
            return super.getInputStream();
        }
        return new Replayed(new ByteArrayInputStream(buffered));
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (buffered == null) {
            return super.getReader();
        }
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(buffered), charset));
    }

    private static final class Replayed extends ServletInputStream {

        private final ByteArrayInputStream in;

        Replayed(ByteArrayInputStream in) {
            this.in = in;
        }

        @Override
        public int read() {
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return in.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return in.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new IllegalStateException("a replayed body is read synchronously");
        }
    }
}
