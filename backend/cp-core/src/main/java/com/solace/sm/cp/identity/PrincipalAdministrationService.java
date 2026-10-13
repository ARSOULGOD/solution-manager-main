package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.identity.PrincipalAdministration;
import com.solace.sm.cp.api.identity.PrincipalConflictException;
import com.solace.sm.cp.api.identity.PrincipalSource;
import com.solace.sm.cp.api.identity.UserView;
import com.solace.sm.cp.authz.AuthorizationGrant;
import com.solace.sm.cp.authz.AuthorizationGrantRepository;
import java.text.Normalizer;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The operator user's lifecycle (plan 013 T3.1). See {@link PrincipalAdministration} for the contract.
 *
 * <p><b>Order, every method:</b> validate the shape of the arguments (reveals nothing, and a refused request costs no
 * authorization read), {@code require(verb)} at the organization or the principal (before any read of the principal or
 * any hash, so a caller who may not administer users learns nothing and cannot burn Argon2 capacity), then the work.
 * A <b>reset</b> pre-checks every refusal from an unlocked read <i>before</i> hashing, so a refused reset pays no
 * Argon2, and then repeats the same checks under the row lock inside the transaction, where they are decisive.
 *
 * <p><b>One transaction per write</b> ({@code smControlTransactions}): the audit context ({@code OR-25}, the operation
 * name of the change), the row lock, the checks, the write and the revocation of the principal's sessions commit
 * together or not at all. A refusal inside the transaction throws and rolls the audit context back with it.
 *
 * <p>{@code I6}: no exception message carries a credential, a hash or a username; no exception chains a driver cause. A
 * credential is hashed once and the plaintext is not retained. {@code I5}: the organization is an argument of every
 * method and is bound in every statement.
 */
public final class PrincipalAdministrationService implements PrincipalAdministration {

    private final PrincipalAdministrationRepository principals;
    private final SessionRepository sessions;
    private final CredentialVerifier hasher;
    private final AuthorizationService authorization;
    private final AuthorizationGrantRepository grants;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public PrincipalAdministrationService(PrincipalAdministrationRepository principals, SessionRepository sessions,
            CredentialVerifier hasher, AuthorizationService authorization, AuthorizationGrantRepository grants,
            AuditContext auditContext, TransactionOperations transactions, Clock clock, Supplier<UUID> ids) {
        this.principals = Objects.requireNonNull(principals, "principals");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public List<UserView> list(PrincipalRef actor, UUID organizationId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(organizationId, "organizationId");
        authorization.require(actor, Permission.USER_VIEW, organization(organizationId));
        return principals.list(organizationId);
    }

    @Override
    public UserView create(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String username,
            char[] initialCredential) {
        requireArguments(actor, origin, organizationId);
        validUsername(username);
        requireCredentialShape(initialCredential);
        authorization.require(actor, Permission.USER_CREATE, organization(organizationId));

        String credentialHash = hasher.hash(initialCredential);
        UUID id = ids.get();
        try {
            return transactions.execute(status -> {
                audit(actor, origin, AuditOperations.PRINCIPAL_CREATED);
                return principals.insertLocal(id, organizationId, username, credentialHash);
            });
        } catch (PrincipalAdministrationRepository.UsernameTakenException taken) {
            // Deliberately not chained, and the username is not echoed (I6).
            throw new PrincipalConflictException(PrincipalConflictException.Reason.USERNAME_TAKEN);
        }
    }

    @Override
    public UserView deactivate(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID userId) {
        requireArguments(actor, origin, organizationId);
        Objects.requireNonNull(userId, "userId");
        authorization.require(actor, Permission.USER_DEACTIVATE, principal(userId, organizationId));

        return transactions.execute(status -> {
            audit(actor, origin, AuditOperations.PRINCIPAL_DEACTIVATED);
            UserView current = principals.lock(organizationId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.PRINCIPAL));
            refuseCallerAndAdministrators(actor, current);
            if (current.status() == UserView.Status.DEACTIVATED) {
                return current;
            }
            if (!principals.deactivate(organizationId, userId)) {
                // Not reachable under the row lock; if it ever is, nothing changed and nothing is revoked.
                return current;
            }
            sessions.revokeAllFor(new PrincipalRef(userId), clock.instant());
            return new UserView(current.id(), current.username(), current.source(), UserView.Status.DEACTIVATED,
                    current.mustRotate(), current.createdAt());
        });
    }

    @Override
    public UserView resetCredential(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID userId,
            char[] resetCredential) {
        requireArguments(actor, origin, organizationId);
        Objects.requireNonNull(userId, "userId");
        requireCredentialShape(resetCredential);
        authorization.require(actor, Permission.USER_EDIT, principal(userId, organizationId));

        // Every refusal first, from an unlocked read: a refused reset must not pay Argon2.
        UserView seen = principals.find(organizationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.PRINCIPAL));
        refuseCallerAndAdministrators(actor, seen);
        requireCallerHoldsTargetVerbs(actor, seen);
        requireResettable(seen);

