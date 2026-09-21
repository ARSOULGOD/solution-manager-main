package com.solace.sm.dp.apply.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The minimal legal rows of design §3.1, in one place, so the privilege tests are testing privileges
 * rather than re-deriving which CHECK wants what.
 *
 * <p>Every statement here binds its parameters ({@code I5}) — a test helper that interpolates
 * strings teaches the pattern the production code must not use.
 *
 * <p><b>The minimal legal {@code apply_request}</b> is worth stating, because seven CHECKs
 * constrain it simultaneously: {@code operation='apply'} forces {@code generation NOT NULL}
 * ({@code apply_has_generation}) and {@code strategy NOT NULL} ({@code lifecycle_has_strategy});
 * {@code strategy='manual'} is legal for {@code apply} ({@code restart_not_manual} only fences
 * {@code restart}) and avoids {@code full_outage_confirmed}, which would demand
 * {@code outage_confirmed_by} for {@code restart-all}; {@code state='REQUESTED'} forces
 * {@code terminal_at IS NULL} ({@code terminal_has_timestamp}) and satisfies
 * {@code state_fits_operation}; and leaving every resolution column NULL satisfies both resolution
 * CHECKs. {@code placement_id} is the nil-UUID sentinel, never SQL NULL — a NULL there defeats the
 * fences and is refused by {@code NOT NULL} anyway (23502).
 */
final class ApplyRows {

    /** The instance-wide sentinel of §3.1 — an explicit value, deliberately not NULL. */
    static final UUID INSTANCE_WIDE = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private ApplyRows() {
    }

    /** The serialization point and instance watermark. Runtime holds table-wide INSERT. */
    static void insertScope(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance) {
        jdbc.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance));
    }

    /** The minimal legal request — see the class javadoc for which CHECK each value answers. */
    static UUID insertRequest(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance,
            UUID placement, String state) {
        UUID applyId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.apply_request"
                        + " (apply_id, tenant_id, instance_id, placement_id, operation, generation,"
                        + "  strategy, requested_by, requested_at, state)"
                        + " VALUES (:a, :t, :i, :p, 'apply', 1, 'manual', 'operator-1', :now, :s)",
                new MapSqlParameterSource().addValue("a", applyId).addValue("t", tenant)
                        .addValue("i", instance).addValue("p", placement)
                        .addValue("now", OffsetDateTime.now()).addValue("s", state));
        return applyId;
    }

    /** A placement watermark row; {@code generation > 0} and the placement may not be the sentinel. */
    static void insertPlacementWatermark(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance,
            UUID placement, long generation) {
        jdbc.update("INSERT INTO sm_data.placement_watermark"
                        + " (tenant_id, instance_id, placement_id, generation)"
                        + " VALUES (:t, :i, :p, :g)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance)
                        .addValue("p", placement).addValue("g", generation));
    }

    static MapSqlParameterSource scope(UUID tenant, UUID instance) {
        return new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance);
    }
}
