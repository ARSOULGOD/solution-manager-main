package com.solace.sm.cp.identity;

/** Raised when a local credential rotation cannot be completed. */
public final class CredentialRotationRefusedException extends RuntimeException {

    public CredentialRotationRefusedException() {
        super("credential rotation was refused");
    }
}
