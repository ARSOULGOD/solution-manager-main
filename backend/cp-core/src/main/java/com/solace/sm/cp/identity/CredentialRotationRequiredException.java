package com.solace.sm.cp.identity;

/** Raised when a principal with a bootstrap credential attempts a non-rotation operation. */
public final class CredentialRotationRequiredException extends RuntimeException {

    public CredentialRotationRequiredException() {
        super("credential rotation is required");
    }
}
