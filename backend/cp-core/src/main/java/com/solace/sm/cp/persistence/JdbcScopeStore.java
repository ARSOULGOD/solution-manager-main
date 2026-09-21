package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.authz.ScopeId;
import com.solace.sm.cp.authz.ScopeNotCanonicalizableException;
import com.solace.sm.cp.authz.ScopeStore;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** PostgreSQL-backed canonical scope lookup and insertion. */
public final class JdbcScopeStore implements ScopeStore {

    private static final String SELECT = """
            SELECT scope_id FROM sm_control.scope
             WHERE kind = :kind
               AND organization_id IS NOT DISTINCT FROM :organizationId
               AND organization_unit_id IS NOT DISTINCT FROM :organizationUnitId
               AND environment_id IS NOT DISTINCT FROM :environmentId
               AND solution_id IS NOT DISTINCT FROM :solutionId
               AND instance_id IS NOT DISTINCT FROM :instanceId
            """;
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO sm_control.scope (
                scope_id, kind, organization_id, organization_unit_id, environment_id, solution_id, instance_id)
            VALUES (
                :scopeId, :kind, :organizationId, :organizationUnitId, :environmentId, :solutionId, :instanceId)
            ON CONFLICT DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Runnable afterMissingLookup;

    public JdbcScopeStore(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, () -> {
        });
    }

    /**
     * Test-only seam: synchronizes callers after the initial miss to force the duplicate-insert
     * race that ordinary scheduling may not reproduce.
     */
    JdbcScopeStore(NamedParameterJdbcTemplate jdbc, Runnable afterMissingLookup) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.afterMissingLookup = Objects.requireNonNull(afterMissingLookup, "after missing lookup");
    }

    @Override
    public ScopeId canonicalize(ScopePath path) {
        Optional<ScopeId> existing = find(path);
        if (existing.isPresent()) {
            return existing.get();
        }
        afterMissingLookup.run();
        ScopeId created = new ScopeId(UUID.randomUUID());
        jdbc.update(INSERT_IF_ABSENT, parameters(path, created.value()));
        return find(path).orElseThrow(() -> new IllegalStateException(
                "scope insert completed without a canonical row"));
    }

    private Optional<ScopeId> find(ScopePath path) {
        return jdbc.query(SELECT, parameters(path, null),
                resultSet -> resultSet.next()
                        ? Optional.of(new ScopeId(resultSet.getObject("scope_id", UUID.class)))
                        : Optional.empty());
    }

    private static MapSqlParameterSource parameters(ScopePath path, UUID scopeId) {
        return new MapSqlParameterSource()
                .addValue("scopeId", scopeId)
                .addValue("kind", kind(path))
                .addValue("organizationId", path.organization())
                .addValue("organizationUnitId", path.subOrganization())
                .addValue("environmentId", path.environment())
                .addValue("solutionId", path.solution())
                .addValue("instanceId", path.instance());
    }

    private static String kind(ScopePath path) {
        return switch (path.depth()) {
            case 1 -> "organization";
            case 2 -> "organization_unit";
            case 3 -> "environment";
            case 4 -> "solution";
            case 5 -> "instance";
            default -> throw new ScopeNotCanonicalizableException("install scope cannot be persisted at runtime");
        };
    }
}
