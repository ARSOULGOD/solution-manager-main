package com.solace.sm.cp.authz;

import com.solace.sm.cp.api.authz.PrincipalRef;
import java.util.Optional;
import java.util.UUID;

/** Resolves the authenticated principal's organization from authoritative server-side state. */
public interface PrincipalOrganizationResolver {

    Optional<UUID> organizationOf(PrincipalRef principal);
}
