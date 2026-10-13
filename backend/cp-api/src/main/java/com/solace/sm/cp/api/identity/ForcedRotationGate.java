package com.solace.sm.cp.api.identity;

import com.solace.sm.cp.api.authz.LocalOperation;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.session.Session;

/**
 * The one server-side enforcement of forced credential rotation.
 *
 * <p>A session whose principal must rotate may perform only the three {@link LocalOperation}s —
 * read its own identity, rotate its own credential, log out. Every catalog verb, and every
 * authenticated operation that is neither, is refused with
 * {@link CredentialRotationRequiredException}.
 */
public interface ForcedRotationGate {

    /** @throws CredentialRotationRequiredException if the session must rotate */
    void check(Session session, Permission attempted);

    /** Permits the three local operations regardless of rotation state. */
    void check(Session session, LocalOperation operation);

    /**
     * Fails closed for an authenticated operation that carries neither a catalog verb nor a local
     * operation.
     *
     * @throws CredentialRotationRequiredException if the session must rotate
     */
    void check(Session session);
}
