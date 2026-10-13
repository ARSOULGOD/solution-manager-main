package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.audit.ClientAddresses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the client address {@code RequestOrigin} carries (ruling {@code OR-20}).
 *
 * <p>⛔ <b>With the default empty {@code sm.gateway.trusted-proxies} list, {@code
 * X-Forwarded-For} is ignored completely</b> — the socket peer ({@code request.getRemoteAddr()})
 * is the client address, full stop. Only when the immediate peer is itself a configured trusted
 * proxy does this consult the header at all, and then it walks it right to left, skipping every
 * entry that is also a trusted proxy, and returns the first entry that is not. A chain of several
 * trusted proxies therefore resolves to the right-most entry that isn't one of them; if the
 * header is absent or entirely blank, or every entry the walk reaches is either a trusted proxy
 * or itself blank, this falls back to the socket peer.
 *
 * <p>Fix ({@code m1}): <b>a blank comma-separated entry (e.g. a stray {@code "a,,b"}, or an
 * attacker-sent empty {@code X-Forwarded-For} FIELD ahead of a trusted proxy's own genuinely
 * appended one) carries no claim about the client address at all, and is simply skipped</b> —
 * the walk continues past it rather than treating it as reason to distrust every entry. Before
 * this fix, a blank entry made the WHOLE header untrusted and fell back to the proxy's own
 * address, which let an attacker who could inject an empty forwarded-for entry ahead of the
 * proxy's end up keyed and audited as the proxy itself (attribution evasion, and a shared
 * throttle bucket with every other real client). A non-blank entry that is not a valid IP
 * literal is different — that IS still treated as reason to distrust the whole header (below),
 * because unlike a blank entry it actively claims to BE an address and fails to parse as one.
 *
 * <p>⛔ <b>Every address is canonicalized through {@link ClientAddresses} before it is ever
 * compared or returned</b> — the socket peer, every {@code X-Forwarded-For} entry, AND (at
 * startup, in {@link GatewayProperties}) every configured {@code trusted-proxies} entry. A
 * servlet container may report a peer in a different but equal IPv6 spelling than whatever an
 * operator configured (Tomcat reports {@code 0:0:0:0:0:0:0:1} for what an operator would write as
 * {@code ::1}), and comparing un-canonicalized strings would silently fail to recognize a
 * genuinely trusted proxy. {@link ClientAddresses#canonicalize} also strips brackets and a zone
 * suffix, so a bracketed or zone-suffixed literal (e.g. {@code [2001:db8::1]}, {@code fe80::1%lo0})
 * needs no separate handling here. A forwarded-header entry that fails to canonicalize — i.e. is
 * not an IP literal at all — is attacker-supplied text, not an address: it would otherwise be
 * written verbatim into the audit trail's {@code client_address} and key the throttle, so trust
 * nothing about the header and fall back to the socket peer instead of adopting it.
 *
 * <p>Fix 3: <b>a trailing {@code :port} is stripped from an entry before canonicalizing</b> — a
 * port-appending reverse proxy (one that writes {@code X-Forwarded-For: 203.0.113.9:54321}) would
 * otherwise make every entry fail to canonicalize as an IP literal, discarding the whole header
 * back to the proxy's own peer address and collapsing the {@code OR-20} per-client throttle to one
 * shared key for every real client behind it. Stripped for an IPv4 entry ({@code a.b.c.d:port}) and
 * a BRACKETED IPv6 entry ({@code [literal]:port}) only — an UNBRACKETED IPv6 entry is never
 * port-stripped, because a trailing {@code :group} is indistinguishable from a genuine hex group
 * (e.g. {@code 2001:db8::9:443} is itself a complete, valid address; treating {@code :443} as a
 * port would silently corrupt it).
 */
public final class ClientAddressResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ClientAddressResolver.class);
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    /**
     * Fix 3: de-duplicates the "discarded as malformed" WARN below to ONE per (proxy, reason) for
     * the life of the process — never per request. A port-appending or otherwise misbehaving proxy
     * can push this header on every single request; without this, that becomes a log line per
     * request forever. Static and process-wide by design, not per-instance: the gateway wires
     * exactly one {@link ClientAddressResolver} bean, but "per process" is the actual guarantee this
     * makes, independent of how many instances happen to exist.
     */
    private static final Set<String> WARNED_MALFORMED_PROXIES = ConcurrentHashMap.newKeySet();

    private final GatewayProperties properties;

    public ClientAddressResolver(GatewayProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        String peer = canonicalizeOrRaw(request.getRemoteAddr());
        if (!properties.getTrustedProxies().contains(peer)) {
            return peer;
        }
        String header = joinAllForwardedForFields(request);
        if (header == null || header.isBlank()) {
            return peer;
        }
        String[] rawEntries = header.split(",");
        String[] entries = new String[rawEntries.length];
        for (int i = 0; i < rawEntries.length; i++) {
            entries[i] = rawEntries[i].trim();
        }
        for (int i = entries.length - 1; i >= 0; i--) {
            if (entries[i].isEmpty()) {
                // Fix (m1): a blank entry carries no claim about the client address — unlike a
                // non-IP entry below, there is nothing here to distrust. Skip it and keep walking;
                // if nothing non-blank remains, the loop falls through to the peer at the bottom.
                continue;
            }
            Optional<String> canonical = ClientAddresses.canonicalize(stripTrailingPort(entries[i]));
            if (canonical.isEmpty()) {
                // Not an address — attacker-supplied text. It would otherwise be written verbatim
                // into the audit trail's client_address and key the throttle, so trust nothing
                // about this header and fall back to the socket peer.
                warnDiscardedOnce(peer, "a forwarded-for entry was not a valid IP literal");
                return peer;
            }
            if (!properties.getTrustedProxies().contains(canonical.get())) {
                return canonical.get();
            }
        }
        return peer; // every entry, including the peer, is a trusted proxy
    }

    /**
     * Fix (external finding 3): {@code HttpServletRequest.getHeader} returns only the FIRST
     * {@code X-Forwarded-For} field when a request carries several separate instances of it —
     * RFC 7230 §3.2.2 permits a field name to repeat, and combining the repeats is exactly what
     * {@code getHeader} does NOT do. A reverse proxy that appends its own field rather than
     * editing an existing one (a common, spec-legal implementation) preserves whatever field an
     * attacker sitting in front of it sent, and that attacker-controlled field arrives FIRST. So
     * every field instance is read, via {@link HttpServletRequest#getHeaders}, and joined with a
     * comma in the order the container reports them — RFC 7230 §3.2.2 permits exactly this join
     * for a repeatable field — before the existing right-to-left, trusted-proxy-skipping walk runs
     * unchanged over the combined value. The proxy-appended field, always the last one a
     * correctly-configured proxy adds, therefore still lands at the rightmost position the walk
     * already trusts most.
     *
     * @return the joined header value, or {@code null} if the header is absent entirely
     */
    private static String joinAllForwardedForFields(HttpServletRequest request) {
        Enumeration<String> fields = request.getHeaders(FORWARDED_FOR_HEADER);
        if (fields == null || !fields.hasMoreElements()) {
            return null;
        }
        return String.join(",", Collections.list(fields));
    }

    /**
     * The socket peer a servlet container reports is always a real IP literal in practice; this
     * falls back to the raw value only as defence in depth against a container ever reporting
     * something {@link ClientAddresses} cannot parse, rather than throwing out of a resolver whose
     * contract is to always return something.
     */
    private static String canonicalizeOrRaw(String raw) {
        return ClientAddresses.canonicalize(raw).orElse(raw);
    }

    /**
     * Strips a trailing {@code :port} from an IPv4 entry ({@code a.b.c.d:port}) or a BRACKETED IPv6
     * entry ({@code [literal]:port}) — see the class javadoc for why an unbracketed IPv6 entry is
     * left untouched. Anything else (no port suffix, or a shape this does not recognize) is returned
     * unchanged; {@link ClientAddresses#canonicalize} is what ultimately decides validity.
     */
    private static String stripTrailingPort(String candidate) {
        if (candidate.startsWith("[")) {
            int closeBracket = candidate.indexOf(']');
            if (closeBracket < 0 || closeBracket + 1 >= candidate.length()
                    || candidate.charAt(closeBracket + 1) != ':') {
                return candidate; // no bracketed-then-port shape — leave it for canonicalize to judge
            }
            String portPart = candidate.substring(closeBracket + 2);
            return isAllDigitsAndNonEmpty(portPart) ? candidate.substring(0, closeBracket + 1) : candidate;
        }
        int lastColon = candidate.lastIndexOf(':');
        if (lastColon <= 0) {
            return candidate; // no colon at all — nothing to strip
        }
        String hostPart = candidate.substring(0, lastColon);
        String portPart = candidate.substring(lastColon + 1);
        // A host part containing ITS OWN colon is an unbracketed IPv6 literal (or malformed) — never
        // port-stripped, because a trailing ":group" is indistinguishable from a genuine hex group.
        if (hostPart.indexOf(':') >= 0) {
            return candidate;
        }
        return isAllDigitsAndNonEmpty(portPart) ? hostPart : candidate;
    }

    private static boolean isAllDigitsAndNonEmpty(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Logs ONE WARN per (proxy, reason) for the life of the process — never the header content, only
     * the trusted proxy's own address and why its header was discarded (see the class javadoc and
     * {@link #WARNED_MALFORMED_PROXIES}).
     */
    private static void warnDiscardedOnce(String proxyAddress, String reason) {
        if (WARNED_MALFORMED_PROXIES.add(proxyAddress + '|' + reason)) {
            LOG.warn("discarding X-Forwarded-For from trusted proxy {} as malformed: {}", proxyAddress, reason);
        }
    }
}
