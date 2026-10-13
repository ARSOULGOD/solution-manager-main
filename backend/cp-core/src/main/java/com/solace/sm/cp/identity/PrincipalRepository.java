package com.solace.sm.cp.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Principal persistence port; its JDBC adapter belongs in {@code cp.persistence}. */
public interface PrincipalRepository {

    /** Names no organization: login looks a principal up under it when the organization did not resolve (W5D-1). */
    UUID NO_ORGANIZATION = new UUID(0L, 0L);

    UUID createLocal(UUID organizationId, String username, String credentialHash, boolean mustRotate);

    UUID createIdp(UUID organizationId, String username);

    Optional<Principal> find(UUID id);

    /**
     * The organization a login names: {@code idOrName} is a canonical UUID ({@code 8-4-4-4-12} hexadecimal digits,
     * either case) that is an organization's id, or else an organization's name compared without regard to case. An id
     * is tried first, so a name that happens to look like another organization's id cannot take that organization's
     * logins. Empty when nothing matches; a blank or oversize input is simply no match (and still costs the one
     * statement). {@code null} is an OMITTED organization (OR-131 Q3): the install's only organization, or empty when
     * the install holds none or several.
     */
    Optional<UUID> resolveOrganization(String idOrName);

    /**
     * The LOCAL principal of that organization whose username equals {@code username} without regard to case (a
     * username is unique per organization on exactly that comparison: {@code principal_org_username_ci_uq}).
     */
    Optional<Principal> findLocalByUsername(UUID organizationId, String username);

    boolean rotateCredential(UUID id, String expectedCredentialHash, String replacementCredentialHash);

    void recordCredentialFailure(UUID id);

    void deactivate(UUID id);

    enum Source {
        LOCAL,
        IDP
    }

    enum Status {
        ACTIVE,
        DEACTIVATED
    }

    record Principal(UUID id, UUID organizationId, Source source, String username, String credentialHash,
                     Status status, boolean mustRotate, Instant createdAt, Instant deactivatedAt) {
        @Override
        public String toString() {
            return "Principal[id=" + id + ", source=" + source + ", status=" + status + "]";
        }
    }
}
