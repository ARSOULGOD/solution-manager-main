package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code sm_data} V024 across an upgrade (plan 008 S4, {@code BE-D48}): a database migrated to V023 holds apply history
 * written before the placement guard existed. V024 accepts a history that already satisfies it, adds the generated
 * column and the validated foreign key, and REFUSES, rolling back and naming the procedure, a history that does not.
 *
 * <p>Same shape as {@link IntentIdentityUpgradeIT}: each test builds its own database ({@code MetricsDb.fresh(.., NOTHING)}),
 * stops Flyway at V023 with {@code target("23")}, seeds rows as the database owner exactly as the V023 engine wrote them,
 * and only then lets Flyway run V024 (and whatever follows it). MF-4 (the owner's {@code OR-118} database) is not
 * measured here; it is not reachable from a test lane, and the refusal below is what protects it.
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyPlacementGuardUpgradeIT {

    private static final UUID NIL = ApplyRows.INSTANCE_WIDE;

    private static Flyway flyway(DataSource migrator, String target) {
        var cfg = Flyway.configure().dataSource(migrator).schemas(SmDataMigrator.SCHEMA)
                .defaultSchema(SmDataMigrator.SCHEMA).initSql(SmDataMigrator.INIT_SQL)
                .locations(SmDataMigrator.LOCATION).createSchemas(false);
        return (target == null ? cfg : cfg.target(target)).load();
    }

    /** A database as the V023 release left it: migrated to V023, no V024. */
    private static final class AtV023 {
        final MetricsDb db;
        final DataSource migrator;
        final NamedParameterJdbcTemplate owner;
        final UUID tenant = UUID.randomUUID();
        final UUID instance = UUID.randomUUID();
        final UUID p1 = UUID.randomUUID();

        AtV023(String name) {
            SmDataPostgres.bootstrap();
            db = MetricsDb.fresh("v024_" + name, MetricsDb.Migrated.NOTHING);
            migrator = SmDataPostgres.dataSource(db.url(), "sm_dp_migrator", SmDataPostgres.MIGRATOR_PASSWORD);
            flyway(migrator, "23").migrate();
            owner = new NamedParameterJdbcTemplate(db.superuser());
            InventoryFixtures.registered(owner, tenant, instance, p1);
            owner.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)",
                    ApplyRows.scope(tenant, instance));
        }

        /** A request as V023 allowed it: any placement id whatever, registered or not. */
        UUID request(UUID placement, String operation, String state) {
            return ApplyRows.insertRequestUnregistered(owner, tenant, instance, placement, operation, state);
        }

        void upgrade() {
            flyway(migrator, null).migrate();
        }

        boolean hasGuard() {
            return owner.queryForObject("SELECT count(*)::int FROM information_schema.columns"
                    + " WHERE table_schema = 'sm_data' AND table_name = 'apply_request'"
                    + " AND column_name = 'placement_ref'", Map.of(), Integer.class) == 1;
        }

        boolean hasPlacementKey() {
            return owner.queryForObject("SELECT count(*)::int FROM pg_constraint WHERE conname ="
                    + " 'placement_instance_identity_key'", Map.of(), Integer.class) == 1;
        }

        /** Every column V023 wrote, hashed: V024 must leave the history byte-for-byte as it was. */
        String requestFingerprint() {
            return owner.queryForObject("SELECT md5(string_agg((to_jsonb(r) - 'placement_ref')::text, '|' ORDER BY apply_id))"
                    + " FROM sm_data.apply_request r", Map.of(), String.class);
        }

        static String refusalChain(Throwable refused) {
            StringBuilder chain = new StringBuilder();
            for (Throwable t = refused; t != null && t.getCause() != t; t = t.getCause()) {
                chain.append(t.getMessage()).append(" | ");
            }
            return chain.toString();
        }
    }

    @Test
    void aHistoryThatAlreadySatisfiesTheGuardUpgradesAndIsLeftAsItWas() {
        AtV023 v = new AtV023("clean");
        UUID scoped = v.request(v.p1, "apply", "REQUESTED");
        UUID stop = v.request(NIL, "stop", "REQUESTED");
        String before = v.requestFingerprint();
        assertFalse(v.hasGuard(), "precondition: this database is at V023");

        v.upgrade();

        assertTrue(v.hasGuard());
        assertTrue(v.hasPlacementKey());
        assertEquals(before, v.requestFingerprint(), "V024 changes no existing column of any request row");
        assertEquals(v.p1, v.owner.queryForObject("SELECT placement_ref FROM sm_data.apply_request WHERE apply_id = :a",
                Map.of("a", scoped), UUID.class), "a placement-scoped request's reference is its placement");
        assertNull(v.owner.queryForObject("SELECT placement_ref FROM sm_data.apply_request WHERE apply_id = :a",
                Map.of("a", stop), UUID.class), "the instance-wide sentinel references nothing");
        assertEquals(Boolean.TRUE, v.owner.queryForObject("SELECT convalidated FROM pg_constraint"
                + " WHERE conname = 'apply_request_placement_registered'", Map.of(), Boolean.class), "VALIDATED, not NOT VALID");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "apply_request_placement_registered",
                () -> v.request(UUID.randomUUID(), "apply", "REQUESTED"), "the guard is live after the upgrade");
    }

    @Test
    void v024RefusesAnUnregisteredPlacementRollsBackAndRunsOnceItIsRegistered() {
        AtV023 v = new AtV023("unregistered");
        UUID ghost = UUID.randomUUID();
        UUID apply = v.request(ghost, "apply", "REQUESTED");
        String before = v.requestFingerprint();

        Throwable refused = assertThrows(Throwable.class, v::upgrade);

        String chain = AtV023.refusalChain(refused);
        assertTrue(chain.contains("V024 refuses to run while an apply_request names a placement that is not registered"),
                "V024's own sentence, naming the procedure. Was: " + chain);
        assertTrue(chain.contains("Upgrade across the apply-request placement guard"), chain);
        assertFalse(chain.contains(ghost.toString()), "never a row's value (I6)");
        assertFalse(chain.contains(apply.toString()), "never a row's value (I6)");
        assertFalse(chain.contains(v.tenant.toString()), "never a row's value (I6)");
        assertFalse(v.hasGuard(), "the migration rolled back: no column was added");
        assertFalse(v.hasPlacementKey(), "…and no key was added to placement");
        assertEquals(before, v.requestFingerprint(), "…and nothing was deleted or changed");

        // the positive control: the same database upgrades once the placement is registered for that instance
        InventoryFixtures.registered(v.owner, v.tenant, v.instance, ghost);
        v.upgrade();
        assertTrue(v.hasGuard());
    }

    @Test
    void v024RefusesAPlacementThatBelongsToAnotherInstanceOfTheSameTenant() {
        AtV023 v = new AtV023("foreign_instance");
        UUID otherInstance = UUID.randomUUID();
        UUID otherPlacement = UUID.randomUUID();
        InventoryFixtures.registered(v.owner, v.tenant, otherInstance, otherPlacement);
        v.request(otherPlacement, "apply", "REQUESTED");   // instance A's request naming instance B's placement

        Throwable refused = assertThrows(Throwable.class, v::upgrade);

        assertTrue(AtV023.refusalChain(refused).contains("V024 refuses to run"),
                "the guard binds the placement to the request's instance, so a pre-existing mismatch is refused too");
        assertFalse(v.hasGuard());
    }

    /**
     * W3 review W3A-3: a V024-era binary must not go Ready on a V023 schema, where the referential guard is absent
     * (mirrors the V021 probe in {@link IntentIdentityUpgradeIT}). The message names the runbook section.
     */
    @Test
    void theNewBinaryRefusesToGoReadyOnAV023SchemaAndPassesOnceV024Ran() throws Exception {
        AtV023 v = new AtV023("readiness");
        ApplyPersistenceConfiguration config = new ApplyPersistenceConfiguration();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> config.smDataSchemaReadiness(v.db.runtime()).afterPropertiesSet());
        assertTrue(refused.getMessage().contains("apply_request_placement_registered")
                        && refused.getMessage().contains("Upgrade across the apply-request placement guard"),
                refused.getMessage());

        v.upgrade();
        config.smDataSchemaReadiness(v.db.runtime()).afterPropertiesSet();
    }
}
