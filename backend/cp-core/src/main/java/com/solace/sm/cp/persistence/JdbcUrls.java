package com.solace.sm.cp.persistence;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * One structural rule about {@code sm_control}'s JDBC URLs, mirroring dp-core's {@code JdbcUrls}
 * ({@code com.solace.sm.dp.apply.persistence.JdbcUrls}) for the identical reason: a URL is a LOCATION,
 * never a credential, and both {@code Flyway} and Spring's {@code SimpleDriverDataSource}/HikariCP log
 * it — Flyway at INFO on every successful migrate, the others at DEBUG on every connect or in
 * pool-startup/connection-failure diagnostics. Whatever the URL carries WILL be logged, so it may carry
 * nothing that is not a location.
 *
 * <p><b>Why cp-core does not simply import dp-core's class.</b> {@code I7} forbids it — this module may
 * not depend on {@code dp-core}. This is therefore an independent copy of the same grammar rather than
 * a shared one; a future extraction to a module both sides may depend on is a separate, deliberate
 * change, not this one.
 *
 * <p><b>Grammar, not blocklist</b> — same rationale as the DP precedent: pgjdbc accepts dozens of
 * properties, and a blocklist of {@code user}/{@code password}/{@code sslpassword} leaves every
 * property this class has never heard of admitted by default (including a free-text {@code options=}
 * payload). The rule is instead positive: the URL must be
 * {@code jdbc:postgresql://host[:port][,host[:port]…]/database}, optionally followed by
 * {@code ?name=value} pairs whose NAMES are in {@link #ALLOWED} and whose VALUES fit that parameter's
 * own grammar. Everything else — including an unrecognised parameter and the userinfo form — is
 * refused, and the refusal never echoes the URL, a value, or an unrecognised parameter's name (a name
 * can carry a secret too, e.g. {@code ?hunter2=1}).
 */
final class JdbcUrls {

    /** The one scheme the grammar admits — pgjdbc's. */
    static final String SCHEME = "jdbc:postgresql://";

    private static final String HOST_PORT = "(?:[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+\\])(?::[0-9]{1,5})?";
    private static final Pattern AUTHORITY = Pattern.compile(HOST_PORT + "(?:," + HOST_PORT + ")*");
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private static final Pattern SSLMODE =
            Pattern.compile("disable|allow|prefer|require|verify-ca|verify-full");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("/[A-Za-z0-9._/-]+");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,63}");
    private static final Pattern SECONDS = Pattern.compile("[0-9]{1,6}");
    private static final Pattern BOOLEAN = Pattern.compile("true|false");

    /**
     * The parameters an {@code sm_control} URL may carry — exact pgjdbc property names — and the
     * grammar each value must match. {@code JdbcUrlsTest} checks every key against {@code PGProperty}.
     */
    static final Map<String, Pattern> ALLOWED;

    static {
        Map<String, Pattern> m = new TreeMap<>();
        m.put("sslmode", SSLMODE);
        m.put("sslrootcert", ABSOLUTE_PATH);
        m.put("sslcert", ABSOLUTE_PATH);
        m.put("sslkey", ABSOLUTE_PATH);
        m.put("ApplicationName", NAME);
        m.put("connectTimeout", SECONDS);
        m.put("socketTimeout", SECONDS);
        m.put("loginTimeout", SECONDS);
        m.put("tcpKeepAlive", BOOLEAN);
        ALLOWED = Collections.unmodifiableMap(m); // a TreeMap, so the refusal lists names in one order
    }

    private JdbcUrls() {
    }

    /**
     * Requires {@code url} to fit the location grammar.
     *
     * @param url the JDBC URL as configured; {@code null} and blank are the CALLER's own check, not
     *            this one's, so they pass through untouched
     * @throws IllegalArgumentException naming which part of the grammar was missed — never the URL, a
     *                                  value, or an unrecognised parameter name
     */
    static void requireLocation(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        if (!url.startsWith(SCHEME)) {
            throw refusal("a scheme other than pgjdbc's");
        }
        String rest = url.substring(SCHEME.length());
        int query = rest.indexOf('?');
        String location = query < 0 ? rest : rest.substring(0, query);
        int slash = location.indexOf('/');
        if (slash < 0) {
            throw refusal("no /database after the host");
        }
        if (!AUTHORITY.matcher(location.substring(0, slash)).matches()) {
            throw refusal("a host part that is not host[:port] (userinfo, an unbracketed IPv6 "
                    + "address or a stray character)");
        }
        if (!DATABASE.matcher(location.substring(slash + 1)).matches()) {
            throw refusal("a database name that is not a plain identifier");
        }
        if (query >= 0) {
            requireAllowedParameters(rest.substring(query + 1));
        }
    }

    /**
     * Strips the query string from an already-validated location URL, for display: even though
     * {@link #requireLocation} guarantees the query holds no credential, {@code toString} on the
     * bound configuration prints the location only — one fewer thing to re-reason about if the
     * allowlist ever grows.
     *
     * @param url a URL that has already passed {@link #requireLocation}; {@code null} passes through
     */
    static String locationOnly(String url) {
        if (url == null) {
            return null;
        }
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static void requireAllowedParameters(String query) {
        String[] pairs = query.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            int equals = pairs[i].indexOf('=');
            if (equals < 0) {
                throw refusal("a query parameter without '=' at position " + (i + 1));
            }
            String name = pairs[i].substring(0, equals);
            Pattern grammar = ALLOWED.get(name);
            if (grammar == null) {
                throw refusal("a query parameter at position " + (i + 1)
                        + " that is not in the allowlist (its name is deliberately not repeated)");
            }
            if (!grammar.matcher(pairs[i].substring(equals + 1)).matches()) {
                throw refusal("a '" + name + "' value outside that parameter's grammar (the value is "
                        + "deliberately not repeated)");
            }
        }
    }

    private static IllegalArgumentException refusal(String what) {
        return new IllegalArgumentException("a JDBC URL must be a location only — pgjdbc's scheme, "
                + "then host[:port]/database, optionally ?name=value pairs from the allowlist "
                + String.join(", ", ALLOWED.keySet()) + " — found " + what
                + ". The username and password are supplied separately, because Flyway and HikariCP "
                + "both LOG the URL. The offending URL is deliberately not repeated here (I6)");
    }
}
