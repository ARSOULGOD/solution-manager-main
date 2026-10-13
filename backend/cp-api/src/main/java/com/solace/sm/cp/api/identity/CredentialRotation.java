package com.solace.sm.cp.api.identity;

import com.solace.sm.cp.api.session.Session;

/**
 * Rotates the calling session's own local credential.
 *
 * <p>Atomic: the replacement hash, the cleared {@code must_rotate} flag and the revocation of every
 * <b>other</b> session of the principal commit together or not at all. The calling session survives.
 */
public interface CredentialRotation {

    /**
     * @throws CredentialRotationRefusedException for any refusal; the reasons are not distinguished
     */
    void rotate(Session current, char[] currentCredential, char[] replacementCredential,
                com.solace.sm.cp.api.audit.RequestOrigin origin);
}
