package com.solace.sm.cp.hierarchy;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.hierarchy.HierarchyDirectory;
import com.solace.sm.cp.api.hierarchy.ScopeNode;
import com.solace.sm.cp.api.hierarchy.VisibleOrganization;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.authz.AuthorizationGrant;
import com.solace.sm.cp.authz.AuthorizationGrantRepository;
import com.solace.sm.cp.authz.ScopeVisibility;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Implements console-integration.md §5.2 rows 8-10 and {@code D11-AMENDMENT-PROPOSAL.md} §3.4
 * directly on top of {@link ScopeVisibility#isVisible} — the SAME either-direction prefix test
 * {@code ScopeEvaluatingAuthorizationService} uses for enforcement, just never narrowed to one
 * permission: a node is included whenever it is prefix-comparable with some held grant, in either
 * direction (an ancestor of a grant, or a descendant of one).
 *
 * <p>⛔ <b>The organization is {@code session.organizationId()}</b> ({@code I5}) — never a
 * parameter taken as an authority claim. §3.1 step 3's cross-organization refusal happens here
 * BEFORE any grant is read: {@link #requireOwnOrganization} short-circuits on identity comparison
 * alone.
 *
 * <p>Existence and visibility collapse to the identical {@link ResourceNotFoundException} (D11
 * §3.4's "step 0" / "step 1", the same 404 either way) — never a name, never a hint about which
 * check failed.
 */
public final class ScopeVisibleHierarchyDirectory implements HierarchyDirectory {

    private final AuthorizationGrantRepository grants;
    private final HierarchyTreeRepository tree;

    public ScopeVisibleHierarchyDirectory(AuthorizationGrantRepository grants, HierarchyTreeRepository tree) {
        this.grants = Objects.requireNonNull(grants, "grants");
        this.tree = Objects.requireNonNull(tree, "tree");
    }

    @Override
    public List<ScopeNode> listOrganizations(Session session) {
        Objects.requireNonNull(session, "session");
        List<AuthorizationGrant> held = grants.grantsFor(session.principal());
        ScopePath organization = ScopePath.organization(session.organizationId());
        if (held.stream().noneMatch(grant -> ScopeVisibility.isVisible(grant.scope(), organization))) {
            return List.of();
        }
        // A session whose organization row is gone sees no organization — the same answer as no grant.
        return tree.organizationOf(session.organizationId()).map(List::of).orElse(List.of());
    }

    @Override
    public List<ScopeNode> listSubOrganizations(Session session, UUID organizationId) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(organizationId, "organizationId");
        requireOwnOrganization(session, organizationId);

        List<AuthorizationGrant> held = grants.grantsFor(session.principal());
        requireVisible(held, ScopePath.organization(organizationId), ResourceKind.ORGANIZATION);

        return tree.subOrganizationsOf(organizationId).stream()
                .filter(unit -> held.stream().anyMatch(
                        grant -> ScopeVisibility.isVisible(grant.scope(), ScopePath.subOrganization(organizationId, unit.id()))))
                .toList();
    }

    /**
     * {@code OR-116}: composed from this class's own two public reads — the SAME rule, never a
     * parallel one. Every organization returned by {@link #listOrganizations} is visible by
     * construction, so {@link #listSubOrganizations} cannot refuse it.
     */
    @Override
    public List<VisibleOrganization> visibleOrganizations(Session session) {
        Objects.requireNonNull(session, "session");
        return listOrganizations(session).stream()
                .map(organization -> new VisibleOrganization(organization,
                        listSubOrganizations(session, organization.id())))
                .toList();
    }

    @Override
    public List<ScopeNode> listEnvironments(Session session, UUID organizationId, UUID subOrganizationId) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(subOrganizationId, "subOrganizationId");
        requireOwnOrganization(session, organizationId);
        // Step 0 (D11 §3.4): resolves existence AND coherence (subOrganizationId really is a child
        // of organizationId) in one query — an id of the wrong level or a mismatched pair both
        // simply fail to match a row here, with no separate validation path to keep in step.
        if (!tree.subOrganizationExists(organizationId, subOrganizationId)) {
            throw new ResourceNotFoundException(ResourceKind.SUB_ORGANIZATION);
        }

        List<AuthorizationGrant> held = grants.grantsFor(session.principal());
        requireVisible(held, ScopePath.subOrganization(organizationId, subOrganizationId), ResourceKind.SUB_ORGANIZATION);

        return tree.environmentsOf(organizationId, subOrganizationId).stream()
                .filter(env -> held.stream().anyMatch(grant -> ScopeVisibility.isVisible(
                        grant.scope(), ScopePath.environment(organizationId, subOrganizationId, env.id()))))
                .toList();
    }

    /**
     * §3.1 step 3 / §3.4 step 0: a path parameter naming any organization other than the caller's
     * own is refused before a single binding is read — indistinguishable from "does not exist".
     */
    private static void requireOwnOrganization(Session session, UUID organizationId) {
        if (!organizationId.equals(session.organizationId())) {
            throw new ResourceNotFoundException(ResourceKind.ORGANIZATION);
        }
    }

    private static void requireVisible(List<AuthorizationGrant> held, ScopePath candidate, ResourceKind kind) {
        if (held.stream().noneMatch(grant -> ScopeVisibility.isVisible(grant.scope(), candidate))) {
            throw new ResourceNotFoundException(kind);
        }
    }
}
