package com.solace.sm.dp.apply.model;

import java.util.regex.Pattern;

/**
 * The three character grammars the evidence types of design §3.1 share, in one place.
 *
 * <p><b>These are SHAPE controls.</b> {@code error_class}, {@code pre_baseline} and
 * {@code runtime_ref} are the three columns §3.1's own table names as "content, not shape — engine
 * classification tests", and this class is deliberately only the first half of that. A grammar that
 * cannot express {@code =}, a space, a quote, a backslash or a newline excludes an environment line
 * ({@code password=hunter2}), a {@code Bearer eyJ…} header, padded base64, PEM, a JSON blob, a stack
 * trace and any exception text — whatever a caller intends, and that much IS structural.
 *
 * <p><b>What it does not exclude is a bare identifier-shaped token.</b> A compact JWT is base64url and
 * dots; a hex secret is hex; both match {@link #VALUE} and both are accepted. So the grammar reduces
 * the surface to "one identifier-shaped token per field" and stops there. The {@code I6} CONTENT
 * obligation is the ADAPTER's: whatever builds a {@link RuntimeRef} or a {@link PreBaseline} is the
 * only writer of these columns, and each adapter must be tested against sentinel secrets drawn from
 * its own inputs. Plan 005 carries that obligation by name.
 *
 * <p><b>A refusal never echoes the value it refused.</b> Naming the rule is diagnosable; repeating the
 * rejected text would copy the very value the type is filtering into a log line, an exception message
 * and a stack trace.
 */
final class ValueGrammar {

    /**
     * Adapter identifiers: container ids, unit names, namespaces, generation-named ConfigMap names.
     * No whitespace, no quotes, no {@code =}, no backslash.
     */
    static final Pattern VALUE = Pattern.compile("^[A-Za-z0-9._:/@+-]{1,128}$");

    /** {@code <algorithm>:<hex>} — the read-back evidence shape the design writes as {@code sha256:…}. */
    static final Pattern HASH = Pattern.compile("^[a-z0-9]+:[0-9a-f]{16,128}$");

    /**
     * Infrastructure-identifier grammar for {@link ReplicaRef} alone: Docker container names and
     * image digests, systemd unit names (including escaped names, which may carry a literal
     * backslash), and Kubernetes RFC 1123 label/subdomain names (up to 253 characters), verified
     * against each runtime's own naming rules (BE-D19) — not {@link #VALUE}, which every OTHER
     * I6-sensitive column keeps using precisely because it excludes backslash.
     */
    static final Pattern REPLICA_REF = Pattern.compile("^[A-Za-z0-9._:/@+\\\\-]{1,255}$");

    private ValueGrammar() {
    }

    /**
     * @param what the field being validated, for the message
     * @param rule the human-readable rule, quoted back to the caller INSTEAD of the value
     * @throws IllegalArgumentException naming {@code what} and {@code rule}, never the value
     */
    static String require(Pattern p, String value, String what, String rule) {
        if (value == null || !p.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " must match " + p.pattern() + " — " + rule
                    + ". The offending value is deliberately not repeated here (I6)");
        }
        return value;
    }
}
