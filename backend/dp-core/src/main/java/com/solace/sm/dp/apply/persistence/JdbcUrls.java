package com.solace.sm.dp.apply.persistence;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * One structural rule about JDBC URLs: a URL is a LOCATION, never a credential — enforced as a
 * grammar the URL must fit, not as a list of things it must not contain.
 *
 * <p><b>Why this is a refusal and not a lint.</b> Both consumers of an {@code sm_data} URL print it.
 * Flyway logs {@code Database: <url> (PostgreSQL 14.24)} at INFO on every successful migrate, and
 * Spring's {@code SimpleDriverDataSource} logs it again at DEBUG on each connect — both MEASURED, see
 * {@link SmDataMigrationMain}'s javadoc for the captured lines — while HikariCP names the URL in its
 * pool-startup and connection-failure diagnostics. Design §3.1 puts the two login secrets in the
 * install runbook — an environment variable, a mounted secret, a managed store — precisely so they exist
 * in as few places as possible. Whatever the URL carries WILL be logged, so the URL may carry nothing
 * that is not a location.
 *
 * <p><b>Why a grammar and not a blocklist.</b> A first version of this class refused the parameter
 * names {@code user}, {@code password} and {@code sslpassword} and the {@code alice:hunter2@host}
 * userinfo form, and said so. An external review pointed out what that leaves open: pgjdbc accepts
 * dozens of properties, and a value smuggled into one this class did not name — or into an
 * {@code options=} payload — would be admitted and then printed by Flyway. A blocklist can only be as
 * complete as its author's knowledge of the driver. So the rule is now positive: the URL must be
 * {@code jdbc:postgresql://host[:port][,host[:port]…]/database}, optionally followed by
 * {@code ?name=value} pairs whose NAMES are in {@link #ALLOWED} — exact pgjdbc property names, nine of
 * them, each a connection or TLS setting an installer legitimately needs — and whose VALUES match that
 * parameter's own grammar (a keyword, a number, an absolute path, an identifier). Everything else is
 * refused, including every parameter this class has never heard of and the userinfo form, which the
 * host grammar simply does not contain. ({@code Driver.parseURL} does not parse userinfo — measured —
 * but libpq/psql URIs do carry it, and a URI copied from a psql session should fail here, by name,
 * rather than at connect time for a reason nobody would connect to a leak.) Adding a parameter is a
 * deliberate, reviewed edit of the map — which is the point.
 *
 * <p><b>What the grammar does NOT decide, stated</b> — the same residual {@code ValueGrammar} states for
 * evidence: a value that FITS an allowlisted grammar is accepted whatever it means. {@code ?ApplicationName=hunter2}
 * or {@code ?sslkey=/etc/sm/hunter2} passes and is then printed by Flyway at INFO. The grammar bounds the
 * shape of what can be smuggled (an identifier, an absolute path, a keyword, a number); it cannot know that a
 * particular identifier is a secret. An installer that puts a secret where a name goes has defeated a
 * shape control, not a content one, and the runbook says so.
 *
 * <p><b>Both credentials, not just the password:</b> a leaked role name is half of a credential and,
 * under §3.1's topology, tells a reader whether the process holds {@code sm_dp_migrator} (schema owner)
 * or {@code sm_dp_runtime}. The role therefore has no place in the grammar either.
 *
 * <p><b>The refusal never echoes the URL (nor even the literal scheme), a parameter value, or an
 * unrecognised parameter NAME</b> — a name is just as capable of carrying a secret as a value
 * ({@code ?hunter2=1}), so an unknown parameter is reported by its POSITION in the query string. Only names from {@link #ALLOWED} are
 * ever printed, because those are this class's own words. Like every other {@code I6} refusal in this
 * codebase, the message names the RULE and never the offending input.
 */
final class JdbcUrls {

    /** The one scheme the grammar admits — pgjdbc's. The design's sole truth is PostgreSQL. */
    static final String SCHEME = "jdbc:postgresql://";

    /** {@code host[:port]}, comma-separated for pgjdbc's multi-host form; IPv6 in brackets. */
    private static final String HOST_PORT = "(?:[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+\\])(?::[0-9]{1,5})?";
    private static final Pattern AUTHORITY = Pattern.compile(HOST_PORT + "(?:," + HOST_PORT + ")*");

    /** A PostgreSQL database name as an ordinary identifier — no quoting, no URL-encoding. */
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private static final Pattern SSLMODE =
            Pattern.compile("disable|allow|prefer|require|verify-ca|verify-full");
    /** An absolute POSIX path without spaces — a mounted certificate or key. */
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("/[A-Za-z0-9._/-]+");
    /** An application name as PostgreSQL shows it in {@code pg_stat_activity}. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,63}");
    /** A timeout: pgjdbc's are whole seconds. */
    private static final Pattern SECONDS = Pattern.compile("[0-9]{1,6}");
    private static final Pattern BOOLEAN = Pattern.compile("true|false");

    /**
     * The parameters an {@code sm_data} URL may carry — exact pgjdbc property names — and the grammar
     * each value must match. {@code JdbcUrlsTest} checks every key against {@code PGProperty}, so a
     * misspelling cannot survive the build.
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
     * @throws IllegalArgumentException naming which part of the grammar was missed — never the URL,
     *                                  a value, or an unrecognised parameter name
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
     * {@code name=value} pairs separated by {@code &} — pgjdbc's separator; {@code ;} is not one, and
     * a pair containing it fails its value grammar. An EMPTY query ({@code …/db?}) is refused too:
     * pgjdbc accepts it (measured), but nothing legitimate writes it, and the grammar admits what an
     * installer needs rather than everything the driver tolerates.
     */
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
        // The scheme is described, not spelled: every stream SmDataMigrationMain prints is held to
        // "contains no 'jdbc:' at all", and this message is printed there.
        return new IllegalArgumentException("a JDBC URL must be a location only — pgjdbc's scheme, "
                + "then host[:port]/database, optionally ?name=value pairs from the allowlist "
                + String.join(", ", ALLOWED.keySet()) + " — found " + what
                + ". The username and password are supplied separately (design §3.1: they are install "
                + "runbook material), because Flyway and HikariCP both LOG the URL. The offending URL "
                + "is deliberately not repeated here (I6)");
    }
}
