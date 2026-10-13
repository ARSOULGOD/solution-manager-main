package com.solace.sm.cp.api.governance;

/**
 * The organization already runs as many instances of the solution as its entitlement allows ({@code OR-40}).
 * Rendered {@code 409 ENTITLEMENT_REFUSED}, never {@code 403}: the verb is held, the quota is what is exhausted.
 * Carries no message derived from the request.
 */
public final class QuotaExceededException extends RuntimeException {

    public QuotaExceededException() {
        super("the organization's instance quota for this solution is exhausted");
    }
}
