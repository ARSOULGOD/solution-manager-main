package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code apply_scope} and {@code placement_watermark} — the serialization point of design §3.3 and
 * the two maintained watermarks of §2.2 rule 1.
 *
 * <p>Every statement is tenant-keyed ({@code I5}) and every value is bound, never interpolated.
 */
public final class ApplyScopeRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ApplyScopeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The scope row's single-arbiter upsert.
     *
     * <p>{@code ON CONFLICT DO NOTHING} is legitimate HERE and nowhere else in this design: the table
     * has exactly one unique key (its primary key), so there is no second constraint whose violation
     * could be silently absorbed. §2.2 rule 2's intent insert is the contrasting case — two UNIQUEs,
     * different meanings, so it is a plain INSERT and the 23505 is classified by name.
     */
    public void ensureScopeRow(TenantId t, InstanceId i) {
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)"
                        + " ON CONFLICT (tenant_id, instance_id) DO NOTHING",
                key(t, i)));
    }

    /**
     * Takes the row lock the whole admission transaction is serialized by, and returns the instance
     * watermark it was holding.
     *
     * <p>{@code SELECT … FOR UPDATE} rather than {@code pg_advisory_xact_lock}: design §3.1 rejects
     * advisory locks because their keys are hashed into one database-wide namespace (int4
     * {@code hashtext} collides — measured) while this row is exact, tenant-keyed, and visible in
     * {@code pg_locks} under its own identity.
     *
     * @throws IllegalStateException if the row is absent — {@link #ensureScopeRow} must run first,
     *                               and {@code request_has_scope} would refuse the insert anyway
     */
    public long lockScope(TenantId t, InstanceId i) {
        try {
            Long v = jdbc.queryForObject(
                    "SELECT instance_watermark FROM sm_data.apply_scope"
                            + " WHERE tenant_id = :t AND instance_id = :i FOR UPDATE",
                    key(t, i), Long.class);
            return v == null ? 0L : v;
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException(
                    "no apply_scope row to lock for tenant " + t.value() + " instance " + i.value()
                            + " — ensureScopeRow must precede lockScope in the same transaction", e);
        }
    }

    public long instanceWatermark(TenantId t, InstanceId i) {
        Long v = jdbc.queryForObject(
                "SELECT coalesce(max(instance_watermark), 0) FROM sm_data.apply_scope"
                        + " WHERE tenant_id = :t AND instance_id = :i",
                key(t, i), Long.class);
        return v == null ? 0L : v;
    }

    /**
     * One placement's watermark, or 0.
     *
     * <p>NULL-explicit on purpose (design §2.2 rule 1): a placement with no row contributes 0, never
     * SQL NULL into a comparison — {@code G <= NULL} is NULL, and a NULL predicate admits.
     */
    public long placementWatermark(TenantId t, InstanceId i, PlacementId p) {
        Long v = jdbc.queryForObject(
                "SELECT coalesce(max(generation), 0) FROM sm_data.placement_watermark"
                        + " WHERE tenant_id = :t AND instance_id = :i AND placement_id = :p",
                key(t, i).addValue("p", p.value()), Long.class);
        return v == null ? 0L : v;
    }

    /** The cross-scope maximum an INSTANCE-WIDE apply is fenced by. */
    public long maxPlacementWatermark(TenantId t, InstanceId i) {
        Long v = jdbc.queryForObject(
                "SELECT coalesce(max(generation), 0) FROM sm_data.placement_watermark"
                        + " WHERE tenant_id = :t AND instance_id = :i",
                key(t, i), Long.class);
        return v == null ? 0L : v;
    }

    /**
     * Raises the instance watermark, never lowers it.
     *
     * <p>{@code GREATEST} in the statement and the {@code apply_scope_monotonic} trigger behind it are
     * belt and braces on purpose: the statement makes a late-landing lower generation a no-op instead
     * of an error, and the trigger makes an engine bug that tried anyway a loud P0001.
     */
    public void raiseInstanceWatermark(TenantId t, InstanceId i, long g) {
        Sql.write(() -> jdbc.update(
                "UPDATE sm_data.apply_scope SET instance_watermark = GREATEST(instance_watermark, :g)"
                        + " WHERE tenant_id = :t AND instance_id = :i",
                key(t, i).addValue("g", g)));
    }

    /** The placement equivalent; the sentinel is refused here by {@code real_placements_only}. */
    public void raisePlacementWatermark(TenantId t, InstanceId i, PlacementId p, long g) {
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.placement_watermark"
                        + " (tenant_id, instance_id, placement_id, generation)"
                        + " VALUES (:t, :i, :p, :g)"
                        + " ON CONFLICT (tenant_id, instance_id, placement_id) DO UPDATE"
                        + " SET generation = GREATEST(placement_watermark.generation, EXCLUDED.generation)",
                key(t, i).addValue("p", p.value()).addValue("g", g)));
    }

    private static MapSqlParameterSource key(TenantId t, InstanceId i) {
        return new MapSqlParameterSource().addValue("t", t.value()).addValue("i", i.value());
    }
}
