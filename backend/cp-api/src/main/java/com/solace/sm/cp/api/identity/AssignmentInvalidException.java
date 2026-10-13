package com.solace.sm.cp.api.identity;

import java.util.Objects;

/**
 * A role grant that cannot be made whoever asks: rendered {@code 422 VALIDATION_FAILED}. The message is fixed per
 * {@link Reason} and carries nothing from the request.
 */
public final class AssignmentInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the grant is refused. */
    public enum Reason {
        /** {@code OR-12}: Administrator is bootstrap and emergency standing, never granted over the API. */
        ADMINISTRATOR_NOT_GRANTABLE("the Administrator role is not granted through this surface"),
        /** The role id is not one of the built-in roles. */
        UNKNOWN_ROLE("no such role");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }

    private final Reason reason;

    public AssignmentInvalidException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").message());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
