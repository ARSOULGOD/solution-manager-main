package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.authz.LocalOperation;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.identity.CredentialRotationRequiredException;
import com.solace.sm.cp.api.identity.ForcedRotationGate;
import com.solace.sm.cp.api.session.Session;
import java.util.Objects;

/** Enforces the server-side forced-rotation state carried by a resolved session. */
public final class MustRotateGate implements ForcedRotationGate {

    @Override
    public void check(Session session, Permission attempted) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(attempted, "attempted");
        rejectIfRotationRequired(session);
    }

    @Override
    public void check(Session session, LocalOperation operation) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(operation, "operation");
    }

    /** Fails closed for an authenticated endpoint that has neither a catalog nor a local operation. */
    @Override
    public void check(Session session) {
        Objects.requireNonNull(session, "session");
        rejectIfRotationRequired(session);
    }

    private static void rejectIfRotationRequired(Session session) {
        if (session.mustRotate()) {
            throw new CredentialRotationRequiredException();
        }
    }
}
