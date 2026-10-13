package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.authz.PrincipalRef;
import java.util.List;

/** Reads the built-in role names a principal is bound to, for display only — never for authorization. */
public interface RoleBindingRepository {

    List<String> boundRoleNames(PrincipalRef principal);

    /**
     * True iff {@code principal} is bound to the role named {@code roleName} AT THE INSTALL SCOPE
     * specifically (frontend/backend-integration-auth-hierarchy.md D2) — never true for a binding at
     * any narrower scope (organization, organization unit, environment, solution or instance), even
     * for the same role name. This is the query {@code superAdmin} must be derived from; a role
     * binding at any OTHER scope is display-only and must never make {@code superAdmin} true.
     */
    boolean boundToRoleAtInstallScope(PrincipalRef principal, String roleName);
}
