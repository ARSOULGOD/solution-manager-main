package com.solace.sm.dp.api.config;

/**
 * The secret store would not take the value: it is empty, or larger than a secret may be. Maps to a {@code 422}.
 * Fixed message and no cause: the value is a secret (I6), and a store's own message must never be the one that
 * reaches a response.
 */
public final class SecretValueRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SecretValueRejectedException() {
        super("config.secret.value-rejected");
    }
}
