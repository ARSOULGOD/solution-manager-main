package com.solace.sm.cp.api.identity;

import java.util.Objects;

/**
 * A user-administration request that is well-formed and authorized but conflicts with the state of the system. Rendered
 * {@code 409 CONFLICT}.
 *
 * <p>The message is fixed per {@link Reason}: it never carries the username, the principal's id or anything else from
 * the request, and no cause is chained (a driver's unique-violation text reproduces the key).
 */
public final class PrincipalConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the request conflicts. */
    public enum Reason {
        /**
         * {@code principal_org_username_ci_uq}: the username (compared without regard to case) is already held by a
         * user of the SAME organization. Never another organization's: the index is per organization
         * ({@code OR-125 (4)}, which closed the cross-organization username oracle W3D-1 recorded), so this reveals
         * nothing the caller's own {@code user.view} does not already show.
         */
        USERNAME_TAKEN("that username is already taken"),
        /** {@code OR-12}/R18: the caller may not deactivate or reset itself. */
        CALLER_ITSELF("a user cannot be administered by itself through this surface"),
        /**
         * {@code OR-12}: Administrator standing is made and unmade only outside the API, so one administrator cannot
         * lock out another and the last install-scope administrator cannot be removed.
         */
        ADMINISTRATOR_PROTECTED("a user holding an Administrator binding cannot be administered through this surface"),
        /** An identity-provider principal has no local credential to reset. */
        NOT_LOCAL("only a local user has a credential"),
        /** A deactivated user is never reactivated, so its credential is not reset either. */
        DEACTIVATED("the user is deactivated");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }

    private final Reason reason;

    public PrincipalConflictException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").message());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
