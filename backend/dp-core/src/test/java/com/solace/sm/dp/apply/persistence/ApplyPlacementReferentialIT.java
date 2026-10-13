package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.inventory.TablePlacementCatalog;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 008 S4, {@code BE-D48} ({@code sm_data} V024; owner ruling {@code OR-121}, OQ-8.5 (a)): the database refuses an
 * {@code apply_request} that names a placement the registry does not hold for THAT instance of THAT tenant. The
 * service ({@code requireLivePlacement}) and the catalog stay the first and third enforcers; this is the second, and
 * the only one a new submit path that forgets the service cannot bypass.
 *
 * <p>Every refusal is asserted by SQLSTATE AND constraint name ({@link Refusals}), as {@code sm_dp_runtime}, the
 * credential the application holds. The FK is existence, never liveness: a retired placement of the same instance
 * passes it and is refused at pre-flight by the catalog, which is asserted here as the pair.
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyPlacementReferentialIT {

    private static final String FK = "apply_request_placement_registered";

    private static NamedParameterJdbcTemplate runtime;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
    }

    /** A declared instance with its scope row, and one registered placement on its own target. */
    private record World(UUID tenant, UUID instance, UUID placement) {
        static World fresh() {
            UUID tenant = SmDataPostgres.freshTenant();
            UUID instance = UUID.randomUUID();
            UUID placement = UUID.randomUUID();
            InventoryFixtures.registered(tenant, instance, placement);
            ApplyRows.insertScope(runtime, tenant, instance);
            return new World(tenant, instance, placement);
        }

        /** A second instance of the same tenant, declared, with its own placement. */
        World sibling() {
            UUID other = UUID.randomUUID();
            UUID otherPlacement = UUID.randomUUID();
            InventoryFixtures.registered(tenant, other, otherPlacement);
            ApplyRows.insertScope(runtime, tenant, other);
            return new World(tenant, other, otherPlacement);
        }
    }

    @Test
    void aRequestNamingARegisteredPlacementOfItsOwnInstanceIsAdmitted() {
        World w = World.fresh();
        ApplyRows.insertRequest(runtime, w.tenant, w.instance, w.placement, "REQUESTED");
        assertEquals(1, count(w.tenant, w.instance));
    }

    @Test
    void anApplyRequestCannotNameAPlacementOfAnotherInstance() {
        World a = World.fresh();
        World b = a.sibling();
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, FK,
                () -> ApplyRows.insertRequestUnregistered(runtime, a.tenant, a.instance, b.placement, "apply", "REQUESTED"),
                "instance B's placement named under instance A: the v2 paper's F20, now in the database");
        assertEquals(0, count(a.tenant, a.instance));
    }

    @Test
    void anApplyRequestCannotNameAnUnknownPlacement() {
        World w = World.fresh();
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, FK,
                () -> ApplyRows.insertRequestUnregistered(runtime, w.tenant, w.instance, UUID.randomUUID(), "apply", "REQUESTED"));
    }

    @Test
    void anApplyRequestCannotNameAnotherTenantsPlacement() {
        World mine = World.fresh();
        World theirs = World.fresh();
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, FK,
                () -> ApplyRows.insertRequestUnregistered(runtime, mine.tenant, mine.instance, theirs.placement, "apply", "REQUESTED"),
                "a placement is tenant-keyed: I5 reaches the guard, not only the service");
    }

    @Test
    void aRetiredPlacementPassesTheFkAndIsRefusedAtPreflight() {
        World w = World.fresh();
        runtime.update("UPDATE sm_data.placement SET retired_at = :now WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("now", OffsetDateTime.now()).addValue("t", w.tenant)
                        .addValue("p", w.placement));

        // The FK is existence, not liveness: a retired placement's history (an instance-wide stop's journal) stays writable.
        ApplyRows.insertRequest(runtime, w.tenant, w.instance, w.placement, "REQUESTED");
        assertEquals(1, count(w.tenant, w.instance));

        // The third enforcer, unchanged: the catalog refuses a retired placement at execution time.
        TablePlacementCatalog catalog = new TablePlacementCatalog(new JdbcPlacementRepository(runtime),
                new JdbcTargetConnectionRepository(runtime), new JdbcInstanceStateRepository(runtime));
        RuntimeAdapterException refused = org.junit.jupiter.api.Assertions.assertThrows(RuntimeAdapterException.class,
                () -> catalog.lookup(new PlacementRef(new TenantId(w.tenant), new InstanceId(w.instance),
                        new PlacementId(w.placement))));
        assertEquals("placement.unresolvable", refused.errorClass().token());
    }

    @Test
    void theInstanceWideSentinelIsStillAcceptedForAStop() {
        World w = World.fresh();
        ApplyRows.insertRequest(runtime, w.tenant, w.instance, ApplyRows.INSTANCE_WIDE, "stop", "REQUESTED");
        assertEquals(1, count(w.tenant, w.instance));
        assertNull(runtime.queryForObject("SELECT placement_ref FROM sm_data.apply_request WHERE tenant_id = :t"
                + " AND instance_id = :i", Map.of("t", w.tenant, "i", w.instance), UUID.class),
                "the sentinel references nothing: the generated column is NULL, and MATCH SIMPLE needs no match");
    }

    @Test
    void thePlacementRefMirrorsThePlacementIdForARealPlacement() {
        World w = World.fresh();
        ApplyRows.insertRequest(runtime, w.tenant, w.instance, w.placement, "REQUESTED");
        assertEquals(w.placement, runtime.queryForObject("SELECT placement_ref FROM sm_data.apply_request"
                + " WHERE tenant_id = :t AND instance_id = :i", Map.of("t", w.tenant, "i", w.instance), UUID.class));
    }

    @Test
    void placementRefCannotBeWrittenByTheRuntimeRole() {
        World w = World.fresh();
        assertEquals(Boolean.FALSE, runtime.queryForObject(
                "SELECT has_column_privilege('sm_dp_runtime', 'sm_data.apply_request', 'placement_ref', 'INSERT')"
                        + " OR has_column_privilege('sm_dp_runtime', 'sm_data.apply_request', 'placement_ref', 'UPDATE')",
                Map.of(), Boolean.class), "no writer privilege on the generated column, by grant");
        // 428C9 generated_always: PostgreSQL rejects the value while rewriting the statement, before it checks privileges.
        Refusals.assertRefused("428C9", null, () -> runtime.update(
                "INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id, placement_ref,"
                        + " operation, generation, strategy, requested_by, requested_at, state)"
                        + " VALUES (:a, :t, :i, :p, :p, 'apply', 1, 'manual', 'operator-1', :now, 'REQUESTED')",
                new MapSqlParameterSource().addValue("a", UUID.randomUUID()).addValue("t", w.tenant)
                        .addValue("i", w.instance).addValue("p", w.placement).addValue("now", OffsetDateTime.now())),
                "the column is generated: the guard cannot be steered by a writer");
    }

    @Test
    void placementWatermarkStillRefusesTheSentinel() {
        World w = World.fresh();
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "real_placements_only",
                () -> ApplyRows.insertPlacementWatermark(runtime, w.tenant, w.instance, ApplyRows.INSTANCE_WIDE, 1L));
    }

    @Test
    void thePlacementCarriesTheNewTripleKeyThatTheForeignKeyReferences() {
        assertEquals(1, runtime.queryForObject("SELECT count(*)::int FROM pg_constraint c"
                + " WHERE c.conrelid = 'sm_data.placement'::regclass AND c.contype = 'u'"
                + " AND c.conname = 'placement_instance_identity_key'", Map.of(), Integer.class));
        assertTrue(runtime.queryForObject("SELECT c.convalidated FROM pg_constraint c WHERE c.conname = :n"
                + " AND c.conrelid = 'sm_data.apply_request'::regclass", Map.of("n", FK), Boolean.class),
                "the guard is VALIDATED, not left NOT VALID: it holds for every row, old and new");
    }

    /**
     * V024's side effect, measured on PostgreSQL 16 only: a STORED generated column is NULL in {@code NEW} inside a BEFORE
     * trigger (the PostgreSQL 14 "Generated Columns" page says "it is not allowed to access generated columns in BEFORE
     * triggers" but does not specify the value), so V006's terminal guard and V008's state_version stamp, which compare
     * {@code to_jsonb(NEW)} with {@code to_jsonb(OLD)}, saw every UPDATE as a change. V024 therefore redefines both to
     * ignore placement_ref. On 16 these two tests are what fails without that; on 14 they discriminate only if NEW carries
     * a different value there, which is why the two mutations are OWED on postgres:14 (plan 008 S4 Results).
     */
    @Test
    void theCompactionFoldIsStillTheOneLegalWriteOnATerminalRequest() {
        World w = World.fresh();
        UUID apply = terminalRequest(w);
        assertEquals(1, runtime.update("UPDATE sm_data.apply_request SET attempt_summary = CAST(:s AS jsonb)"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("s", "{\"MATERIALIZE\": {\"attempts\": 2}}")
                        .addValue("t", w.tenant).addValue("a", apply)),
                "V006 sanctioned write 2: attempt_summary, and only attempt_summary, on a terminal row");
        Refusals.assertRefused("P0001", null, () -> runtime.update("UPDATE sm_data.apply_request SET state = 'FAILED'"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", w.tenant).addValue("a", apply)),
                "the guard still refuses any OTHER write on a terminal row (the positive control above is not a hole)");
    }

    @Test
    void aFoldOrANoOpUpdateStillDoesNotDrawAStateVersion() {
        World w = World.fresh();
        UUID apply = terminalRequest(w);
        long before = stateVersion(w, apply);
        runtime.update("UPDATE sm_data.apply_request SET attempt_summary = CAST(:s AS jsonb)"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("s", "{\"MATERIALIZE\": {\"attempts\": 3}}")
                        .addValue("t", w.tenant).addValue("a", apply));
        assertEquals(before, stateVersion(w, apply), "a compaction is not an observable change (V008): same stamp");
        runtime.update("UPDATE sm_data.apply_request SET state = state WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", w.tenant).addValue("a", apply));
        assertEquals(before, stateVersion(w, apply), "a write that changes nothing keeps the stamp too");
    }

    private static UUID terminalRequest(World w) {
        UUID apply = ApplyRows.insertRequest(runtime, w.tenant, w.instance, w.placement, "REQUESTED");
        runtime.update("UPDATE sm_data.apply_request SET state = 'FAILED_NO_EFFECT', terminal_at = :now"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("now", OffsetDateTime.now()).addValue("t", w.tenant)
                        .addValue("a", apply));
        return apply;
    }

    private static long stateVersion(World w, UUID apply) {
        return runtime.queryForObject("SELECT state_version FROM sm_data.apply_request WHERE tenant_id = :t"
                + " AND apply_id = :a", Map.of("t", w.tenant, "a", apply), Long.class);
    }

    private static int count(UUID tenant, UUID instance) {
        return runtime.queryForObject("SELECT count(*)::int FROM sm_data.apply_request WHERE tenant_id = :t"
                + " AND instance_id = :i", Map.of("t", tenant, "i", instance), Integer.class);
    }
}
