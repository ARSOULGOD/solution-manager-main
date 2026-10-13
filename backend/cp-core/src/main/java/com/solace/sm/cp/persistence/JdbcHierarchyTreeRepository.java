package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.hierarchy.ScopeNode;
import com.solace.sm.cp.hierarchy.HierarchyTreeRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * JDBC reader for the installer-seeded hierarchy tree. Bind-parameter-only ({@code I5}); every
 * query is scoped by the caller-supplied {@code organizationId}, never trusted on its own — the
 * cross-organization refusal happens one layer up, in
 * {@code ScopeVisibleHierarchyDirectory#requireOwnOrganization}, before this class is ever called.
 */
public final class JdbcHierarchyTreeRepository implements HierarchyTreeRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcHierarchyTreeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean subOrganizationExists(UUID organizationId, UUID organizationUnitId) {
        // Same defensive filter as subOrganizationsOf below, and for the same reason: a future
        // nested row (parent_unit_id set) must be treated as depth-2 by BOTH queries or neither —
        // otherwise a unit invisible to listSubOrganizations could still be accepted here as a
        // valid depth-2 parent for listEnvironments.
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM sm_control.organization_unit "
                        + "WHERE organization_id = :organizationId AND organization_unit_id = :organizationUnitId "
                        + "AND parent_unit_id IS NULL",
                Map.of("organizationId", organizationId, "organizationUnitId", organizationUnitId), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public Optional<ScopeNode> organizationOf(UUID organizationId) {
        return jdbc.query(
                "SELECT organization_id, name FROM sm_control.organization WHERE organization_id = :organizationId",
                Map.of("organizationId", organizationId),
                (rs, rowNum) -> new ScopeNode(rs.getObject("organization_id", UUID.class), rs.getString("name")))
                .stream().findFirst();
    }

    @Override
    public List<ScopeNode> subOrganizationsOf(UUID organizationId) {
        // Ordered by display name, byte-wise (COLLATE "C": locale-independent, so the order is the same on every
        // database), with the id as tie-break. Never by id alone: the OR-118 walkthrough captures items[0], and an id
        // order is arbitrary to a human.
        // parent_unit_id is nullable and never populated in v1 (D11-AMENDMENT-PROPOSAL.md §6.4) —
        // filtered defensively so a future nested row cannot silently surface at the wrong level.
        return jdbc.query(
                "SELECT organization_unit_id, name FROM sm_control.organization_unit "
                        + "WHERE organization_id = :organizationId AND parent_unit_id IS NULL "
                        + "ORDER BY name COLLATE \"C\", organization_unit_id",
                Map.of("organizationId", organizationId),
                (rs, rowNum) -> new ScopeNode(rs.getObject("organization_unit_id", UUID.class), rs.getString("name")));
    }

    @Override
    public List<ScopeNode> environmentsOf(UUID organizationId, UUID organizationUnitId) {
        // Same order as subOrganizationsOf: name (C collation), then id.
        return jdbc.query(
                "SELECT environment_id, name FROM sm_control.environment "
                        + "WHERE organization_id = :organizationId AND organization_unit_id = :organizationUnitId "
                        + "ORDER BY name COLLATE \"C\", environment_id",
                Map.of("organizationId", organizationId, "organizationUnitId", organizationUnitId),
                (rs, rowNum) -> new ScopeNode(rs.getObject("environment_id", UUID.class), rs.getString("name")));
    }
}
