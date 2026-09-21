package com.solace.sm.cp.authz;

import com.solace.sm.cp.api.authz.ScopePath;

/** Persistence boundary for canonical scope rows. Implementations rely on {@code sm_control}'s FKs. */
public interface ScopeStore {

    ScopeId canonicalize(ScopePath path);
}
