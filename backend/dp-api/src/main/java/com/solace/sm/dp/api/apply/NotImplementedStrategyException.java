package com.solace.sm.dp.api.apply;

/**
 * A strategy the contract names and v1 does not serve: {@code restart-all}, {@code rolling-no-validation} or
 * {@code scheduled} have no approved verb (open item 39). Answered {@code 501}, never {@code 403}.
 */
public final class NotImplementedStrategyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotImplementedStrategyException(String strategy) {
        // the token is one of five closed values; it is still not echoed to a client
        super("strategy not served: " + strategy);
    }
}
