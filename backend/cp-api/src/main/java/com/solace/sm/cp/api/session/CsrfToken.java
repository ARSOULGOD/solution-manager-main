package com.solace.sm.cp.api.session;

import java.util.Objects;

/** Opaque value for the script-readable CSRF cookie/header pair. Never log this value. */
public final class CsrfToken {

    private final String value;

    public CsrfToken(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return "[redacted csrf token]";
    }
}
