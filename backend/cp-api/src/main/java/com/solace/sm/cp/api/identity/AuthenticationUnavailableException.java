package com.solace.sm.cp.api.identity;

/**
 * Credential verification could not run — its bounded concurrency capacity is exhausted.
 *
 * <p>⛔ Not a credential outcome, and never to be reported as one: it says nothing about whether the
 * presented credential is valid, so it maps to a retryable unavailability, not to a login refusal.
 */
public final class AuthenticationUnavailableException extends RuntimeException {

    public AuthenticationUnavailableException() {
        super("credential verification is temporarily unavailable");
    }
}
