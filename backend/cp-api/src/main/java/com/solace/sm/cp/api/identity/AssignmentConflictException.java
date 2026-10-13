package com.solace.sm.cp.api.identity;

import java.util.Objects;

/** A role grant or revocation in conflict with the system's state: rendered {@code 409 CONFLICT}. Fixed messages. */
public final class AssignmentConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the request conflicts. */
    public enum Reason {
        /** {@code OR-12}: an Administrator binding is never revoked over the API, whoever holds it. */
        ADMINISTRATOR_BINDING("an Administrator binding is not revoked through this surface"),
        /** A deactivated user is never reactivated, so a binding to it would be inert. */
        PRINCIPAL_DEACTIVATED("the user is deactivated");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }

    private final Reason reason;

    public AssignmentConflictException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").message());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
