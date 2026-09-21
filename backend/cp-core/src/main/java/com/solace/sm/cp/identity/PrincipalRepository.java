package com.solace.sm.cp.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Principal persistence port; its JDBC adapter belongs in {@code cp.persistence}. */
public interface PrincipalRepository {

    UUID createLocal(UUID organizationId, String username, String credentialHash, boolean mustRotate);

    UUID createIdp(UUID organizationId, String username);

    Optional<Principal> find(UUID id);

    Optional<Principal> findLocalByUsername(String username);

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
