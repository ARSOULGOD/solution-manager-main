package com.solace.sm.cp.identity;

/** Raised when a missing or deactivated principal attempts to establish a session. */
public final class SessionNotIssuableException extends RuntimeException {

    public SessionNotIssuableException() {
        super("session cannot be issued for this principal");
    }
}
