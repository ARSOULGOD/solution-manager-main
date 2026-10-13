package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.solace.sm.cp.api.audit.ClientAddresses;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Ruling {@code OR-20}: with the default empty {@code trusted-proxies} list, {@code
 * X-Forwarded-For} is ignored completely; a configured trusted proxy's forwarded header is
 * consulted right-to-left, skipping every trusted-proxy entry.
 *
 * <p>{@code M3}: the socket peer, every {@code X-Forwarded-For} entry, and every configured
 * trusted-proxy entry are canonicalized through {@link ClientAddresses} before comparison — so
 * two byte-different but equal IPv6 spellings (bracketed vs not, upper vs lower case, zone-suffixed
 * or not, compressed vs expanded) are always recognized as the same address.
 */
class ClientAddressResolverTest {

    private Logger resolverLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachLogAppender() {
        resolverLogger = (Logger) LoggerFactory.getLogger(ClientAddressResolver.class);
        appender = new ListAppender<>();
        appender.start();
        resolverLogger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        resolverLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void defaultEmptyTrustedProxiesIgnoresForwardedForEntirely() {
        ClientAddressResolver resolver = resolverWithTrustedProxies();
        MockHttpServletRequest request = requestFrom("203.0.113.9", "10.0.0.1");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void aTrustedProxysForwardedForIsHonoured() {
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.0.1");
        MockHttpServletRequest request = requestFrom("10.0.0.1", "203.0.113.9");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void aChainOfTrustedProxiesResolvesTheRightMostUntrustedEntry() {
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.0.2", "10.0.0.3");
        MockHttpServletRequest request = requestFrom("10.0.0.3", "203.0.113.9, 10.0.0.2, 10.0.0.3");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void anUntrustedPeersForwardedForIsIgnored() {
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.0.1");
        MockHttpServletRequest request = requestFrom("198.51.100.7", "203.0.113.9");

        assertEquals("198.51.100.7", resolver.resolve(request));
    }

    @Test
    void aNonIpForwardedForEntryFallsBackToThePeerRatherThanReachingTheAuditTrail() {
        // Behind a trusted proxy, the right-most untrusted X-Forwarded-For entry becomes the client
        // address — which is written verbatim into the audit trail's client_address and keys the
        // throttle. An entry that is not an IP literal is attacker text, not an address: it must
        // never be adopted (and one longer than RequestOrigin's bound would otherwise be a 500).
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.0.1");
        for (String forged : new String[] {"not-an-address", "203.0.113.9; DROP", "x".repeat(200), "999.1.1.1"}) {
            assertEquals("10.0.0.1", resolver.resolve(requestFrom("10.0.0.1", forged)), forged);
        }
    }

    @Test
    void aBlankEntryInTheMiddleIsSkippedAndTheWalkReturnsTheRightmostUntrustedLiteral() {
        // Fix (m1): "a,,b" shape — a genuine address, a blank entry, then the trusted proxy's own
        // address last. The blank carries no claim and must be skipped; the walk continues past
        // it AND past the trailing trusted-proxy entry to return "a", the rightmost untrusted
        // literal — it must NOT fall back to the peer just because a blank entry appeared.
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.0.1");
        MockHttpServletRequest request = requestFrom("10.0.0.1", "203.0.113.9,, 10.0.0.1");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void aBlankEntryBeforeARealOneAtTheStartResolvesToTheRealEntry() {
        // "," style: blank first, a real untrusted address second (rightmost) — the walk finds
        // the real entry immediately from the right and never even needs to reach the blank one.
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.0.1");
        MockHttpServletRequest request = requestFrom("10.0.0.1", ",203.0.113.9");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void anAbsentForwardedForFallsBackToThePeerEvenFromATrustedProxy() {
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.0.1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        assertEquals("10.0.0.1", resolver.resolve(request));
    }

    @Test
    void aBlankForwardedForFallsBackToThePeer() {
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.0.1");
        MockHttpServletRequest request = requestFrom("10.0.0.1", "   ");

        assertEquals("10.0.0.1", resolver.resolve(request));
    }

    @Test
    void everyEntryBeingATrustedProxyFallsBackToThePeer() {
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.0.1", "10.0.0.2");
        MockHttpServletRequest request = requestFrom("10.0.0.1", "10.0.0.2, 10.0.0.1");

        assertEquals("10.0.0.1", resolver.resolve(request));
    }

    @Test
    void bracketedIPv6EntriesAreCanonicalized() {
        ClientAddressResolver resolver = resolverWithTrustedProxies("::1");
        MockHttpServletRequest request = requestFrom("::1", "[2001:db8::9]");

        assertEquals("2001:db8:0:0:0:0:0:9", resolver.resolve(request));
    }

    @Test
    void aBracketedIPv6PeerIsCanonicalized() {
        ClientAddressResolver resolver = resolverWithTrustedProxies();
        MockHttpServletRequest request = requestFrom("[2001:db8::1]", "203.0.113.9");

        // trustedProxies is empty, so XFF is ignored — but the returned peer must still be
        // canonicalized (bracket-free, and in ClientAddresses' full lower-case form).
        assertEquals("2001:db8:0:0:0:0:0:1", resolver.resolve(request));
    }

    // ── M3: IPv6 canonicalization ────────────────────────────────────────────────────────────

    @Test
    void aTrustedProxyConfiguredCompressedMatchesAPeerTheContainerReportsExpanded() {
        // Tomcat reports an IPv6 loopback peer as "0:0:0:0:0:0:0:1"; an operator configuring
        // trusted-proxies would naturally write "::1" instead. Both canonicalize to the same
        // string, so the proxy must still be recognized and XFF still consulted — a raw string
        // comparison of the two spellings would silently never match.
        ClientAddressResolver resolver = resolverWithTrustedProxies("::1");
        MockHttpServletRequest request = requestFrom("0:0:0:0:0:0:0:1", "203.0.113.9");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void anUppercaseAndAnAlreadyExpandedXffEntryResolveToTheSameCanonicalString() {
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.0.1");
        MockHttpServletRequest uppercase = requestFrom("10.0.0.1", "2001:DB8::1");
        MockHttpServletRequest alreadyExpanded = requestFrom("10.0.0.1", "2001:db8:0:0:0:0:0:1");

        String resolvedFromUppercase = resolver.resolve(uppercase);
        String resolvedFromExpanded = resolver.resolve(alreadyExpanded);

        assertEquals(resolvedFromExpanded, resolvedFromUppercase);
        assertEquals("2001:db8:0:0:0:0:0:1", resolvedFromUppercase);
    }

    @Test
    void aZoneSuffixedPeerCanonicalises() {
        ClientAddressResolver resolver = resolverWithTrustedProxies();
        MockHttpServletRequest request = requestFrom("fe80::1%lo0", "203.0.113.9");

        // trustedProxies is empty, so XFF is ignored — but the returned peer must still be
        // canonicalized, with the zone suffix stripped (ClientAddresses never returns one).
        assertEquals("fe80:0:0:0:0:0:0:1", resolver.resolve(request));
    }

    // ── fix 3: a port-appending proxy must not collapse XFF entries back to the peer ──────────

    @Test
    void anIpv4XffEntryCarryingAPortHasThePortStrippedBeforeCanonicalizing() {
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.9.1");
        MockHttpServletRequest request = requestFrom("10.0.9.1", "203.0.113.9:54321");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void aBracketedIpv6XffEntryCarryingAPortHasThePortStrippedBeforeCanonicalizing() {
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.9.2");
        MockHttpServletRequest request = requestFrom("10.0.9.2", "[2001:db8::9]:443");

        assertEquals("2001:db8:0:0:0:0:0:9", resolver.resolve(request));
    }

    @Test
    void anUnbracketedIpv6XffEntryIsNeverPortStrippedBecauseItIsAmbiguous() {
        // "2001:db8::9:443" is a complete, valid IPv6 literal in its own right (443 is its last hex
        // group) — stripping a trailing ":443" as if it were a port would silently corrupt a real
        // address, so an unbracketed IPv6 entry must never be port-stripped.
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.9.3");
        MockHttpServletRequest request = requestFrom("10.0.9.3", "2001:db8::9:443");

        assertEquals("2001:db8:0:0:0:0:9:443", resolver.resolve(request));
    }

    // ── external finding 3: duplicate X-Forwarded-For FIELDS (not comma-joined entries within
    // one field) must all be read, in order, and joined exactly as RFC 7230 allows multiple
    // instances of the same field name to be combined — never just the first field, which is
    // what an attacker in front of the trusted proxy controls. ─────────────────────────────────

    @Test
    void anAttackerSuppliedFieldBeforeTheProxyAppendedFieldIsIgnoredInFavourOfTheLastField() {
        // Two SEPARATE "X-Forwarded-For" header instances, exactly as a trusted reverse proxy that
        // appends its own field (rather than editing an existing one) would send them: the
        // attacker's own forged field arrives first, the proxy's genuine appended field last.
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.0.1");
        MockHttpServletRequest request = requestFromFields("10.0.0.1", "203.0.113.66", "198.51.100.7");

        assertEquals("198.51.100.7", resolver.resolve(request));
    }

    @Test
    void aSingleForwardedForFieldIsUnaffectedByTheMultiFieldJoin() {
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.0.1");
        MockHttpServletRequest request = requestFromFields("10.0.0.1", "203.0.113.9");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void mixedFieldsWithAMalformedEntryMakeTheWholeHeaderUntrusted() {
        // Unlike a BLANK entry (fix m1, above), a non-blank entry that fails to parse as an IP
        // literal actively claims to BE an address and is still treated as reason to distrust the
        // whole header — this behaviour is unchanged by the m1 fix.
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.0.1");
        MockHttpServletRequest request = requestFromFields("10.0.0.1", "203.0.113.9", "not-an-address");

        assertEquals("10.0.0.1", resolver.resolve(request));
    }

    @Test
    void aBlankLeadingFieldFromAnAttackerAheadOfTheProxysAppendedFieldIsSkipped() {
        // Fix (m1)'s exact motivating scenario: an attacker sends their OWN empty
        // X-Forwarded-For field ahead of the trusted proxy's genuine appended field. The blank
        // carries no claim about the client address, so it is skipped — the attacker must not end
        // up keyed and audited as the proxy itself just by injecting an empty field.
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.0.1");
        MockHttpServletRequest request = requestFromFields("10.0.0.1", "", "203.0.113.9");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void aBlankForwardedForEntryBehindATrustedProxyNeverWarnsEvenWhenRepeated() {
        // Fix (m1): a blank entry is silently skipped — unlike a genuinely malformed (non-IP)
        // entry (see aNonIpForwardedForEntryBehindATrustedProxyLogsExactlyOneWarnNeverTheHeaderContent
        // below), it must never trigger the "discarded as malformed" warning at all.
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.9.4");

        for (int i = 0; i < 3; i++) {
            assertEquals("203.0.113.9", resolver.resolve(requestFrom("10.0.9.4", "203.0.113.9,, 10.0.9.4")));
        }

        assertEquals(0, warnEvents().size(), "a blank entry must never log a warning: " + warnEvents());
    }

    @Test
    void aNonIpForwardedForEntryBehindATrustedProxyLogsExactlyOneWarnNeverTheHeaderContent() {
        ClientAddressResolver resolver = resolverWithTrustedProxies("10.0.9.5");
        String forged = "not-an-address; DROP TABLE users;";

        assertEquals("10.0.9.5", resolver.resolve(requestFrom("10.0.9.5", forged)));
        assertEquals("10.0.9.5", resolver.resolve(requestFrom("10.0.9.5", forged)));

        List<ILoggingEvent> warnings = warnEvents();
        assertEquals(1, warnings.size(), "expected exactly ONE warn across two requests: " + warnings);
        String message = warnings.get(0).getFormattedMessage();
        assertTrue(message.contains("10.0.9.5"), message);
        assertTrue(!message.contains(forged), "must never log the header content: " + message);
    }

    private List<ILoggingEvent> warnEvents() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).collect(Collectors.toList());
    }

    /**
     * The proxy entries a test configures here are canonicalized before being stored, exactly as
     * {@link GatewayProperties#validate()} canonicalizes {@code sm.gateway.trusted-proxies} in
     * production — a test that skipped this would compare {@link ClientAddressResolver}'s
     * canonicalized peer/XFF values against un-canonicalized configured literals, which is not
     * what production ever does.
     */
    private static ClientAddressResolver resolverWithTrustedProxies(String... proxies) {
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedProxies(java.util.Arrays.stream(proxies)
                .map(candidate -> ClientAddresses.canonicalize(candidate).orElseThrow())
                .collect(Collectors.toList()));
        return new ClientAddressResolver(properties);
    }

    private static MockHttpServletRequest requestFrom(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }

    /** Adds "X-Forwarded-For" as {@code fields.length} SEPARATE header instances, in order. */
    private static MockHttpServletRequest requestFromFields(String remoteAddr, String... fields) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        for (String field : fields) {
            request.addHeader("X-Forwarded-For", field);
        }
        return request;
    }
}
