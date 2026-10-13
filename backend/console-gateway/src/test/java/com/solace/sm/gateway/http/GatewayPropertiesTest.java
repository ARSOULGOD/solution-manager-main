package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * {@code trusted-origins} entries are compared byte-for-byte against a browser-sent {@code
 * Origin} header ({@link AuthenticationInterceptor#validateOrigin}), which is always exactly
 * {@code scheme://host[:port]} with a lowercase scheme and host and no default port. An entry
 * that could never equal such a header (a trailing slash, a path, uppercase, a default port, a
 * wildcard) would silently 403 every request forever, so it must be refused at startup instead.
 */
class GatewayPropertiesTest {

    @Test
    void emptyTrustedOriginsFailsValidation() {
        assertThrows(IllegalStateException.class, () -> validate(List.of()));
    }

    @Test
    void nullTrustedOriginsFailsValidation() {
        assertThrows(IllegalStateException.class, () -> validate((List<String>) null));
    }

    @Test
    void anExactSchemeHostOriginWithNoPortIsAccepted() {
        assertDoesNotThrow(() -> validate("https://console.example.test"));
    }

    @Test
    void anExactSchemeHostOriginWithANonDefaultPortIsAccepted() {
        assertDoesNotThrow(() -> validate("http://console.example.test:8080"));
    }

    @Test
    void aTrailingSlashIsRejected() {
        assertThrows(IllegalStateException.class, () -> validate("https://console.example.test/"));
    }

    @Test
    void aPathIsRejected() {
        assertThrows(IllegalStateException.class, () -> validate("https://console.example.test/console"));
    }

    @Test
    void aQueryIsRejected() {
        assertThrows(IllegalStateException.class, () -> validate("https://console.example.test?x=1"));
    }

    @Test
    void userInfoIsRejected() {
        assertThrows(IllegalStateException.class, () -> validate("https://user@console.example.test"));
    }

    @Test
    void anUppercaseHostIsRejected() {
        assertThrows(IllegalStateException.class, () -> validate("https://Console.example.test"));
    }

    @Test
    void anUppercaseSchemeIsRejected() {
        assertThrows(IllegalStateException.class, () -> validate("HTTPS://console.example.test"));
    }

    @Test
    void aWildcardIsRejected() {
        assertThrows(IllegalStateException.class, () -> validate("https://*.example.test"));
    }

    @Test
    void anExplicitDefaultHttpsPortIsRejected() {
        assertThrows(IllegalStateException.class, () -> validate("https://console.example.test:443"));
    }

    @Test
    void anExplicitDefaultHttpPortIsRejected() {
        assertThrows(IllegalStateException.class, () -> validate("http://console.example.test:80"));
    }

    @Test
    void aNonHttpSchemeIsRejected() {
        assertThrows(IllegalStateException.class, () -> validate("ftp://console.example.test"));
    }

    @Test
    void aBracketedIPv6HostWithANonDefaultPortIsAccepted() {
        assertDoesNotThrow(() -> validate("https://[::1]:8443"));
    }

    @Test
    void aBracketedIPv6HostWithNoPortIsAccepted() {
        assertDoesNotThrow(() -> validate("https://[2001:db8::1]"));
    }

    @Test
    void anUppercaseBracketedIPv6HostIsRejected() {
        assertThrows(IllegalStateException.class, () -> validate("https://[2001:DB8::1]"));
    }

    @Test
    void anExplicitDefaultPortOnABracketedIPv6HostIsRejected() {
        assertThrows(IllegalStateException.class, () -> validate("https://[::1]:443"));
    }

    @Test
    void aBlankEntryIsRejected() {
        assertThrows(IllegalStateException.class, () -> validate("   "));
    }

    // ── fix 6: bracketed IPv6 origins must be in RFC 5952 canonical (browser-serialised) form ──

    @Test
    void aCompressedBracketedIpv6LoopbackOriginIsAccepted() {
        assertDoesNotThrow(() -> validate("https://[::1]:8443"));
    }

    @Test
    void aFullyExpandedBracketedIpv6LoopbackOriginIsRejectedNamingTheCompressedForm() {
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> validate("https://[0:0:0:0:0:0:0:1]:8443"));
        assertTrue(failure.getMessage().contains("[::1]"), failure.getMessage());
    }

    @Test
    void aPartiallyExpandedBracketedIpv6OriginWithATiedRunIsRejectedNamingTheLeftmostCompression() {
        // Two equal-length runs of zero groups (positions 2-3 and 5-6); RFC 5952 compresses the
        // LEFTMOST on a tie.
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> validate("https://[2001:db8:0:0:1:0:0:1]"));
        assertTrue(failure.getMessage().contains("[2001:db8::1:0:0:1]"), failure.getMessage());
    }

    @Test
    void anAlreadyCanonicalBracketedIpv6OriginWithAnEmbeddedRunIsAccepted() {
        assertDoesNotThrow(() -> validate("https://[2001:db8::1]"));
    }

    // ── trusted-proxies (OR-20) ──────────────────────────────────────────────────────────────

    @Test
    void anEmptyTrustedProxiesListIsAccepted() {
        assertDoesNotThrow(() -> validateProxies(List.of()));
    }

    @Test
    void aNullTrustedProxiesListIsAccepted() {
        assertDoesNotThrow(() -> validateProxies(null));
    }

    @Test
    void anIpv4TrustedProxyIsAccepted() {
        assertDoesNotThrow(() -> validateProxies(List.of("10.0.0.1")));
    }

    @Test
    void anIpv6TrustedProxyIsAccepted() {
        assertDoesNotThrow(() -> validateProxies(List.of("::1")));
    }

    @Test
    void aHostnameTrustedProxyIsRejected() {
        assertThrows(IllegalStateException.class, () -> validateProxies(List.of("proxy.example.test")));
    }

    @Test
    void aCidrRangeTrustedProxyIsRejected() {
        assertThrows(IllegalStateException.class, () -> validateProxies(List.of("10.0.0.0/24")));
    }

    @Test
    void anOutOfRangeIpv4OctetTrustedProxyIsRejected() {
        assertThrows(IllegalStateException.class, () -> validateProxies(List.of("10.0.0.999")));
    }

    @Test
    void aBlankTrustedProxyEntryIsRejected() {
        assertThrows(IllegalStateException.class, () -> validateProxies(List.of("   ")));
    }

    // ── B1: the operator must make an explicit client-topology choice ──────────────────────────

    @Test
    void emptyProxiesAndDirectFalseFailsWithNoExplicitChoiceMade() {
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedOrigins(List.of("https://console.example.test"));
        // trustedProxies left at its default (empty); directClientConnections left at its default (false).
        IllegalStateException failure = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(failure.getMessage().contains("shared") || failure.getMessage().contains("everyone"),
                () -> "expected the failure to explain the shared-throttle-key consequence: " + failure.getMessage());
    }

    @Test
    void nonEmptyProxiesAndDirectFalseIsAccepted() {
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedOrigins(List.of("https://console.example.test"));
        properties.setTrustedProxies(List.of("10.0.0.1"));
        properties.setDirectClientConnections(false);
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void emptyProxiesAndDirectTrueIsAccepted() {
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedOrigins(List.of("https://console.example.test"));
        properties.setTrustedProxies(List.of());
        properties.setDirectClientConnections(true);
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void nonEmptyProxiesAndDirectTrueFailsAsContradictory() {
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedOrigins(List.of("https://console.example.test"));
        properties.setTrustedProxies(List.of("10.0.0.1"));
        properties.setDirectClientConnections(true);
        IllegalStateException failure = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(failure.getMessage().contains("contradictory"), failure.getMessage());
    }

    // ── M3: every trusted-proxies entry is canonicalized (and IPv6-literal-checked) via ClientAddresses ──

    @Test
    void anUppercaseIpv6TrustedProxyIsCanonicalizedToLowercase() {
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedOrigins(List.of("https://console.example.test"));
        properties.setTrustedProxies(List.of("2001:DB8::1"));
        properties.validate();
        assertEquals(List.of("2001:db8:0:0:0:0:0:1"), properties.getTrustedProxies());
    }

    @Test
    void aCompressedAndAnExpandedIpv6TrustedProxyCanonicalizeToTheSameStoredForm() {
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedOrigins(List.of("https://console.example.test"));
        properties.setTrustedProxies(List.of("::1"));
        properties.validate();
        assertEquals(List.of("0:0:0:0:0:0:0:1"), properties.getTrustedProxies());
    }

    // ── OR-24: sm.gateway.auth-rate.* is validated at startup ───────────────────────────────────

    @Test
    void authRateDefaultsAreAccepted() {
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedOrigins(List.of("https://console.example.test"));
        properties.setDirectClientConnections(true);
        assertDoesNotThrow(properties::validate);
        assertEquals(10, properties.getAuthRate().getCapacity());
        assertEquals(10, properties.getAuthRate().getRefillPerMinute());
        assertEquals(100_000, properties.getAuthRate().getMaxTrackedKeys());
    }

    @Test
    void aZeroOrNegativeAuthRateCapacityFailsValidation() {
        assertThrows(IllegalStateException.class, () -> validateAuthRate(0, 10, 100));
        assertThrows(IllegalStateException.class, () -> validateAuthRate(-1, 10, 100));
    }

    @Test
    void aZeroOrNegativeAuthRateRefillPerMinuteFailsValidation() {
        assertThrows(IllegalStateException.class, () -> validateAuthRate(10, 0, 100));
        assertThrows(IllegalStateException.class, () -> validateAuthRate(10, -1, 100));
    }

    @Test
    void aZeroOrNegativeAuthRateMaxTrackedKeysFailsValidation() {
        assertThrows(IllegalStateException.class, () -> validateAuthRate(10, 10, 0));
        assertThrows(IllegalStateException.class, () -> validateAuthRate(10, 10, -1));
    }

    @Test
    void positiveAuthRateValuesAreAccepted() {
        assertDoesNotThrow(() -> validateAuthRate(2, 5, 3));
    }

    private static void validateAuthRate(int capacity, int refillPerMinute, int maxTrackedKeys) {
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedOrigins(List.of("https://console.example.test"));
        properties.setDirectClientConnections(true);
        GatewayProperties.AuthRate authRate = new GatewayProperties.AuthRate();
        authRate.setCapacity(capacity);
        authRate.setRefillPerMinute(refillPerMinute);
        authRate.setMaxTrackedKeys(maxTrackedKeys);
        properties.setAuthRate(authRate);
        properties.validate();
    }

    // ── fix 2: the @PostConstruct wiring itself, proven through a real Spring context boot ────

    /**
     * {@code emptyProxiesAndDirectFalseFailsWithNoExplicitChoiceMade} above calls {@code validate()}
     * directly, so it would keep passing even if {@code @PostConstruct} were removed from {@link
     * GatewayProperties#validate()} entirely — Spring would simply never call it, and this gate would
     * silently stop firing in production. This boots a REAL Spring web context, wired exactly as
     * {@link GatewayWebConfiguration} wires production (via {@link GatewayTestSupport#TestConfig}),
     * with only {@code trusted-origins} set and neither {@code trusted-proxies} nor {@code
     * direct-client-connections} — B1's "neither set" case — and asserts startup itself fails, with a
     * cause chain naming {@code direct-client-connections}. See the fix's mutation proof: removing
     * {@code @PostConstruct} turns this test red.
     */
    @Test
    void bootingARealSpringContextWithOnlyTrustedOriginsSetFailsStartupNamingDirectClientConnections() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN);
        context.register(GatewayTestSupport.TestConfig.class);

        RuntimeException failure = assertThrows(RuntimeException.class, context::refresh);

        assertTrue(causeChainContains(failure, "direct-client-connections"),
                "expected a cause naming direct-client-connections in: " + failure);
    }

    private static boolean causeChainContains(Throwable failure, String text) {
        for (Throwable cause = failure; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static void validate(String origin) {
        validate(List.of(origin));
    }

    private static void validate(List<String> origins) {
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedOrigins(origins);
        // These tests exercise ONLY trusted-origins validation; an empty trusted-proxies list
        // needs an explicit direct-client-connections=true (B1) so that unrelated check never
        // fires here.
        properties.setDirectClientConnections(true);
        properties.validate();
    }

    private static void validateProxies(List<String> proxies) {
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedOrigins(List.of("https://console.example.test"));
        properties.setTrustedProxies(proxies);
        // These tests exercise ONLY trusted-proxies entry validation; when the list under test is
        // itself empty, satisfy B1's explicit-choice gate the same way `validate(List<String>)`
        // above does, so it never masks what these tests actually check.
        if (proxies == null || proxies.isEmpty()) {
            properties.setDirectClientConnections(true);
        }
        properties.validate();
    }
}
