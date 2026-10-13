package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.identity.UserView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Principal writes for the user-administration surface (plan 013 T3.1). Its JDBC adapter belongs in
 * {@code cp.persistence}; {@link PrincipalRepository} stays the login and rotation port it was.
 *
 * <p>Every method names its organization and binds it ({@code I5}); a principal of another organization is exactly as
 * absent as a nonexistent one. Every write must run in the caller's transaction, after the audit context is set
 * ({@code OR-25}); the {@code credential_hash} never leaves this port (a {@link UserView} has no field for it).
 */
public interface PrincipalAdministrationRepository {

    /**
     * A {@code 23505} on {@code principal_username_unique}. Carries <b>no cause and no username</b>: the driver's
     * unique-violation text reproduces the key.
     */
    final class UsernameTakenException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UsernameTakenException() {
            super("that username is already taken");
        }
    }

    /** Inserts an active local principal with {@code must_rotate} set and returns it. */
    UserView insertLocal(UUID id, UUID organizationId, String username, String credentialHash);

    /** The organization's principals ordered by username then id. */
    List<UserView> list(UUID organizationId);

    Optional<UserView> find(UUID organizationId, UUID id);

    /** As {@link #find}, taking a row lock that holds until the surrounding transaction ends. */
    Optional<UserView> lock(UUID organizationId, UUID id);

    /** Whether the principal holds {@code roleId} at any scope. */
    boolean holdsRole(UUID principalId, UUID roleId);

    /** @return {@code true} when an <i>active</i> principal became deactivated; {@code false} when nothing changed */
    boolean deactivate(UUID organizationId, UUID id);

    /**
     * Sets the credential hash and {@code must_rotate} of an <i>active local</i> principal, and zeroes its credential
     * failure count.
     *
     * @return {@code true} when a row changed
     */
    boolean resetCredential(UUID organizationId, UUID id, String credentialHash);
}
