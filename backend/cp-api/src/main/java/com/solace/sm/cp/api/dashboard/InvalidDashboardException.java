package com.solace.sm.cp.api.dashboard;

import java.util.Objects;

/**
 * A dashboard write was refused. {@link #kind()} says which answer the gateway gives; the message is a fixed,
 * caller-safe sentence that never echoes the submitted document.
 */
public final class InvalidDashboardException extends RuntimeException {

    /** What kind of refusal this is. */
    public enum Kind {
        /** The submitted document, layout or request is not valid: the gateway answers {@code 400 BAD_REQUEST} (console.yaml's dashboard routes declare no 422). */
        INVALID,
        /** The caller's revision is not the stored one: the gateway answers {@code 409 CONFLICT}. */
        STALE_REVISION,
        /** The widget named does not exist on this dashboard, or is not one that may be hidden: the gateway answers {@code 404 NOT_FOUND}. */
        UNKNOWN_WIDGET
    }

    private final Kind kind;

    public InvalidDashboardException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind kind() {
        return kind;
    }
}
