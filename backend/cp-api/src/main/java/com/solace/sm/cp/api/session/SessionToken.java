package com.solace.sm.cp.api.session;

import java.util.Objects;

/** Opaque bearer value for the httpOnly session cookie. Never log this value. */
public final class SessionToken {

    private final String value;

    public SessionToken(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return "[redacted session token]";
    }
}
