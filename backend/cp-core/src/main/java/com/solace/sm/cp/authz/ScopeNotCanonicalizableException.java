package com.solace.sm.cp.authz;

/** A request cannot be resolved to a canonical scope from the current server-side context. */
public class ScopeNotCanonicalizableException extends IllegalArgumentException {

    public ScopeNotCanonicalizableException(String message) {
        super(message);
    }
}
