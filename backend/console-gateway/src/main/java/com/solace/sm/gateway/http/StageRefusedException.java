package com.solace.sm.gateway.http;

import java.util.Objects;

/**
 * A stage refused as {@code 422 VALIDATION_FAILED} (OR-34). The reason is a member of a closed set with FIXED text, so
 * nothing the request carried - a value, a key, a secret - can reach the response (the fixed-detail rule of
 * {@link ProblemBody}, {@code I6}).
 */
public final class StageRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why a stage was refused. */
    public enum Reason {
        PACKAGE_UNAVAILABLE("the instance's declared package is not available"),
        SELECTION_UNRESOLVED("the component selection cannot be projected against the declared package"),
        UNDECLARED_KEY("the draft names a key the package does not declare"),
        RENDERED_MISMATCH("the rendered set does not match the server's own projection"),
        BLOCKING_VALIDATION("the draft has a blocking validation"),
        SECRET_LITERAL("a secret field accepts only a reference, never a value"),
        SECRET_REFERENCE_UNRESOLVED("a secret reference does not resolve to a stored secret"),
        /** T10.1: the path names a field, not a component instance; two instances declare it. */
        SECRET_FIELD_AMBIGUOUS("more than one component instance declares this secret field"),
        /** T10.1: the secret store refused the value (empty, or larger than a secret may be). */
        SECRET_VALUE_REJECTED("a secret value is required and is bounded in size");

        private final String detail;

        Reason(String detail) {
            this.detail = detail;
        }

        public String detail() {
            return detail;
        }
    }

    private final Reason reason;

    public StageRefusedException(Reason reason) {
        super("stage refused: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
