package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.audit.ClientAddresses;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway-owned HTTP boundary configuration.
 *
 * <p>⛔ {@code trustedOrigins} is required and non-empty: it is the whole of {@code Origin}/
 * {@code Referer} validation for unsafe methods (there are no wildcards, and a missing or empty
 * list would make every unsafe request either always-rejected or, if the check were skipped
 * instead, always-accepted — this fails startup rather than silently choosing either).
 *
 * <p>⛔ Each entry must be EXACTLY {@code scheme://host[:port]} — {@code http} or {@code https},
 * lowercase, no userinfo/path/query/trailing-slash/wildcard, and no explicit default port
 * ({@code :80} for {@code http}, {@code :443} for {@code https}). The host may be a bracketed
 * IPv6 literal (e.g. {@code [::1]}), still lowercase, and must be in RFC 5952 CANONICAL
 * (compressed) form — a browser always serialises an IPv6 host that way, so a bracketed literal
 * that is merely valid but not already canonical (e.g. {@code [0:0:0:0:0:0:0:1]}) would still
 * silently 403 forever, exactly like every other unreachable shape below. {@link
 * AuthenticationInterceptor} compares an entry byte-for-byte against a browser's {@code Origin}
 * header, which a browser always sends in exactly that normalized shape; an entry that could never
 * equal such a header (a trailing slash, a path, uppercase, a redundant default port, a
 * non-canonical IPv6 host) would silently 403 every request forever, so this fails startup instead
 * of shipping a permanently-broken origin list.
 *
 * <p>⛔ <b>{@code trustedProxies} defaults to empty, and an empty list means {@code
 * X-Forwarded-For} is never trusted</b> ({@code OR-20}; see {@link ClientAddressResolver}). Each
 * entry must be an exact IP literal (IPv4 or IPv6) — never a hostname, never a CIDR range —
 * because it is compared against the resolved socket peer and forwarded-header entries, and is
 * validated (and canonicalized — see below) at startup for the same reason {@code trustedOrigins}
 * is.
 *
 * <p>Every {@code trustedProxies} entry is canonicalized through {@link ClientAddresses} at
 * startup, and the canonical form — not the operator's literal spelling — is what {@link
 * #getTrustedProxies()} returns afterwards. A servlet container may report a peer or forwarded
 * entry in a different (but equal) IPv6 spelling than whatever an operator typed (e.g. Tomcat's
 * {@code 0:0:0:0:0:0:0:1} versus a configured {@code ::1}); comparing un-canonicalized strings
 * would silently fail to recognize the proxy, and {@link ClientAddressResolver} canonicalizes the
 * peer and every forwarded-header entry the same way before ever comparing against this list.
 *
 * <p>⛔ <b>{@code directClientConnections} defaults to {@code false}, and startup FAILS unless the
 * operator has made an explicit choice</b> between it and {@code trustedProxies}: exactly one of
 * "{@code trustedProxies} is non-empty" or "{@code directClientConnections=true}" must hold.
 * Neither set (the all-defaults case) is refused, not silently accepted — the documented topology
 * puts a front server between every browser and this process, proxying {@code /api} to it, so
 * with an empty {@code trustedProxies} every request's socket peer resolves to that ONE proxy
 * address for every real user, collapsing the per-client-address login throttle ({@code OR-20})
 * into a single shared key: twenty bad logins from any one user would lock out everyone. Both set
 * at once is refused too, as contradictory — {@code trustedProxies} declares a proxy sits in
 * front, {@code directClientConnections} declares none does.
 */
@ConfigurationProperties(prefix = "sm.gateway")
public class GatewayProperties {

    // Host is either a bracketed IPv6 literal ("[::1]", hex digits and colons only) or a plain
    // host with no colon, bracket, or the other excluded characters — java.net.URI.getHost()
    // returns an IPv6 host in exactly that bracketed form (verified: URI.create("https://[::1]:8443/x")
    // .getHost() == "[::1]"), and AuthenticationInterceptor#originOfReferer forwards it unchanged
    // apart from lowercasing, so a trusted-origins entry must be able to match that shape.
    private static final Pattern ORIGIN_PATTERN =
            Pattern.compile("^(https?)://(\\[[0-9a-fA-F:]+\\]|[^/@:\\[\\]\\s?#]+)(:(\\d+))?$");

    private List<String> trustedOrigins = List.of();
    private List<String> trustedProxies = List.of();
    private boolean directClientConnections = false;
    private AuthRate authRate = new AuthRate();
    private TargetScan targetScan = new TargetScan();
    private ConnectionTest connectionTest = new ConnectionTest();

    public List<String> getTrustedOrigins() {
        return trustedOrigins;
    }

    public void setTrustedOrigins(List<String> trustedOrigins) {
        this.trustedOrigins = trustedOrigins;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public boolean isDirectClientConnections() {
        return directClientConnections;
    }

    public void setDirectClientConnections(boolean directClientConnections) {
        this.directClientConnections = directClientConnections;
    }

    public AuthRate getAuthRate() {
        return authRate;
    }

    public void setAuthRate(AuthRate authRate) {
        this.authRate = authRate;
    }

    public TargetScan getTargetScan() {
        return targetScan;
    }

    public void setTargetScan(TargetScan targetScan) {
        this.targetScan = targetScan;
    }

    public ConnectionTest getConnectionTest() {
        return connectionTest;
    }

    public void setConnectionTest(ConnectionTest connectionTest) {
        this.connectionTest = connectionTest;
    }

    @PostConstruct
    void validate() {
        if (trustedOrigins == null || trustedOrigins.isEmpty()) {
            throw new IllegalStateException(
                    "sm.gateway.trusted-origins must be configured with at least one exact "
                            + "scheme://host[:port] origin; there is no wildcard and no default");
        }
        trustedOrigins.forEach(GatewayProperties::validateOrigin);

        List<String> rawProxies = trustedProxies == null ? List.of() : trustedProxies;
        List<String> canonicalProxies = new ArrayList<>(rawProxies.size());
        for (String candidate : rawProxies) {
            canonicalProxies.add(validateAndCanonicalizeTrustedProxy(candidate));
        }
        this.trustedProxies = List.copyOf(canonicalProxies);

        validateExplicitClientTopologyChoice(this.trustedProxies);

        if (authRate == null) {
            authRate = new AuthRate();
        }
        authRate.validate();
        if (targetScan == null) {
            targetScan = new TargetScan();
        }
        targetScan.validate();
        if (connectionTest == null) {
            connectionTest = new ConnectionTest();
        }
        connectionTest.validate();
    }

    /**
     * {@code sm.gateway.target-scan.*}: the per-session budget of {@code scanHostKeys} (plan 013 T1.4, D-REG 7.1). A
     * scan makes the data plane open connections to a host a caller named, so it is bounded per session: default 10 a
     * minute, a token bucket (the {@code MetricsRateGate} pattern, per minute). It must be positive.
     */
    public static class TargetScan {

        private int permitsPerMinute = 10;

        public int getPermitsPerMinute() {
            return permitsPerMinute;
        }

        public void setPermitsPerMinute(int permitsPerMinute) {
            this.permitsPerMinute = permitsPerMinute;
        }

        void validate() {
            if (permitsPerMinute <= 0) {
                throw new IllegalStateException(
                        "sm.gateway.target-scan.permits-per-minute must be positive, was " + permitsPerMinute);
            }
        }
    }

    /**
     * {@code sm.gateway.connection-test.*}: the per-session budget of {@code createConnection}/{@code
     * updateConnection}'s {@code testBeforeSave} (plan 013 T4, {@code BE-D66}). A SEPARATE counter from {@link
     * TargetScan} — the same mechanism ({@code ScanRateGate}), a second instance — because a Connection test is not a
     * Target scan and the two budgets must not share a bucket. Default 10 a minute. It must be positive.
     */
    public static class ConnectionTest {

        private int permitsPerMinute = 10;

        public int getPermitsPerMinute() {
            return permitsPerMinute;
        }

        public void setPermitsPerMinute(int permitsPerMinute) {
            this.permitsPerMinute = permitsPerMinute;
        }

        void validate() {
            if (permitsPerMinute <= 0) {
                throw new IllegalStateException(
                        "sm.gateway.connection-test.permits-per-minute must be positive, was " + permitsPerMinute);
            }
        }
    }

    /**
     * {@code sm.gateway.auth-rate.*} — the {@code OR-24} pre-authentication rate gate's own
     * configuration. {@code capacity} and {@code refillPerMinute} shape the per-client-address
     * token bucket; {@code maxTrackedKeys} bounds the gate's own memory (see
     * {@link AuthRateGate}) — with no evictable idle bucket left once that many distinct keys are
     * tracked, the gate fails CLOSED for any further new key rather than growing without bound.
     * Every field defaults to a positive value and startup fails if any is configured to zero or
     * negative, since a non-positive capacity or refill would either wedge the gate shut for
     * every caller or never limit anything, and a non-positive key cap would refuse every
     * caller outright.
     */
    public static class AuthRate {

        private int capacity = 10;
        private int refillPerMinute = 10;
        private int maxTrackedKeys = 100_000;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getRefillPerMinute() {
            return refillPerMinute;
        }

        public void setRefillPerMinute(int refillPerMinute) {
            this.refillPerMinute = refillPerMinute;
        }

        public int getMaxTrackedKeys() {
            return maxTrackedKeys;
        }

        public void setMaxTrackedKeys(int maxTrackedKeys) {
            this.maxTrackedKeys = maxTrackedKeys;
        }

        void validate() {
            requirePositive(capacity, "sm.gateway.auth-rate.capacity");
            requirePositive(refillPerMinute, "sm.gateway.auth-rate.refill-per-minute");
            requirePositive(maxTrackedKeys, "sm.gateway.auth-rate.max-tracked-keys");
        }

        private static void requirePositive(int value, String property) {
            if (value <= 0) {
                throw new IllegalStateException(property + " must be positive, was " + value);
            }
        }
    }

    private static void validateOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            throw invalid(String.valueOf(origin), "must not be blank");
        }
        if (origin.contains("*")) {
            throw invalid(origin, "must not contain a wildcard");
        }
        if (!origin.startsWith("http://") && !origin.startsWith("https://")) {
            throw invalid(origin, "must start with exactly 'http://' or 'https://' (lowercase)");
        }
        Matcher matcher = ORIGIN_PATTERN.matcher(origin);
        if (!matcher.matches()) {
            throw invalid(origin, "must be exactly scheme://host[:port] with no userinfo, path, "
                    + "query, trailing slash, or wildcard");
        }
        String scheme = matcher.group(1);
        String host = matcher.group(2);
        String portGroup = matcher.group(4);
        if (!host.equals(host.toLowerCase(Locale.ROOT))) {
            throw invalid(origin, "host must be lowercase");
        }
        if (host.startsWith("[")) {
            validateIpv6CanonicalForm(origin, host);
        }
        if (portGroup != null) {
            int port = Integer.parseInt(portGroup);
            boolean explicitDefaultPort = ("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443);
            if (explicitDefaultPort) {
                throw invalid(origin, "must omit an explicit default port (:80 for http, :443 for https)");
            }
        }
    }

    private static IllegalStateException invalid(String origin, String reason) {
        return new IllegalStateException(
                "sm.gateway.trusted-origins entry '" + origin + "' is invalid: " + reason);
    }

    /**
     * Fix 6: a bracketed IPv6 host is compared byte-for-byte against a browser's {@code Origin}
     * header, and a browser always serialises an IPv6 host in RFC 5952 canonical (compressed) form —
     * e.g. {@code [::1]}, never {@code [0:0:0:0:0:0:0:1]}. An operator-typed entry that is a VALID
     * IPv6 literal but not already in that exact canonical text would silently 403 every request
     * forever (like every other unreachable-origin shape this class already refuses at startup), so
     * this refuses it here instead, naming the canonical form to use.
     *
     * @param host the bracketed host, e.g. {@code "[2001:db8::1]"} — already confirmed lowercase
     */
    private static void validateIpv6CanonicalForm(String origin, String host) {
        String inner = host.substring(1, host.length() - 1);
        String canonical = canonicalRfc5952(origin, inner);
        if (!inner.equals(canonical)) {
            throw invalid(origin, "IPv6 host is not in RFC 5952 canonical form (the form a browser actually "
                    + "sends); use [" + canonical + "] instead");
        }
    }

    /**
     * @return the RFC 5952 canonical text for a bracket-free IPv6 literal: lower-case, no leading
     *         zeros within a group, and the LONGEST run of two or more all-zero groups compressed to
     *         {@code "::"} (leftmost on a tie; a single zero group is never compressed).
     */
    private static String canonicalRfc5952(String origin, String literal) {
        // ClientAddresses.canonicalize returns the FULL, uncompressed, lower-case 8-group form for any
        // valid IPv6 literal (its own javadoc: "0:0:0:0:0:0:0:1", never "::1") — exactly the expansion
        // this needs as its starting point, with no separate parser of its own.
        Optional<String> expanded = ClientAddresses.canonicalize("[" + literal + "]");
        if (expanded.isEmpty() || !expanded.get().contains(":")) {
            throw invalid(origin, "host is not a valid IPv6 literal");
        }
        String[] groups = expanded.get().split(":");
        int bestStart = -1;
        int bestLen = 0;
        int i = 0;
        while (i < groups.length) {
            if (!"0".equals(groups[i])) {
                i++;
                continue;
            }
            int start = i;
            while (i < groups.length && "0".equals(groups[i])) {
                i++;
            }
            int len = i - start;
            if (len > bestLen) {
                bestStart = start;
                bestLen = len;
            }
        }
        if (bestLen < 2) {
            // No run of two or more zero groups — a single zero group is never compressed.
            return String.join(":", groups);
        }
        String left = String.join(":", Arrays.asList(groups).subList(0, bestStart));
        String right = String.join(":", Arrays.asList(groups).subList(bestStart + bestLen, groups.length));
        return left + "::" + right;
    }

    /**
     * @return {@code candidate} canonicalized through {@link ClientAddresses} — the form every
     *         comparison against a resolved peer or forwarded-header entry actually uses
     */
    private static String validateAndCanonicalizeTrustedProxy(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw invalidProxy(String.valueOf(candidate), "must not be blank");
        }
        return ClientAddresses.canonicalize(candidate)
                .orElseThrow(() -> invalidProxy(candidate,
                        "must be an exact IP literal (IPv4 or IPv6), not a hostname or CIDR range"));
    }

    private static IllegalStateException invalidProxy(String value, String reason) {
        return new IllegalStateException(
                "sm.gateway.trusted-proxies entry '" + value + "' is invalid: " + reason);
    }

    /**
     * {@code B1}: the operator must pick exactly one of "a proxy sits in front" ({@code
     * trustedProxies} non-empty) or "no proxy sits in front" ({@code directClientConnections=true}).
     * Neither, or both, is refused at startup rather than guessing — see the class javadoc for why
     * the all-defaults case is exactly the misconfiguration that silently collapses the
     * per-client-address login throttle to one shared key.
     */
    private void validateExplicitClientTopologyChoice(List<String> canonicalProxies) {
        boolean hasTrustedProxies = !canonicalProxies.isEmpty();
        if (hasTrustedProxies && directClientConnections) {
            throw new IllegalStateException(
                    "sm.gateway.trusted-proxies is non-empty AND sm.gateway.direct-client-connections=true: "
                            + "these are contradictory. trusted-proxies declares that a reverse proxy sits in "
                            + "front of this process; direct-client-connections declares that none does and the "
                            + "socket peer IS the client. Configure exactly one.");
        }
        if (!hasTrustedProxies && !directClientConnections) {
            throw new IllegalStateException(
                    "neither sm.gateway.trusted-proxies nor sm.gateway.direct-client-connections=true is "
                            + "configured; the operator must choose explicitly. In the documented topology (a "
                            + "front server proxying /api to this process), every request's socket peer is the "
                            + "proxy, not the caller: with trusted-proxies left empty, the per-client-address "
                            + "login throttle keys on that ONE shared proxy address for every real user, so "
                            + "twenty bad logins from anyone locks out everyone. Set sm.gateway.trusted-proxies "
                            + "to the proxy's address(es) if one sits in front, or "
                            + "sm.gateway.direct-client-connections=true if this process is directly exposed "
                            + "and the socket peer really is the client.");
        }
    }
}
