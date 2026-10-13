package com.solace.sm.gateway.http;

/**
 * Thrown by {@link OperationPolicy#classify(String)} for an operation id that is not
 * {@code login}, not one of the three {@code LocalOperation}s, and not registered in
 * {@code ConsoleOperations}.
 *
 * <p>⛔ This is the fail-closed path: it is deliberately an exception rather than a fallback
 * classification, so a handler carrying such an id cannot be authorized and cannot start.
 */
public final class UnclassifiableOperationException extends RuntimeException {

    public UnclassifiableOperationException(String operationId) {
        super("'" + operationId + "' is not classifiable as session-establishing, a local "
                + "operation, or a catalog verb; it cannot be authorized and must not be served");
    }
}
