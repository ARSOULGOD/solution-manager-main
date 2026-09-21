package com.solace.sm.cp.api.authz;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A position in the <b>five-level</b> grant hierarchy, per ruling {@code OR-16}, which amends
 * {@code D11}:
 *
 * <pre>Organization → Sub-organization → Environment → Solution → Instance</pre>
 *
 * <p>⛔ <b>There is no placement component, and adding one would be wrong.</b> {@code OR-16}
 * makes Placement an <i>operational child identity</i> of Instance — it appears in APIs,
 * topics, events and runtime operations, and never in a role binding. A placement-targeted
 * operation authorizes through its owning instance, plus any separately required Target
 * permission ({@code D10}'s two-sided rule). {@code D11-AMENDMENT-PROPOSAL.md} §3.2 gives the
 * reason: a binding granted at a placement would survive a rebind, and {@code SM-D23} makes a
 * rebind a new {@code placement_id}.
 *
 * <p><b>Prefix semantics.</b> A binding authorizes a resource when the binding's path is a
 * prefix of the resource's path — that is what "downward inheritance" means. The empty path
 * reaches everything, which is what makes the install-scoped bootstrap admin work; an
 * organization-scoped binding does <b>not</b> reach an empty-path resource.
 *
 * <p>⚠️ Levels must be contiguous from the root. {@code (org, null, env)} is not a position in
 * a tree, and the constructor refuses it rather than letting a hole reach further than it
 * should.
 */
public record ScopePath(UUID organization,
                        UUID subOrganization,
                        UUID environment,
                        UUID solution,
                        UUID instance) {

    /** Reaches every resource. The seeded bootstrap admin is bound here. */
    public static final ScopePath INSTALL = new ScopePath(null, null, null, null, null);

    public ScopePath {
        UUID[] levels = {organization, subOrganization, environment, solution, instance};
        boolean ended = false;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] == null) {
                ended = true;
            } else if (ended) {
                throw new IllegalArgumentException(
                        "a scope path must be contiguous from the root; level " + i
                                + " is set while an ancestor is null. A path with a hole is not a "
                                + "position in the tree.");
            }
        }
    }

    public static ScopePath organization(UUID organization) {
        return new ScopePath(Objects.requireNonNull(organization), null, null, null, null);
    }

    public static ScopePath subOrganization(UUID organization, UUID subOrganization) {
        return new ScopePath(Objects.requireNonNull(organization),
                Objects.requireNonNull(subOrganization), null, null, null);
    }

    public static ScopePath environment(UUID organization, UUID subOrganization, UUID environment) {
        return new ScopePath(Objects.requireNonNull(organization),
                Objects.requireNonNull(subOrganization),
                Objects.requireNonNull(environment), null, null);
    }

    public static ScopePath solution(UUID organization, UUID subOrganization, UUID environment,
                                     UUID solution) {
        return new ScopePath(Objects.requireNonNull(organization),
                Objects.requireNonNull(subOrganization),
                Objects.requireNonNull(environment),
                Objects.requireNonNull(solution), null);
    }

    public static ScopePath instance(UUID organization, UUID subOrganization, UUID environment,
                                     UUID solution, UUID instance) {
        return new ScopePath(Objects.requireNonNull(organization),
                Objects.requireNonNull(subOrganization),
                Objects.requireNonNull(environment),
                Objects.requireNonNull(solution),
                Objects.requireNonNull(instance));
    }

    /** The set levels, root first. Empty for {@link #INSTALL}. */
    public List<UUID> levels() {
        List<UUID> out = new ArrayList<>(5);
        for (UUID u : new UUID[]{organization, subOrganization, environment, solution, instance}) {
            if (u == null) {
                break;
            }
            out.add(u);
        }
        return List.copyOf(out);
    }

    /** How deep this path is: 0 for the install scope, 5 for an instance. */
    public int depth() {
        return levels().size();
    }

    /**
     * True when a binding at this path authorizes {@code resource}.
     *
     * <p>⚠️ Not symmetric, and the direction is the whole point: an environment-scoped binding
     * reaches that environment's instances, and an instance-scoped binding reaches neither its
     * environment nor a sibling.
     */
    public boolean authorizes(ScopePath resource) {
        Objects.requireNonNull(resource, "resource");
        List<UUID> mine = levels();
        List<UUID> theirs = resource.levels();
        if (mine.size() > theirs.size()) {
            return false;
        }
        for (int i = 0; i < mine.size(); i++) {
            if (!mine.get(i).equals(theirs.get(i))) {
                return false;
            }
        }
        return true;
    }
}
