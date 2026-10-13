package com.solace.sm.cp.api.authz;

/**
 * Thrown at registration when an operation is mapped to a verb whose consequence class is
 * {@link ConsequenceClass#UNDECIDED}.
 *
 * <p>⛔ <b>This fails at startup, deliberately.</b> The alternative — letting it register and
 * refusing at call time — ships a server whose behaviour depends on whether anyone exercised
 * the endpoint. An unruled verb is a design gap, and a design gap should stop the build, not
 * surprise an operator.
 */
public final class UnshippableOperationException extends RuntimeException {

    public UnshippableOperationException(String operationId, Permission permission) {
        super("operation '" + operationId + "' is mapped to '" + permission.id()
                + "', whose consequence class is UNDECIDED. Such an operation cannot ship: it "
                + "must answer 501 until an owner ruling classifies the verb. Do not relax the "
                + "class to STANDARD to get past this — that is the silent widening the "
                + "three-valued enum exists to prevent.");
    }
}
