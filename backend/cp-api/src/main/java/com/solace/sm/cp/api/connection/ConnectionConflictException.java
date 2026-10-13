package com.solace.sm.cp.api.connection;

import java.util.Objects;

/**
 * A Connection write was refused for a reason the gateway must distinguish (plan 013 T2, {@code OR-130}).
 * {@link #kind()} says which answer the gateway gives; the message is a fixed, caller-safe sentence.
 */
public final class ConnectionConflictException extends RuntimeException {

    /** What kind of refusal this is. */
    public enum Kind {
        /** The name is taken by a different record in this scope: the gateway answers {@code 409 CONFLICT}. */
        NAME_TAKEN,
        /** The record changed between the read and the write: the gateway answers {@code 409 CONFLICT} (retry). */
        STALE_WRITE,
        /** {@code usedByCount > 0}: the gateway answers {@code 409 CONFLICT} ({@code deleteConnection} only). */
        IN_USE
    }

    private final Kind kind;

    public ConnectionConflictException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind kind() {
        return kind;
    }
}
