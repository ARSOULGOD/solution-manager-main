package com.solace.sm.cp.persistence;

import com.solace.sm.cp.governance.InstanceRetirementRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link InstanceRetirementRepository} over {@code sm_control}, served with the {@code sm_cp_runtime} credential,
 * which holds {@code UPDATE} on exactly {@code instance.retired_at} and {@code instance.retired_by} (V017). Every
 * statement binds its parameters ({@code I5}) and names its organization in its own {@code WHERE}.
 */
public final class JdbcInstanceRetirementRepository implements InstanceRetirementRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcInstanceRetirementRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean exists(UUID organizationId, UUID instanceId) {
        return !jdbc.queryForList("""
                SELECT 1 FROM sm_control.instance
                 WHERE organization_id = :organization AND instance_id = :instance
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("instance", instanceId), Integer.class).isEmpty();
    }

    @Override
    public boolean retire(UUID organizationId, UUID instanceId, UUID retiredBy, Instant at) {
        // retired_at IS NULL: a second retire matches no row, so it writes nothing and the original actor stays.
        return jdbc.update("""
                UPDATE sm_control.instance SET retired_at = :at, retired_by = :by
                 WHERE organization_id = :organization AND instance_id = :instance AND retired_at IS NULL
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("instance", instanceId).addValue("by", retiredBy)
                .addValue("at", Timestamp.from(at))) == 1;
    }

    @Override
    public Set<UUID> retired(UUID organizationId, Collection<UUID> instanceIds) {
        if (instanceIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.query("""
                SELECT instance_id FROM sm_control.instance
                 WHERE organization_id = :organization AND instance_id IN (:ids) AND retired_at IS NOT NULL
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("ids", instanceIds), (rs, row) -> rs.getObject("instance_id", UUID.class)));
    }
}
