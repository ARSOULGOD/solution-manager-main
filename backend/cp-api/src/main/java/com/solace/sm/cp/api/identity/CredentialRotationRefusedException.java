package com.solace.sm.cp.api.identity;

/**
 * A credential rotation was refused: the current credential did not verify, the replacement equals
 * the current one, the session is no longer usable, or the principal is not in forced rotation.
 * The reasons are deliberately not distinguished.
 */
public final class CredentialRotationRefusedException extends RuntimeException {

    public CredentialRotationRefusedException() {
        super("credential rotation refused");
    }
}
