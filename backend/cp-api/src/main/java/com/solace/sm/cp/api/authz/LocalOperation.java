package com.solace.sm.cp.api.authz;

/**
 * Operations that are authorized by something other than a catalog verb.
 *
 * <p>⛔ <b>These are not permissions and must never be added to {@link Permission}.</b> The
 * catalog is closed at 47 members; an operation that needs a grantable verb which is not
 * there answers {@code 501}. These three are different: each is authorized by the session
 * itself rather than by a binding, so no verb is the right answer rather than a missing one.
 */
public enum LocalOperation {

    /** Authentication only. ⚠️ Available while {@code must_rotate} is set, so a locked-out principal can leave. */
    LOGOUT,

    /** Read the current principal. Authentication-only and available during forced rotation. */
    READ_OWN_IDENTITY,

    /**
     * Rotate one's own credential. Verifies the current credential and clears
     * {@code must_rotate}. ⚠️ {@code user.edit} is a different thing: it is an administrator
     * editing <i>someone else</i>.
     */
    ROTATE_OWN_CREDENTIAL
}
