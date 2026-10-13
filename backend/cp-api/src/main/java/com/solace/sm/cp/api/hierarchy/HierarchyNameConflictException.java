package com.solace.sm.cp.api.hierarchy;

import com.solace.sm.cp.api.authz.ResourceKind;
import java.util.Objects;

/**
 * A sibling already has this display name ({@code OR-123}): two sub-organizations of one organization, two
 * environments of one sub-organization, or two organizations of the install may not share a name, compared
 * <b>case-insensitively</b>. Rendered {@code 409 CONFLICT}.
 *
 * <p>The comparison is the database's, never Java's: it is PostgreSQL's {@code lower()} under the database's locale,
 * enforced by the unique indexes {@code organization_name_ci_uq}, {@code organization_unit_name_ci_uq} and
 * {@code environment_name_ci_uq}. A service that pre-checked in Java with its own case rule would disagree with them
 * on some inputs, so none does: it writes, and this is what a {@code 23505} on one of those three indexes becomes.
 *
 * <p>Carries no message derived from the request (the name a caller sent is not echoed), and no cause: PostgreSQL's
 * unique-violation text reproduces the key.
 */
public final class HierarchyNameConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ResourceKind kind;

    public HierarchyNameConflictException(ResourceKind kind) {
        super("a sibling " + describe(Objects.requireNonNull(kind, "kind")) + " already has this name");
        this.kind = kind;
    }

    /** Which kind of node's name clashed: an organization, a sub-organization or an environment. */
    public ResourceKind kind() {
        return kind;
    }

    private static String describe(ResourceKind kind) {
        return switch (kind) {
            case ORGANIZATION -> "organization";
            case SUB_ORGANIZATION -> "sub-organization";
            case ENVIRONMENT -> "environment";
            default -> throw new IllegalArgumentException("only a hierarchy node has a sibling name: " + kind);
        };
    }
}
