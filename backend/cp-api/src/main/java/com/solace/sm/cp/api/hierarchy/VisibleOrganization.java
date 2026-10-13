package com.solace.sm.cp.api.hierarchy;

import java.util.List;
import java.util.Objects;

/**
 * One visible organization together with the sub-organizations visible beneath it — the shape
 * {@code Identity.organizations} carries (ruling {@code OR-116}).
 *
 * <p>Never constructed from a second visibility rule: {@link HierarchyDirectory#visibleOrganizations}
 * builds it from {@link HierarchyDirectory#listOrganizations} and {@link
 * HierarchyDirectory#listSubOrganizations}, so it can never hold more than those two lists return.
 */
public record VisibleOrganization(ScopeNode organization, List<ScopeNode> subOrganizations) {

    public VisibleOrganization {
        Objects.requireNonNull(organization, "organization");
        subOrganizations = List.copyOf(Objects.requireNonNull(subOrganizations, "subOrganizations"));
    }
}
