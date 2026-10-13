package com.solace.sm.cp.api.catalog;

import java.util.Objects;

/**
 * An entitlement refuses the request ({@code OR-40}, {@code OR-121}). Rendered {@code 409 ENTITLEMENT_REFUSED}, never
 * {@code 403}: the verb is held, the entitlement is what stands in the way. Carries no message derived from the
 * request.
 *
 * <p>Two reasons share the code because the caller's remedy is the same (the entitlement, not the permission, must
 * change): {@link Reason#NOT_ENTITLED} (the package load and the declare path: the organization holds no entitlement
 * for the solution, or no free slot) and {@link Reason#BELOW_DECLARED} ({@code PUT /cp/entitlements/{solution}}: the
 * new limit is below the number of instances already declared).
 */
public final class EntitlementRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the entitlement refused. */
    public enum Reason {
        /** The organization is not entitled to the solution (the original meaning of this exception). */
        NOT_ENTITLED("the organization is not entitled to this solution"),
        /** A limit below the declared instance count was asked for. */
        BELOW_DECLARED("the limit is below the number of instances already declared");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    private final Reason reason;

    public EntitlementRefusedException() {
        this(Reason.NOT_ENTITLED);
    }

    public EntitlementRefusedException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
