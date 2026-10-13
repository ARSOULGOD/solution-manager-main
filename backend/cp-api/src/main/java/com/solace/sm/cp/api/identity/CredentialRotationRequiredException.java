package com.solace.sm.cp.api.identity;

/**
 * The session is authenticated but its principal must rotate the credential before anything other
 * than identity, rotation and logout.
 *
 * <p>⚠️ Not an authorization denial. It is the credential that is provisional, not the grant, so it
 * must not be reported as {@code 403}: that would send an operator hunting for a permission that is
 * not missing.
 */
public final class CredentialRotationRequiredException extends RuntimeException {

    public CredentialRotationRequiredException() {
        super("credential rotation is required");
    }
}
