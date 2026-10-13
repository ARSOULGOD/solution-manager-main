package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/**
 * Plan 013 OQ-14 (b): a credential-bearing request is refused {@code 403 INSECURE_TRANSPORT} unless the channel is secure,
 * a TRUSTED PROXY says {@code https}, or the peer is loopback. The cases are the plan's three alternatives and the ways a
 * caller could claim one of them without having it.
 */
class CredentialTransportInterceptorTest {

    /** A handler that carries a credential, and one that does not. */
    static final class Handlers {
        @CredentialBearing
        public void carries() {
        }

        public void doesNot() {
        }
    }

    private static GatewayProperties properties(String... trustedProxies) {
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedOrigins(List.of("https://console.example.test"));
        properties.setTrustedProxies(List.of(trustedProxies));
        properties.setDirectClientConnections(trustedProxies.length == 0);
        properties.validate();
        return properties;
    }

    private static HandlerMethod handler(String name) throws Exception {
        Method method = Handlers.class.getMethod(name);
        return new HandlerMethod(new Handlers(), method);
    }

    private static MockHttpServletRequest request(String peer, boolean secure, String... forwardedProto) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cp/targets");
        request.setRemoteAddr(peer);
        request.setSecure(secure);
        for (String value : forwardedProto) {
            request.addHeader("X-Forwarded-Proto", value);
        }
        return request;
    }

    private static boolean passes(GatewayProperties properties, MockHttpServletRequest request) throws Exception {
        CredentialTransportInterceptor interceptor = new CredentialTransportInterceptor(properties);
        try {
            return interceptor.preHandle(request, new MockHttpServletResponse(), handler("carries"));
        } catch (GatewayRejectionException refused) {
            assertEquals(ErrorCode.INSECURE_TRANSPORT, refused.code());
            return false;
        }
    }

    @Test
    void aSecureConnectionPassesWhateverThePeer() throws Exception {
        assertTrue(passes(properties(), request("203.0.113.9", true)));
    }

    @Test
    void loopbackPassesInBothFamilies() throws Exception {
        assertTrue(passes(properties(), request("127.0.0.1", false)));
        assertTrue(passes(properties(), request("0:0:0:0:0:0:0:1", false)), "Tomcat's spelling of ::1");
        assertTrue(passes(properties(), request("::1", false)));
    }

    @Test
    void anotherHostOverPlainHttpIsRefused() throws Exception {
        assertFalse(passes(properties(), request("203.0.113.9", false)));
        assertFalse(passes(properties(), request("10.1.2.3", false)), "a private address is not loopback");
        assertFalse(passes(properties(), request("fe80::1", false)));
    }

    @Test
    void aTrustedProxyThatReportsHttpsPasses() throws Exception {
        assertTrue(passes(properties("203.0.113.9"), request("203.0.113.9", false, "https")));
        assertTrue(passes(properties("203.0.113.9"), request("203.0.113.9", false, "HTTPS")), "case-insensitive");
    }

    @Test
    void aTrustedProxyThatReportsHttpOrNothingIsRefused() throws Exception {
        assertFalse(passes(properties("203.0.113.9"), request("203.0.113.9", false, "http")));
        assertFalse(passes(properties("203.0.113.9"), request("203.0.113.9", false)));
    }

    @Test
    void theNearestProxysWordIsTheLastValue() throws Exception {
        // a client-sent "https" ahead of the trusted proxy's own "http": the proxy's (last) value counts
        assertFalse(passes(properties("203.0.113.9"), request("203.0.113.9", false, "https", "http")));
        assertFalse(passes(properties("203.0.113.9"), request("203.0.113.9", false, "https, http")));
        assertTrue(passes(properties("203.0.113.9"), request("203.0.113.9", false, "http, https")));
    }

    @Test
    void aPeerThatIsNotATrustedProxyCannotAssertHttpsWithAHeader() throws Exception {
        assertFalse(passes(properties("203.0.113.9"), request("198.51.100.4", false, "https")));
        assertFalse(passes(properties(), request("198.51.100.4", false, "https")));
    }

    @Test
    void spoofedForwardingHeadersFromAnUntrustedPeerAreIgnoredWhateverTheyClaim() throws Exception {
        // W3C-1: with the container NOT rewriting the request (the launcher pins the strategy to none), the peer is what the
        // socket says. An X-Forwarded-For naming loopback, with or without an https claim, changes nothing.
        for (GatewayProperties properties : List.of(properties(), properties("203.0.113.9"))) {
            MockHttpServletRequest asLoopback = request("10.1.2.3", false);
            asLoopback.addHeader("X-Forwarded-For", "127.0.0.1");
            assertFalse(passes(properties, asLoopback), "X-Forwarded-For: 127.0.0.1");

            MockHttpServletRequest both = request("10.1.2.3", false, "https");
            both.addHeader("X-Forwarded-For", "127.0.0.1");
            assertFalse(passes(properties, both), "X-Forwarded-For plus X-Forwarded-Proto: https");

            MockHttpServletRequest forwarded = request("10.1.2.3", false);
            forwarded.addHeader("Forwarded", "proto=https;for=127.0.0.1");
            assertFalse(passes(properties, forwarded), "RFC 7239 Forwarded");
        }
    }

    @Test
    void aTrustedProxysForwardedForNeverMakesAnotherPeerLoopback() throws Exception {
        // the trusted proxy forwards a client that says it is 127.0.0.1: only the proxy's own proto word counts
        MockHttpServletRequest request = request("203.0.113.9", false, "http");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        assertFalse(passes(properties("203.0.113.9"), request));
    }

    @Test
    void aTrustedProxyOnLoopbackIsHeldToItsOwnHeaderNotToTheLoopbackAllowance() throws Exception {
        // a proxy on this machine forwarding http crossed the network in clear to reach it: it is not the local stack
        assertFalse(passes(properties("127.0.0.1"), request("127.0.0.1", false, "http")));
        assertFalse(passes(properties("127.0.0.1"), request("127.0.0.1", false)));
        assertTrue(passes(properties("127.0.0.1"), request("127.0.0.1", false, "https")));
    }

    @Test
    void aPeerThatIsNotAnAddressIsNotLocal() throws Exception {
        assertFalse(passes(properties(), request("localhost", false)));
        assertFalse(passes(properties(), request("", false)));
    }

    @Test
    void aHandlerWithoutTheMarkIsNeverChecked() throws Exception {
        CredentialTransportInterceptor interceptor = new CredentialTransportInterceptor(properties());
        assertTrue(interceptor.preHandle(request("203.0.113.9", false), new MockHttpServletResponse(),
                handler("doesNot")));
        // ... and a non-handler (a static route, the not-found mapping) is left to the other interceptor
        assertTrue(interceptor.preHandle(request("203.0.113.9", false), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void theRefusalIsRaisedBeforeAnythingReadsTheBody() throws Exception {
        MockHttpServletRequest request = request("203.0.113.9", false);
        request.setContent(new byte[] {1, 2, 3});
        CredentialTransportInterceptor interceptor = new CredentialTransportInterceptor(properties());

        assertThrows(GatewayRejectionException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), handler("carries")));

        // the interceptor only ever looked at the channel: the stream was never opened
        assertEquals(3, request.getContentAsByteArray().length);
        assertEquals(3, request.getInputStream().available(), "no byte of the body was consumed");
    }
}
