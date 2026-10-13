package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.config.RollbackTargetRows;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Rollback eligibility over {@code sm_data.apply_request} (plan 010 T8.1, OR-42), with the runtime credential. In this
 * package because JDBC is confined here; the rule is {@code dp.config.RollbackTargets}'s.
 *
 * <p>Eligible = an {@code apply} request for the placement that reached {@code STAGED}, {@code APPLIED} or
 * {@code SUPERSEDED}, or that an operator resolved {@code INDETERMINATE_RESOLVED} with outcome {@code applied}
 * ({@code OR-108} (7): it was confirmed on the host; this extends {@code OR-42}'s list). The terminal-guard trigger
 * (V006) allows {@code SUPERSEDED} only as the marking of an elder {@code STAGED}, so a superseded request WAS staged.
 * {@code INDETERMINATE_RESOLVED} with outcome {@code not_applied} stays out: nothing was written.
 *
 * <p>{@code I5}: every statement names {@code tenant_id} and binds every value, including the state list.
 */
public final class JdbcRollbackTargetRepository implements RollbackTargetRows {

    private static final List<String> WRITTEN_TO_THE_HOST = List.of("STAGED", "APPLIED", "SUPERSEDED");

    /** The one clause both reads share, so the two cannot drift: {@code OR-42}'s three states plus {@code OR-108} (7). */
    private static final String ELIGIBLE = "(r.state IN (:states) OR (r.state = 'INDETERMINATE_RESOLVED'"
            + " AND r.resolution_outcome = 'applied'))";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRollbackTargetRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OptionalLong applyGeneration(TenantId tenant, InstanceId instance, PlacementId placement, ApplyId apply) {
        return jdbc.query(
                "SELECT r.generation FROM sm_data.apply_request r WHERE r.tenant_id = :t AND r.instance_id = :i"
                        + " AND r.placement_id = :p AND r.apply_id = :a AND r.operation = 'apply'",
                key(tenant, instance).addValue("p", placement.value()).addValue("a", apply.value()), (rs, n) -> rs.getLong("generation")).stream()
                .mapToLong(Long::longValue).findFirst();
    }

    @Override
    public OptionalLong highestEligibleBelow(TenantId tenant, InstanceId instance, PlacementId placement,
            long belowExclusive) {
        Long highest = jdbc.queryForObject(
                "SELECT max(r.generation) FROM sm_data.apply_request r WHERE r.tenant_id = :t AND r.instance_id = :i"
                        + " AND r.placement_id = :p AND r.operation = 'apply' AND " + ELIGIBLE
                        + " AND r.generation < :below",
                key(tenant, instance).addValue("p", placement.value()).addValue("states", WRITTEN_TO_THE_HOST)
                        .addValue("below", belowExclusive),
                Long.class);
        return highest == null ? OptionalLong.empty() : OptionalLong.of(highest);
    }

    @Override
    public boolean isEligible(TenantId tenant, InstanceId instance, PlacementId placement, long generation) {
        Boolean eligible = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_data.apply_request r WHERE r.tenant_id = :t AND r.instance_id = :i"
                        + " AND r.placement_id = :p AND r.operation = 'apply' AND " + ELIGIBLE
                        + " AND r.generation = :g)",
                key(tenant, instance).addValue("p", placement.value()).addValue("states", WRITTEN_TO_THE_HOST)
                        .addValue("g", generation),
                Boolean.class);
        return Boolean.TRUE.equals(eligible);
    }

    private static MapSqlParameterSource key(TenantId tenant, InstanceId instance) {
        return new MapSqlParameterSource().addValue("t", tenant.value()).addValue("i", instance.value());
    }
}
