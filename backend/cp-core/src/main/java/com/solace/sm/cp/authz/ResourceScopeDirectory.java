package com.solace.sm.cp.authz;

import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import java.util.Optional;

/** Authoritative directory from resource identity to its authorization scope path. */
public interface ResourceScopeDirectory {

    Optional<ScopePath> findScopePath(ResourceRef resource);
}
