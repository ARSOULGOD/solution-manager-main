package com.solace.sm.cp.api.identity;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;

/**
 * The operator user's lifecycle (plan 013 T3.1, D-REG 5.4 U1 to U3, {@code OQ-6 (a)}): list, create, deactivate and
 * reset the credential of a <b>local</b> principal in the caller's own organization.
 *
 * <p><b>Authorization</b> is by verb at the <i>organization</i>, never by role: {@code user.view} (list),
 * {@code user.create} (create), {@code user.deactivate} (deactivate), {@code user.edit} (reset). Administering a
 * principal is an organization act ({@code ResourceKind.PRINCIPAL}), so a principal bound below the organization
 * cannot administer users. Every method calls {@code AuthorizationService.require} itself, <b>before</b> any read or
 * write that could reveal anything, and before the (expensive) credential hash. D-REG 4 records the divergence from
 * {@code console-integration.md} 5.2 (which says {@code install ↓}); the code's reading is built, the owner's call
 * stands open.
 *
 * <p><b>Administrator standing is never touched here</b> ({@code OR-12}, {@code OQ-6 (a)}, R18): a principal holding an
 * Administrator binding at any scope, and the caller itself, can be neither deactivated nor have its credential reset
 * ({@link PrincipalConflictException}). One administrator therefore cannot lock out another.
 *
 * <p><b>A reset does not take over standing the caller lacks</b> (W3D-4): if the target holds role bindings, the caller
 * must hold every verb of them at the scope the target holds it, or the reset is an {@link AccessDeniedException}
 * ({@code user.edit}; the refusal names nothing of the target). Today only the Administrator may call it and it holds the
 * whole catalog, so the rule refuses no one in v1; it keeps a future intermediate role from escalating by resetting an
 * Operator's credential. Deactivation is not subject to it.
 *
 * <p><b>The username</b> is 1 to {@value #MAX_USERNAME_LENGTH} code points, kept exactly as given (never stripped; the
 * case you create it in is the case it is shown in), in Unicode normalization form C, and containing no whitespace,
 * control, format (zero-width, bidi override, soft hyphen, byte-order mark), private-use or unassigned code point and no
 * unpaired surrogate (W3D-3). It is unique <b>per organization</b>, compared without regard to case
 * ({@code principal_org_username_ci_uq}, {@code OR-125 (4)}): the same name in another organization is another user, so
 * a {@code 409} here never reveals another organization's usernames, and login (which names the organization) matches
 * the same way. See {@link PrincipalConflictException.Reason#USERNAME_TAKEN}.
 *
 * <p><b>The credential.</b> {@code initialCredential} and {@code resetCredential} are {@code char[]}s that are hashed
 * with Argon2id ({@code OR-7}) and never stored, logged, audited or returned; the callee does not retain them (the
 * caller owns, and should clear, its array). A credential is {@value #MIN_CREDENTIAL_LENGTH} to
 * {@value #MAX_CREDENTIAL_LENGTH} characters (the floor {@code rotateOwnCredential} also applies). A created or reset
 * principal has {@code must_rotate} set: the credential is provisional.
 *
 * <p><b>Sessions.</b> Deactivation and a credential reset each end every live session of the principal <i>in the same
 * transaction</i> as the change, so a deactivated user does not keep a live session.
 *
 * <p>⛔ <b>{@code I5}</b>: the organization is the session's (an argument the gateway fills from
 * {@code Session.organizationId()}); no request names one. A principal of another organization is exactly as absent as
 * a nonexistent one.
 *
 * <p><b>Failures</b>: {@link AccessDeniedException} when the verb is not held at the organization;
 * {@link ResourceNotFoundException} when the principal does not exist in the organization (or the caller's scope hides
 * it); {@link PrincipalConflictException}; {@link IllegalArgumentException} for a username or credential of the wrong
 * shape (the message never echoes the value).
 */
public interface PrincipalAdministration {

    int MIN_CREDENTIAL_LENGTH = 12;
    int MAX_CREDENTIAL_LENGTH = 1024;
    int MAX_USERNAME_LENGTH = 64;

    /** Every principal of the organization, deactivated ones included, ordered by username then id. */
    List<UserView> list(PrincipalRef actor, UUID organizationId);

    /**
     * A local principal, {@code must_rotate}. There is no idempotent replay: a repeated create is
     * {@link PrincipalConflictException.Reason#USERNAME_TAKEN} (D-REG S5), so a console that timed out lists users to see
     * whether the create landed.
     */
    UserView create(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String username,
            char[] initialCredential);

    /** Idempotent: deactivating a deactivated user returns it unchanged and writes nothing. */
    UserView deactivate(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID userId);

    /** Sets a new provisional credential and {@code must_rotate}; ends the user's sessions. */
    UserView resetCredential(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID userId,
            char[] resetCredential);
}
