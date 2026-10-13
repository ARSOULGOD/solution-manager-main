package com.solace.sm.cp.hierarchy;

import com.solace.sm.cp.api.hierarchy.ScopeNode;
import java.util.Optional;
import java.util.UUID;

/**
 * The scope tree's writes (plan 013 T2.2): {@code sm_control.organization_unit} and {@code sm_control.environment}
 * rows are created, and the {@code name} of the three node kinds is changed. Its JDBC adapter belongs in
 * {@code cp.persistence}. {@link HierarchyTreeRepository} stays the read-only tree walker {@code OR-120} left it; the
 * few reads here exist so a write and the read that explains it share one transaction's view.
 *
 * <p>Every method names its organization and binds it ({@code I5}). Every write must run in the caller's transaction,
 * after the audit context is set ({@code OR-25}).
 *
 * <p><b>Name uniqueness is the database's, ignoring case ({@code OR-123}).</b> A write the unique index refuses
 * ({@code organization_name_ci_uq}, {@code organization_unit_name_ci_uq}, {@code environment_name_ci_uq}) is a
 * {@link NameTakenException}; no method here pre-checks a name, and none compares two names in Java.
 */
public interface HierarchyWriteRepository {

    /**
     * A {@code 23505} on one of the three sibling-name indexes. It carries <b>no cause and no name</b>: PostgreSQL's
     * unique-violation text reproduces the key, and a logger prints a whole cause chain ({@code I6}'s lesson, applied
     * to a value that is not a secret but is request-derived).
     */
    final class NameTakenException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String index;

        public NameTakenException(String index) {
            super("a sibling already holds this name (index " + index + ")");
            this.index = index;
        }

        /** The index PostgreSQL named: one of the three {@code *_name_ci_uq}. */
        public String index() {
            return index;
        }
    }

    /** Inserts a top-level sub-organization (the runtime role holds no INSERT on {@code parent_unit_id}). */
    void insertSubOrganization(UUID organizationId, UUID subOrganizationId, String name);

    void insertEnvironment(UUID organizationId, UUID subOrganizationId, UUID environmentId, String name);

    /**
     * Sets the organization's name when it differs (byte-wise) from {@code name}.
     *
     * @return {@code true} when a row changed; {@code false} when there is no such organization or its name is
     *         already exactly {@code name}
     */
    boolean renameOrganization(UUID organizationId, String name);

    boolean renameSubOrganization(UUID organizationId, UUID subOrganizationId, String name);

    boolean renameEnvironment(UUID organizationId, UUID subOrganizationId, UUID environmentId, String name);

    Optional<ScopeNode> findOrganization(UUID organizationId);

    /** A top-level sub-organization of the organization, by id. */
    Optional<ScopeNode> findSubOrganization(UUID organizationId, UUID subOrganizationId);

    Optional<ScopeNode> findEnvironment(UUID organizationId, UUID subOrganizationId, UUID environmentId);

    /** The organization's top-level sub-organization whose name is, byte for byte, {@code name}. */
    Optional<ScopeNode> findSubOrganizationByName(UUID organizationId, String name);

    /** The sub-organization's environment whose name is, byte for byte, {@code name}. */
    Optional<ScopeNode> findEnvironmentByName(UUID organizationId, UUID subOrganizationId, String name);
}
