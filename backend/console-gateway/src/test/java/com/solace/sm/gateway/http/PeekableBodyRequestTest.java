package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The body a resolver may look at once and the handler then reads again, unchanged (plan 013 T1.4). */
class PeekableBodyRequestTest {

    private static MockHttpServletRequest post(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cp/targets");
        request.setContent(body);
        request.setCharacterEncoding("UTF-8");
        return request;
    }

    @Test
    void aPeekedBodyIsReadAgainByStreamAndByReaderExactly() throws Exception {
        byte[] body = "{\"a\":\"café ☃\"}".getBytes(StandardCharsets.UTF_8);
        PeekableBodyRequest request = new PeekableBodyRequest(post(body));

        assertArrayEquals(body, request.peek());
        assertArrayEquals(body, request.peek(), "a second peek is the same bytes");
        assertArrayEquals(body, request.getInputStream().readAllBytes());
        assertArrayEquals(body, request.getInputStream().readAllBytes(), "and the stream can be taken again");
        try (BufferedReader reader = request.getReader()) {
            assertEquals(new String(body, StandardCharsets.UTF_8), reader.readLine());
        }
    }

    @Test
    void aBodyNobodyPeekedAtIsPassedStraightThrough() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest raw = post(body);
        PeekableBodyRequest request = new PeekableBodyRequest(raw);

        assertSame(raw.getInputStream().getClass(), request.getInputStream().getClass(),
                "no buffering unless a resolver asks");
        assertArrayEquals(body, request.getInputStream().readAllBytes());
    }

    @Test
    void aBodyPastTheBoundIsRefused400WithoutBeingBuffered() {
        PeekableBodyRequest declared = new PeekableBodyRequest(post(new byte[PeekableBodyRequest.LIMIT_BYTES + 1]));
        GatewayRejectionException refused = assertThrows(GatewayRejectionException.class, declared::peek);
        assertEquals(ErrorCode.BAD_REQUEST, refused.code());
    }

    @Test
    void aBodyAtTheBoundIsAccepted() {
        PeekableBodyRequest request = new PeekableBodyRequest(post(new byte[PeekableBodyRequest.LIMIT_BYTES]));
        assertEquals(PeekableBodyRequest.LIMIT_BYTES, request.peek().length);
    }

    @Test
    void theFilterWrapsEveryUnsafeRequestAndNoSafeOne() throws Exception {
        PeekableBodyFilter filter = new PeekableBodyFilter();
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            assertEquals(PeekableBodyRequest.class, wrapped(filter, method).getClass(), method);
        }
        for (String method : new String[] {"GET", "HEAD", "OPTIONS"}) {
            assertEquals(MockHttpServletRequest.class, wrapped(filter, method).getClass(), method);
        }
    }

    @Test
    void thePeekedBytesAreZeroedWhenTheRequestIsDone() throws Exception {
        // W3C-8: the body may be a private key; the filter leaves no copy of it in the peek buffer
        byte[] body = "{\"privateKey\":\"-----BEGIN OPENSSH PRIVATE KEY-----\"}".getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> held = new AtomicReference<>();
        FilterChain chain = (request, response) -> {
            byte[] peeked = ((PeekableBodyRequest) request).peek();
            assertArrayEquals(body, peeked.clone(), "the handler sees the body while the request runs");
            held.set(peeked);
        };

        new PeekableBodyFilter().doFilter(post(body.clone()), new MockHttpServletResponse(), chain);

        assertEquals(body.length, held.get().length);
        for (byte b : held.get()) {
            assertEquals(0, b, "every byte of the peek buffer is zero after the request");
        }
    }

    @Test
    void anAsyncRequestKeepsItsBufferUntilItsOwnDispatchEnds() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest raw = post(body.clone());
        raw.setAsyncSupported(true);
        AtomicReference<byte[]> held = new AtomicReference<>();
        FilterChain chain = (request, response) -> {
            held.set(((PeekableBodyRequest) request).peek());
            raw.startAsync();
        };

        new PeekableBodyFilter().doFilter(raw, new MockHttpServletResponse(), chain);

        assertArrayEquals(body, held.get(), "not scrubbed while the async handler may still read it");
    }

    private static ServletRequest wrapped(PeekableBodyFilter filter, String method) throws Exception {
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        FilterChain chain = (request, response) -> seen.set(request);
        filter.doFilter(new MockHttpServletRequest(method, "/api/x"), new MockHttpServletResponse(), chain);
        return seen.get();
    }
}
