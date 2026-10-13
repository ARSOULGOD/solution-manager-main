package com.solace.sm.cp.persistence;

import com.solace.sm.cp.catalog.EntitlementRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link EntitlementRepository} over {@code sm_control.entitlement}. The runtime role holds {@code SELECT},
 * {@code INSERT} and a column-scoped {@code UPDATE} of exactly the three columns the upsert changes (V013), and no
 * {@code DELETE}: v1 has no withdrawal.
 */
public final class JdbcEntitlementRepository implements EntitlementRepository {

    private static final RowMapper<Stored> STORED = (rs, row) -> new Stored(rs.getString("solution"),
            rs.getInt("max_instances"), rs.getTimestamp("set_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcEntitlementRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean exists(UUID organizationId, String solution) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM sm_control.entitlement
                                WHERE organization_id = :organization AND solution = :solution)
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("solution", solution), Boolean.class));
    }

    @Override
    public List<Stored> list(UUID organizationId) {
        return jdbc.query("SELECT solution, max_instances, set_at FROM sm_control.entitlement"
                        + " WHERE organization_id = :organization ORDER BY solution COLLATE \"C\"",
                new MapSqlParameterSource().addValue("organization", organizationId), STORED);
    }

    @Override
    public Optional<Stored> find(UUID organizationId, String solution) {
        return jdbc.query("SELECT solution, max_instances, set_at FROM sm_control.entitlement"
                        + " WHERE organization_id = :organization AND solution = :solution",
                new MapSqlParameterSource().addValue("organization", organizationId)
                        .addValue("solution", solution), STORED).stream().findFirst();
    }

    @Override
    public void upsert(UUID organizationId, String solution, int maxInstances, UUID setBy, Instant setAt) {
        jdbc.update("""
                INSERT INTO sm_control.entitlement (organization_id, solution, max_instances, set_by, set_at)
                VALUES (:organization, :solution, :max, :setBy, :setAt)
                ON CONFLICT (organization_id, solution) DO UPDATE
                   SET max_instances = EXCLUDED.max_instances, set_by = EXCLUDED.set_by, set_at = EXCLUDED.set_at
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("solution", solution).addValue("max", maxInstances).addValue("setBy", setBy)
                .addValue("setAt", Timestamp.from(setAt)));
    }

    @Override
    public boolean insertIfAbsent(UUID organizationId, String solution, int maxInstances, UUID setBy, Instant setAt) {
        return jdbc.update("""
                INSERT INTO sm_control.entitlement (organization_id, solution, max_instances, set_by, set_at)
                VALUES (:organization, :solution, :max, :setBy, :setAt)
                ON CONFLICT (organization_id, solution) DO NOTHING
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("solution", solution).addValue("max", maxInstances).addValue("setBy", setBy)
                .addValue("setAt", Timestamp.from(setAt))) == 1;
    }
}
