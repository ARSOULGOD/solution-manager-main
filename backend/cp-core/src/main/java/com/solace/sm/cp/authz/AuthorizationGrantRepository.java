package com.solace.sm.cp.authz;

import com.solace.sm.cp.api.authz.PrincipalRef;
import java.util.List;

/** Reads role bindings and their role permissions for authorization evaluation. */
public interface AuthorizationGrantRepository {

    List<AuthorizationGrant> grantsFor(PrincipalRef principal);
}
