package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The value half of obligation <b>(t)</b>: the two {@code *_only_rises} triggers of design §3.1.
 *
 * <p>The hazard they close is row-relative and was measured: the runtime's {@code UPDATE} right
 * reaches every row, so one unguarded statement could rewind any tenant's fence and re-admit a
 * superseded generation. With the guard, a bug touching the wrong row can only OVER-fence — an
 * over-raised watermark refuses applies, it never re-admits a stale one.
 *
 * <p>Every refusal is asserted with the trigger's own wording, per §3.3's harness rule. That rule is
 * not pedantry here: §3.1 records that a single shared function branching on {@code TG_TABLE_NAME}
 * failed with {@code 42703} in three of four directions, and an earlier run read one of those
 * {@code 42703}s as the refusal it was asserting. A test that accepts "some error" would have passed
 * on a schema where no watermark could be raised at all.
 */
@Testcontainers(disabledWithoutDocker = true)
class WatermarkTriggerIT {

    private static NamedParameterJdbcTemplate runtime;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
    }

    @Test
    void theInstanceWatermarkRisesAndStaysEqualButMayNotDecrease() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);

        assertEquals(1, setInstanceWatermark(tenant, instance, 10), "a rise must succeed");
        assertEquals(1, setInstanceWatermark(tenant, instance, 10),
                "equal is not a decrease — the trigger tests '<', and re-landing the same generation "
                        + "must not be an error");

        Refusals.assertRaised("instance watermark may not decrease (10 -> 3)",
                () -> setInstanceWatermark(tenant, instance, 3));

        assertEquals(Integer.valueOf(10), currentInstanceWatermark(tenant, instance),
                "the refused statement must have left the fence where it was");
    }

    @Test
    void thePlacementWatermarkRisesAndStaysEqualButMayNotDecrease() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        ApplyRows.insertPlacementWatermark(runtime, tenant, instance, placement, 7);

        assertEquals(1, setPlacementWatermark(tenant, instance, placement, 12));
        assertEquals(1, setPlacementWatermark(tenant, instance, placement, 12));

        Refusals.assertRaised("placement watermark may not decrease (12 -> 1)",
                () -> setPlacementWatermark(tenant, instance, placement, 1));
    }

    @Test
    void theGreatestUpsertIdiomTheAdmissionPathUsesIsAdmittedAndItsLoweringFormIsNot() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        ApplyRows.insertPlacementWatermark(runtime, tenant, instance, placement, 20);

        // What the admission transaction actually writes: an upsert whose DO UPDATE can only raise.
        assertEquals(1, runtime.update(
                "INSERT INTO sm_data.placement_watermark (tenant_id, instance_id, placement_id, generation)"
                        + " VALUES (:t, :i, :p, :g)"
                        + " ON CONFLICT (tenant_id, instance_id, placement_id) DO UPDATE"
                        + " SET generation = GREATEST(sm_data.placement_watermark.generation, EXCLUDED.generation)",
                ApplyRows.scope(tenant, instance).addValue("p", placement).addValue("g", 25)));
        assertEquals(Integer.valueOf(25), currentPlacementWatermark(tenant, instance, placement));

        // GREATEST also makes a late, lower write a harmless no-op rather than a rewind…
        assertEquals(1, runtime.update(
                "INSERT INTO sm_data.placement_watermark (tenant_id, instance_id, placement_id, generation)"
                        + " VALUES (:t, :i, :p, :g)"
                        + " ON CONFLICT (tenant_id, instance_id, placement_id) DO UPDATE"
                        + " SET generation = GREATEST(sm_data.placement_watermark.generation, EXCLUDED.generation)",
                ApplyRows.scope(tenant, instance).addValue("p", placement).addValue("g", 4)));
        assertEquals(Integer.valueOf(25), currentPlacementWatermark(tenant, instance, placement));

        // …and the trigger is what stops an upsert that FORGOT the GREATEST. This is the version of
        // the hazard that reaches the row through ON CONFLICT rather than a plain UPDATE.
        Refusals.assertRaised("placement watermark may not decrease (25 -> 1)",
                () -> runtime.update(
                        "INSERT INTO sm_data.placement_watermark (tenant_id, instance_id, placement_id, generation)"
                                + " VALUES (:t, :i, :p, :g)"
                                + " ON CONFLICT (tenant_id, instance_id, placement_id) DO UPDATE"
                                + " SET generation = EXCLUDED.generation",
                        ApplyRows.scope(tenant, instance).addValue("p", placement).addValue("g", 1)));

        Refusals.assertRaised("instance watermark may not decrease (30 -> 2)",
                () -> {
                    setInstanceWatermark(tenant, instance, 30);
                    runtime.update(
                            "INSERT INTO sm_data.apply_scope (tenant_id, instance_id, instance_watermark)"
                                    + " VALUES (:t, :i, 2)"
                                    + " ON CONFLICT (tenant_id, instance_id) DO UPDATE"
                                    + " SET instance_watermark = EXCLUDED.instance_watermark",
                            ApplyRows.scope(tenant, instance));
                });
    }

    private static int setInstanceWatermark(UUID tenant, UUID instance, long value) {
        return runtime.update("UPDATE sm_data.apply_scope SET instance_watermark = :g"
                        + " WHERE tenant_id = :t AND instance_id = :i",
                ApplyRows.scope(tenant, instance).addValue("g", value));
    }

    private static int setPlacementWatermark(UUID tenant, UUID instance, UUID placement, long value) {
        return runtime.update("UPDATE sm_data.placement_watermark SET generation = :g"
                        + " WHERE tenant_id = :t AND instance_id = :i AND placement_id = :p",
                ApplyRows.scope(tenant, instance).addValue("p", placement).addValue("g", value));
    }

    private static Integer currentInstanceWatermark(UUID tenant, UUID instance) {
        return runtime.queryForObject("SELECT instance_watermark FROM sm_data.apply_scope"
                        + " WHERE tenant_id = :t AND instance_id = :i",
                ApplyRows.scope(tenant, instance), Integer.class);
    }

    private static Integer currentPlacementWatermark(UUID tenant, UUID instance, UUID placement) {
        return runtime.queryForObject("SELECT generation FROM sm_data.placement_watermark"
                        + " WHERE tenant_id = :t AND instance_id = :i AND placement_id = :p",
                ApplyRows.scope(tenant, instance).addValue("p", placement), Integer.class);
    }
}
