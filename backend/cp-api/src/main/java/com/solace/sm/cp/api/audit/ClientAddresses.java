package com.solace.sm.cp.api.audit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The ONE canonical spelling of a client IP address.
 *
 * <p>An address keys the per-address login throttle ({@code OR-20}), is compared against configured
 * trusted proxies, is written to the audit trail ({@code OR-19}) and is typed by an operator into the
 * lockout-recovery command. If those four places spell one address differently — {@code ::1} versus
 * {@code 0:0:0:0:0:0:0:1}, which is what a servlet container reports — one client gets two throttle
 * keys, a trusted proxy silently fails to match, and recovery clears nothing. So every address goes
 * through {@link #canonicalize} and nothing else.
 *
 * <p>⛔ <b>Never performs a DNS lookup.</b> Only literals are accepted: a dotted IPv4 literal, or a
 * colon-bearing IPv6 literal, for which {@link InetAddress#getByName} checks only the format. A hostname
 * is refused, not resolved.
 */
public final class ClientAddresses {

    private static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
            + "(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private ClientAddresses() {
    }

    /**
     * True only for a string {@link InetAddress#getByName} parses as an IPv6 LITERAL — and therefore
     * never hands to the system resolver.
     *
     * <p>⚠️ The character class alone is not enough, and a review measured why: the JDK treats a string as a
     * literal only when its first character is a hex digit or {@code ':'}. {@code ".:1"} passes the class,
     * is not parsed as a literal, and falls through to {@code getaddrinfo} — a blocking name-service call
     * per request, on a path documented as lookup-free. So the first character is gated here, BEFORE
     * {@code getByName} is ever reached.
     */
    static boolean isParsedAsIpv6Literal(String candidate) {
        if (candidate.isEmpty() || candidate.indexOf(':') < 0 || !IPV6_CHARS.matcher(candidate).matches()) {
            return false;
        }
        char first = candidate.charAt(0);
        return first == ':' || Character.digit(first, 16) >= 0;
    }

    /**
     * @return the canonical literal — IPv4 dotted-quad without leading zeros, or IPv6 in Java's full
     *         lower-case form without brackets or zone; an IPv4-mapped IPv6 address canonicalises to its
     *         IPv4 form — or empty when {@code raw} is not an IP literal
     */
    public static Optional<String> canonicalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String candidate = raw.trim();
        if (candidate.length() > 2 && candidate.charAt(0) == '[' && candidate.charAt(candidate.length() - 1) == ']') {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        int zone = candidate.indexOf('%');
        if (zone >= 0) {
            candidate = candidate.substring(0, zone);
        }
        if (IPV4.matcher(candidate).matches()) {
            return Optional.of(candidate);
        }
        if (!isParsedAsIpv6Literal(candidate)) {
            return Optional.empty();
        }
        try {
            // A colon-bearing string is parsed as an IPv6 literal only — never looked up.
            return Optional.of(InetAddress.getByName(candidate).getHostAddress().toLowerCase(java.util.Locale.ROOT));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    /**
     * The THROTTLING identity of a client — what the pre-authentication rate gate ({@code OR-24}) and
     * the durable per-address login throttle ({@code OR-20}) both key on, so the two can never
     * disagree about who "one client" is. An IPv4 address is its own key. An IPv6 address is keyed
     * by its {@code /64}: one host routinely holds a whole {@code /64}, so keying by the full address
     * would let one attacker spread guesses across an effectively unlimited number of keys and
     * never trip a lockout. The audit trail still records the full address.
     *
     * @param canonical an address already returned by {@link #canonicalize}
     * @return {@code canonical} for IPv4, or {@code "<g1>:<g2>:<g3>:<g4>::/64"} for IPv6
     * @throws IllegalArgumentException if {@code canonical} is not in canonical form
     */
    public static String throttleKey(String canonical) {
        if (canonical == null || !canonicalize(canonical).map(canonical::equals).orElse(false)) {
            throw new IllegalArgumentException("not a canonical client address");
        }
        if (canonical.indexOf(':') < 0) {
            return canonical;
        }
        String[] groups = canonical.split(":");
        return String.join(":", groups[0], groups[1], groups[2], groups[3]) + "::/64";
    }
}
