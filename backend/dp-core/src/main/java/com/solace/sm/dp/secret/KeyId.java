package com.solace.sm.dp.secret;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The operator's label for a master key (D-KEY 4.1). A selector, not a secret, so it may appear in log
 * lines and error messages. A malformed one is refused without being quoted, because the variable it
 * came from is one paste away from holding a key.
 */
public record KeyId(String value) {

    /** The grammar, mirrored by {@code sm_secret}'s CHECK constraints. */
    public static final Pattern GRAMMAR = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    public KeyId {
        Objects.requireNonNull(value, "key id");
        if (!GRAMMAR.matcher(value).matches()) {
            throw new IllegalArgumentException("a key id must match " + GRAMMAR.pattern());
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