        String credentialHash = hasher.hash(resetCredential);
        return transactions.execute(status -> {
            audit(actor, origin, AuditOperations.PRINCIPAL_CREDENTIAL_RESET);
            // The decisive checks, under the row lock: a binding or a deactivation that landed after the pre-check
            // is seen here.
            UserView current = principals.lock(organizationId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.PRINCIPAL));
            refuseCallerAndAdministrators(actor, current);
            requireCallerHoldsTargetVerbs(actor, current);
            requireResettable(current);
            if (!principals.resetCredential(organizationId, userId, credentialHash)) {
                throw new PrincipalConflictException(PrincipalConflictException.Reason.DEACTIVATED);
            }
            sessions.revokeAllFor(new PrincipalRef(userId), clock.instant());
            return new UserView(current.id(), current.username(), current.source(), current.status(), true,
                    current.createdAt());
        });
    }

    /** {@code OR-12}/R18: neither the caller itself nor any holder of an Administrator binding is administered here. */
    private void refuseCallerAndAdministrators(PrincipalRef actor, UserView target) {
        if (target.id().equals(actor.id())) {
            throw new PrincipalConflictException(PrincipalConflictException.Reason.CALLER_ITSELF);
        }
        if (principals.holdsRole(target.id(), BuiltInRoles.ADMINISTRATOR)) {
            throw new PrincipalConflictException(PrincipalConflictException.Reason.ADMINISTRATOR_PROTECTED);
        }
    }

    /**
     * W3D-4: resetting a credential is taking the account over (the caller sets the provisional credential), so the
     * caller must hold every verb the target holds, at the scope the target holds it. Otherwise a principal with
     * {@code user.edit} could become anyone below it. The refusal is a plain {@code user.edit} {@code 403}: it names
     * nothing of the target's standing. In v1 the only principals the caller may reset hold Viewer, Operator or
     * nothing, all of which the Administrator covers, so this refuses no one today; it makes that a property of the
     * code and not of today's role table (D-REG {@code OQ-7 (c)}).
     *
     * <p><b>Deactivation is deliberately not guarded this way:</b> it grants the caller no authority it lacks, and the
     * Administrator and self protections already stop the lock-out cases. The owner may extend the rule to it.
     */
    private void requireCallerHoldsTargetVerbs(PrincipalRef actor, UserView target) {
        List<AuthorizationGrant> targetGrants = grants.grantsFor(new PrincipalRef(target.id()));
        if (targetGrants.isEmpty()) {
            return;
        }
        if (VerbCoverage.firstTargetVerbMissing(grants.grantsFor(actor), targetGrants).isPresent()) {
            throw new AccessDeniedException(Permission.USER_EDIT);
        }
    }

    private static void requireResettable(UserView target) {
        if (target.source() != PrincipalSource.LOCAL) {
            throw new PrincipalConflictException(PrincipalConflictException.Reason.NOT_LOCAL);
        }
        if (target.status() != UserView.Status.ACTIVE) {
            throw new PrincipalConflictException(PrincipalConflictException.Reason.DEACTIVATED);
        }
    }

    private void audit(PrincipalRef actor, RequestOrigin origin, String operation) {
        auditContext.set(actor.id(), operation, origin.correlationId(), origin.clientAddress());
    }

    private static void requireArguments(PrincipalRef actor, RequestOrigin origin, UUID organizationId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(organizationId, "organizationId");
    }

    private static ResourceRef organization(UUID organizationId) {
        return ResourceRef.of(ResourceKind.ORGANIZATION, organizationId, new TenantId(organizationId));
    }

    private static ResourceRef principal(UUID userId, UUID organizationId) {
        return ResourceRef.of(ResourceKind.PRINCIPAL, userId, new TenantId(organizationId));
    }

    /**
     * 1 to 64 code points with no whitespace and no control character, kept exactly as given (never stripped: the
     * username is the login key; login matches it without regard to case, inside the organization it names). Shape only,
     * never uniqueness: that is the database's ({@code principal_org_username_ci_uq}, per organization).
     */
    static void validUsername(String username) {
        Objects.requireNonNull(username, "username");
        int length = username.codePointCount(0, username.length());
        boolean clean = username.codePoints().noneMatch(PrincipalAdministrationService::invisibleOrUnsafe)
                && Normalizer.isNormalized(username, Normalizer.Form.NFC);
        if (length < 1 || length > MAX_USERNAME_LENGTH || !clean) {
            throw new IllegalArgumentException("a username is 1 to " + MAX_USERNAME_LENGTH
                    + " characters in Unicode normalization form C with no whitespace, control, format, private-use,"
                    + " unassigned or unpaired surrogate character");
        }
    }

    /**
     * W3D-3: a code point that can make two different usernames look identical or hide one. Besides whitespace and
     * controls this refuses the FORMAT class (zero-width space and joiner, the bidi overrides, the word joiner, the
     * byte-order mark, the soft hyphen), unpaired SURROGATEs (a paired one is one supplementary code point, so it never
     * appears here), PRIVATE_USE and UNASSIGNED. Combined with the NFC requirement, a precomposed and a decomposed form
     * of the same text cannot both be registered. Letters of other scripts are allowed; confusable letters (a Cyrillic
     * "a") are not folded, because the product has no confusables table. Case IS folded, by the database: a username is
     * unique per organization on {@code lower(username)} (V019's {@code principal_org_username_ci_uq}) and login matches
     * the same way, so {@code Alice} and {@code alice} are one name (decided by OR-131 Q4).
     */
    private static boolean invisibleOrUnsafe(int codePoint) {
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || Character.isISOControl(codePoint)) {
            return true;
        }
        return switch (Character.getType(codePoint)) {
            case Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true;
            default -> false;
        };
    }

    static void requireCredentialShape(char[] credential) {
        Objects.requireNonNull(credential, "credential");
        if (credential.length < MIN_CREDENTIAL_LENGTH || credential.length > MAX_CREDENTIAL_LENGTH) {
            throw new IllegalArgumentException("a credential is " + MIN_CREDENTIAL_LENGTH + " to "
                    + MAX_CREDENTIAL_LENGTH + " characters");
        }
    }
}
