package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.solace.sm.dp.api.inventory.AddPlacementCommand;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.apply.persistence.PlacementItSupport.Declared;
import com.solace.sm.dp.inventory.InventoryService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OR-125 (1), W2A-1, against a real PostgreSQL 14 as {@code sm_dp_runtime}: {@code addPlacement} and
 * {@code retirePlacement} record the principal that acted, in {@code placement.created_by} and {@code placement.retired_by}
 * (sm_data V026). The column is an actor id, so the agent gateway can write an agent's id into it later (OR-128 (5)) with
 * no further migration.
 */
@Testcontainers(disabledWithoutDocker = true)
class PlacementActorIT {

    private static InventoryService inventory;
    private static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void wire() {
        inventory = PlacementItSupport.service();
        jdbc = PlacementItSupport.runtime();
    }

    private static UUID actorColumn(String column, UUID tenant, UUID placement) {
        return jdbc.queryForObject("SELECT " + column + " FROM sm_data.placement WHERE tenant_id = :t"
                + " AND placement_id = :p", new MapSqlParameterSource().addValue("t", tenant)
                .addValue("p", placement), UUID.class);
    }

    private static UUID add(Declared d, UUID actor) {
        PlacementSpec spec = PlacementItSupport.freshSpec(PlacementItSupport.freshPort());
        InstanceView view = inventory.addPlacement(d.tenant(), d.instance(), new AddPlacementCommand(
                InventoryFixtures.target(d.tenant()), spec, actor,
                PlacementItSupport.declaration(PlacementItSupport.freshPort())));
        return view.placements().get(1).placementId();
    }

    @Test
    void anAddedPlacementRecordsWhoAddedItAndTheDeclaredOneWhoDeclaredIt() {
        Declared d = Declared.fresh("cache.hit_rate");
        UUID adder = UUID.randomUUID();

        UUID added = add(d, adder);

        assertEquals(adder, actorColumn("created_by", d.tenant(), added));
        assertNull(actorColumn("retired_by", d.tenant(), added), "a live placement has no retirer");
        assertEquals(PlacementItSupport.ACTOR, actorColumn("created_by", d.tenant(), d.placement()),
                "the declare's own placement is attributed to the declaring principal");
    }

    @Test
    void aRetiredPlacementRecordsWhoRetiredItAndNothingElseIsAttributed() {
        Declared d = Declared.fresh("cache.hit_rate");
        UUID adder = UUID.randomUUID();
        UUID retirer = UUID.randomUUID();
        UUID added = add(d, adder);

        inventory.retirePlacement(d.tenant(), d.instance(), added, retirer, UUID.randomUUID());

        assertEquals(retirer, actorColumn("retired_by", d.tenant(), added));
        assertEquals(adder, actorColumn("created_by", d.tenant(), added), "the creator is not overwritten");
        assertNull(actorColumn("retired_by", d.tenant(), d.placement()), "the other placement is untouched");
    }

    @Test
    void theRunningRoleMayWriteRetiredByOnlyTogetherWithRetiredAtAndNeverCreatedBy() {
        Declared d = Declared.fresh("cache.hit_rate");
        UUID added = add(d, UUID.randomUUID());
        MapSqlParameterSource key = new MapSqlParameterSource().addValue("t", d.tenant()).addValue("p", added)
                .addValue("x", UUID.randomUUID());

        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> jdbc.update(
                "UPDATE sm_data.placement SET created_by = :x WHERE tenant_id = :t AND placement_id = :p", key));
        Refusals.assertRaised("a placement is immutable", () -> SmDataPostgres.superuserJdbc().update(
                "UPDATE sm_data.placement SET retired_by = :x WHERE tenant_id = :t AND placement_id = :p", key));
        Refusals.assertRaised("a placement is immutable", () -> SmDataPostgres.superuserJdbc().update(
                "UPDATE sm_data.placement SET created_by = :x, retired_at = now() WHERE tenant_id = :t"
                        + " AND placement_id = :p", key));
        // The one legal change sets both, once; a second write of either is refused.
        jdbc.update("UPDATE sm_data.placement SET retired_at = now(), retired_by = :x WHERE tenant_id = :t"
                + " AND placement_id = :p", key);
        Refusals.assertRaised("a placement is immutable", () -> jdbc.update(
                "UPDATE sm_data.placement SET retired_by = gen_random_uuid() WHERE tenant_id = :t AND placement_id = :p",
                key));
        assertEquals(key.getValue("x"), actorColumn("retired_by", d.tenant(), added));
    }

    @Test
    void aRetirerWithoutARetirementIsRefusedByTheCheckEvenWhereTheTriggerIsSteppedAround() throws Exception {
        Declared d = Declared.fresh("cache.hit_rate");
        UUID added = add(d, UUID.randomUUID());

        // session_replication_role = replica skips the immutability TRIGGER (superuser only), never a CHECK: the constraint
        // is what still says "a placement with a retirer was retired".
        try (java.sql.Connection c = SmDataPostgres.superuser().getConnection();
                java.sql.Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("SET LOCAL session_replication_role = replica");
            try (java.sql.PreparedStatement update = c.prepareStatement(
                    "UPDATE sm_data.placement SET retired_by = gen_random_uuid() WHERE tenant_id = ? AND placement_id = ?")) {
                update.setObject(1, d.tenant());
                update.setObject(2, added);
                Refusals.assertRefused(Refusals.CHECK_VIOLATION, "placement_retired_by_needs_retirement",
                        update::executeUpdate);
            }
            c.rollback();
        }
    }

    @Test
    void aSweepThatRetiresEverythingAttributesNoPrincipal() {
        Declared d = Declared.fresh("cache.hit_rate");
        UUID added = add(d, UUID.randomUUID());

        jdbc.update("UPDATE sm_data.placement SET retired_at = now() WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", d.tenant()).addValue("i", d.instance()));

        assertNull(actorColumn("retired_by", d.tenant(), added), "NULL means a system retirement, never a guess");
    }
}
