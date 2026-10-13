package com.solace.sm.dp.apply.model;

import java.util.regex.Pattern;

/**
 * {@code apply_attempt.error_class} — a CLASSIFIED, greppable code, never raw exception text.
 *
 * <p><b>This is a SHAPE control.</b> The grammar — dotted, lower-case,
 * {@code ^[a-z][a-z0-9]*(\.[a-z0-9]+)*$}, at most {@value #MAX_LENGTH} characters — cannot express
 * {@code =}, a space, a quote, a slash, an uppercase letter or a newline, so an environment line
 * ({@code password=hunter2}), a {@code Bearer} header or any structured secret representation, padded
 * base64, PEM, a JSON blob, a stack trace and raw exception text are all excluded by construction. What
 * fits is what the design writes: {@code io.timeout}, {@code docker.api.unreachable},
 * {@code hash.mismatch}.
 *
 * <p><b>It is not a content control.</b> A bare lower-case identifier-shaped token still fits — a hex
 * secret is lower-case hex. Design §3.1's residual table calls {@code I6} in {@code error_class}
 * "content, not shape", pinned by "engine classification tests", and that half is the CALLER's: the
 * engine (and, in Plan 005, each adapter) chooses the classification, and its own tests are where a
 * classifier that pastes a token into a code is caught. This type narrows the surface to one dotted
 * lower-case token; it does not decide what that token means.
 *
 * <p>The column is {@code text}, so there is no JSON here: the persistence layer stores
 * {@link #token} directly.
 *
 * @param token the classification code
 */
public record ErrorClass(String token) {

    /** Long enough for a four-segment dotted code; short enough that no payload fits. */
    public static final int MAX_LENGTH = 64;

    private static final Pattern TOKEN = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z0-9]+)*$");

    public ErrorClass {
        // The length is checked first and reported WITH the pattern, so every refusal from this
        // constructor names the same rule whichever half of it was broken.
        if (token == null || token.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("an error class must match " + TOKEN.pattern()
                    + " and be at most " + MAX_LENGTH + " characters — a CLASSIFIED code such as "
                    + "io.timeout, never raw error text (I6). Was "
                    + (token == null ? "null" : token.length() + " characters")
                    + "; the offending value is deliberately not repeated here");
        }
        ValueGrammar.require(TOKEN, token, "an error class",
                "a CLASSIFIED code such as io.timeout or docker.api.unreachable, never raw error "
                        + "text (I6)");
    }
}
