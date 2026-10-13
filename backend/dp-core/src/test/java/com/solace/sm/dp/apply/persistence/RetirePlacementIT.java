package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.AddPlacementCommand;
import com.solace.sm.dp.api.inventory.InstanceNotActiveException;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.LastLivePlacementException;
import com.solace.sm.dp.api.inventory.PlacementInFlightException;
import com.solace.sm.dp.api.inventory.PlacementSchedulePendingException;
import com.solace.sm.dp.api.inventory.PlacementNotLiveException;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.apply.persistence.PlacementItSupport.Declared;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.events.EventEnvelope;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 008 S3 ({@code BE-D53}, {@code OQ-8.4} under plan 012's {@code OPS-Q12} R-1) against a real PostgreSQL 14 as
 * {@code sm_dp_runtime}: {@code retirePlacement} over the production inventory service and the real rows.
 *
 * <p>What it proves that the no-database test cannot: the in-flight check reads the REAL {@code apply_request} and
 * {@code fleet_member} rows (including the instance-wide stop that addresses every placement), the freed container name,
 * directory and ports really can be added again (the partial unique indexes and endpoint triggers exclude retired rows),
 * the row is kept, and two retirements racing for the last two placements have exactly one winner.
 */
@Testcontainers(disabledWithoutDocker = true)
class RetirePlacementIT {

    private static final UUID NIL = ApplyRows.INSTANCE_WIDE;
    private static final UUID CORRELATION = UUID.randomUUID();

    private static InventoryService inventory;
    private static NamedParameterJdbcTemplate jdbc;
    private static ExecutorService pool;

    @BeforeAll
    static void wire() {
        inventory = PlacementItSupport.service();
        jdbc = PlacementItSupport.runtime();
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterAll
    static void close() {
        pool.shutdownNow();
    }

    // ---- arrangement ---------------------------------------------------------------------------------------

    /** An instance with two live placements on two targets: the declared one and an added one. */
    private record Two(Declared declared, UUID second, PlacementSpec secondSpec, int secondMetricsPort,
            UUID secondTarget) {
        UUID tenant() {
            return declared.tenant();
        }

        UUID instance() {
            return declared.instance();
        }
    }

    private static Two two(Declared d) {
        UUID secondTarget = InventoryFixtures.target(d.tenant());
        PlacementSpec spec = PlacementItSupport.freshSpec(PlacementItSupport.freshPort());
        int metricsPort = PlacementItSupport.freshPort();
        InstanceView view = inventory.addPlacement(d.tenant(), d.instance(), new AddPlacementCommand(secondTarget, spec,
                PlacementItSupport.ACTOR, PlacementItSupport.declaration(metricsPort)));
        return new Two(d, view.placements().get(1).placementId(), spec, metricsPort, secondTarget);
    }

    private static Two two() {
        return two(Declared.fresh("cache.hit_rate"));
    }

    private static void scopeRow(UUID tenant, UUID instance) {
        jdbc.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)"
                + " ON CONFLICT DO NOTHING", new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance));
    }

    /** A request row in {@code state}; terminal states carry their timestamp ({@code terminal_has_timestamp}). */
    private static UUID request(UUID tenant, UUID instance, UUID placement, String operation, String state) {
        scopeRow(tenant, instance);
        boolean open = List.of("REQUESTED", "RUNNING", "APPLY_INDETERMINATE").contains(state);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id, operation,"
                        + " generation, strategy, requested_by, requested_at, state, terminal_at)"
                        + " VALUES (:a, :t, :i, :p, :op, :g, :s, 'operator-1', :now, :state, :terminal)",
                new MapSqlParameterSource().addValue("a", id).addValue("t", tenant).addValue("i", instance)
                        .addValue("p", placement).addValue("op", operation)
                        .addValue("g", "apply".equals(operation) ? 3L : null)
                        .addValue("s", "apply".equals(operation) ? "manual" : null)
                        .addValue("now", OffsetDateTime.now()).addValue("state", state)
                        .addValue("terminal", open ? null : OffsetDateTime.now()));
        return id;
    }

    private static UUID fleetMember(UUID tenant, UUID instance, UUID placement) {
        UUID fleet = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.fleet_action (tenant_id, fleet_id, catalog_solution_id, strategy, batch_size,"
                        + " on_failure, state, current_batch, requested_by, requested_at, correlation_id)"
                        + " VALUES (:t, :f, :c, 'manual', 1, 'halt-remaining', 'RUNNING', 0, 'operator-1', :now, :corr)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("f", fleet).addValue("c", UUID.randomUUID())
                        .addValue("now", OffsetDateTime.now()).addValue("corr", UUID.randomUUID()));
        jdbc.update("INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, batch, instance_id, placement_id,"
                        + " generation, apply_id, disposition) VALUES (:t, :f, 0, 0, :i, :p, 3, :a, 'pending')",
                new MapSqlParameterSource().addValue("t", tenant).addValue("f", fleet).addValue("i", instance)
                        .addValue("p", placement).addValue("a", UUID.randomUUID()));
        return fleet;
    }

    private static void moveMember(UUID tenant, UUID fleet, String sql) {
        jdbc.update(sql, new MapSqlParameterSource().addValue("t", tenant).addValue("f", fleet));
    }

    private static String retiredAt(UUID tenant, UUID placement) {
        return jdbc.queryForObject("SELECT coalesce(retired_at::text, 'live') FROM sm_data.placement"
                + " WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement), String.class);
    }

    // ---- the operation -------------------------------------------------------------------------------------

    @Test
    void aPlacementIsRetiredItsRowIsKeptAndTheHostIsNeverTouched() {
        Two two = two();
        int requestsBefore = PlacementItSupport.count("apply_request", two.tenant());
        long stateBefore = PlacementItSupport.stateVersion(two.tenant(), two.instance());

        InstanceView view = inventory.retirePlacement(two.tenant(), two.instance(), two.second(),
                PlacementItSupport.ACTOR, CORRELATION);

        assertEquals(List.of(two.declared().placement()),
                view.placements().stream().map(p -> p.placementId()).toList(), "the other placement is untouched");
        assertFalse(retiredAt(two.tenant(), two.second()).equals("live"), "the placement carries a retired_at");
        assertEquals(2, PlacementItSupport.count("placement", two.tenant()), "the row is kept forever");
        assertEquals(1, PlacementItSupport.livePlacements(two.tenant(), two.instance()));
        assertEquals(requestsBefore, PlacementItSupport.count("apply_request", two.tenant()),
                "no stop, no apply: the container is left running because nothing here knows how to touch it");
        assertEquals(0, PlacementItSupport.count("apply_attempt", two.tenant()));
        assertTrue(view.stateVersion() > stateBefore, "the instance's observable view changed, so it is stamped");
    }

    // ---- the record (plan 012 T5, OPS-Q12 R-1, BE-D53) ------------------------------------------------------

    private record Record(UUID retirementId, UUID instanceId, UUID requestedBy, UUID correlationId, String workload,
            String lastHealth, boolean closed, String outcome) {
    }

    private static List<Record> recordsOf(UUID tenant, UUID placement) {
        return jdbc.query("SELECT retirement_id, instance_id, requested_by, correlation_id, workload_disposition,"
                        + " last_health::text AS last_health, completed_at IS NOT NULL AS closed, outcome"
                        + " FROM sm_data.instance_retirement WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement),
                (rs, i) -> new Record(rs.getObject("retirement_id", UUID.class), rs.getObject("instance_id", UUID.class),
                        rs.getObject("requested_by", UUID.class), rs.getObject("correlation_id", UUID.class),
                        rs.getString("workload_disposition"), rs.getString("last_health"), rs.getBoolean("closed"),
                        rs.getString("outcome")));
    }

    private static void health(UUID tenant, UUID instance, UUID placement, String state) {
        jdbc.update("INSERT INTO sm_data.placement_health (tenant_id, placement_id, instance_id, state, checked_at,"
                        + " changed_at) VALUES (:t, :p, :i, :s, now(), now())",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement).addValue("i", instance)
                        .addValue("s", state));
    }

    @Test
    void aRetirementWritesItsPlacementScopedRecordNamingTheActorTheRequestAndTheLastHealth() {
        Two two = two();
        health(two.tenant(), two.instance(), two.second(), "GREEN");

        inventory.retirePlacement(two.tenant(), two.instance(), two.second(), PlacementItSupport.ACTOR, CORRELATION);

        List<Record> records = recordsOf(two.tenant(), two.second());
        assertEquals(1, records.size(), "one record for the retired placement");
        Record r = records.get(0);
        assertEquals(two.instance(), r.instanceId());
        assertEquals(PlacementItSupport.ACTOR, r.requestedBy(), "who asked");
        assertEquals(CORRELATION, r.correlationId(), "the request it came from");
        assertEquals("leave-running", r.workload());
        assertTrue(r.closed() && "retired".equals(r.outcome()), "a placement retires in one statement: written closed");
        assertTrue(r.lastHealth().contains("GREEN") && r.lastHealth().contains(two.second().toString()), r.lastHealth());
        assertEquals(0, recordsOf(two.tenant(), two.declared().placement()).size(), "the other placement has none");
        UUID retiredBy = jdbc.queryForObject("SELECT retired_by FROM sm_data.placement WHERE tenant_id = :t"
                        + " AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", two.tenant()).addValue("p", two.second()), UUID.class);
        assertEquals(r.requestedBy(), retiredBy, "one call writes both records with the same actor");
    }

    @Test
    void aPlacementNeverProbedIsRecordedWithANullLastHealth() {
        Two two = two();

        inventory.retirePlacement(two.tenant(), two.instance(), two.second(), PlacementItSupport.ACTOR, CORRELATION);

        assertTrue(recordsOf(two.tenant(), two.second()).get(0).lastHealth().contains("null"),
                recordsOf(two.tenant(), two.second()).get(0).lastHealth());
    }

    @Test
    void aRefusedRetirementWritesNoRecord() {
        Declared d = Declared.fresh("cache.hit_rate");
        assertThrows(LastLivePlacementException.class,
                () -> inventory.retirePlacement(d.tenant(), d.instance(), d.placement(), PlacementItSupport.ACTOR, CORRELATION));
        Two two = two();
        request(two.tenant(), two.instance(), two.second(), "apply", "RUNNING");
        assertThrows(PlacementInFlightException.class, () -> inventory.retirePlacement(two.tenant(), two.instance(),
                two.second(), PlacementItSupport.ACTOR, CORRELATION));
        assertThrows(PlacementNotLiveException.class, () -> inventory.retirePlacement(two.tenant(), two.instance(),
                UUID.randomUUID(), PlacementItSupport.ACTOR, CORRELATION));

        assertEquals(0, PlacementItSupport.count("instance_retirement", d.tenant()));
        assertEquals(0, PlacementItSupport.count("instance_retirement", two.tenant()));
    }

    @Test
    void theRecordIsInsertOnlyAndAPlacementRetirementIsNotAnUndeclare() {
        Two two = two();
        inventory.retirePlacement(two.tenant(), two.instance(), two.second(), PlacementItSupport.ACTOR, CORRELATION);

        // a placement-scoped record never counts as the instance's open undeclare (V023's open-once index and the sweep
        // read placement_id IS NULL), and the instance stays active
        assertEquals(0, jdbc.queryForObject("SELECT count(*)::int FROM sm_data.instance_retirement WHERE tenant_id = :t"
                        + " AND instance_id = :i AND placement_id IS NULL",
                new MapSqlParameterSource().addValue("t", two.tenant()).addValue("i", two.instance()), Integer.class));
        assertEquals("active", jdbc.queryForObject("SELECT lifecycle FROM sm_data.instance_state WHERE tenant_id = :t"
                        + " AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", two.tenant()).addValue("i", two.instance()), String.class));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "UPDATE sm_data.instance_retirement SET requested_by = :x WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("x", UUID.randomUUID()).addValue("t", two.tenant())
                        .addValue("p", two.second())), "the runtime role cannot rewrite who asked");
    }

    @Test
    void theLastLivePlacementIsRefusedAndStaysLive() {
        Declared d = Declared.fresh("cache.hit_rate");

        assertThrows(LastLivePlacementException.class,
                () -> inventory.retirePlacement(d.tenant(), d.instance(), d.placement(), PlacementItSupport.ACTOR, CORRELATION));

        assertEquals("live", retiredAt(d.tenant(), d.placement()));
    }

    @Test
    void aPlacementOfAnotherInstanceAnotherTenantOrNowhereIsNotLive() {
        Two mine = two();
        Two theirs = two();

        assertThrows(PlacementNotLiveException.class, () -> inventory.retirePlacement(mine.tenant(), mine.instance(),
                UUID.randomUUID(), PlacementItSupport.ACTOR, CORRELATION), "unknown");
        assertThrows(PlacementNotLiveException.class, () -> inventory.retirePlacement(mine.tenant(), mine.instance(),
                theirs.second(), PlacementItSupport.ACTOR, CORRELATION), "another tenant's placement (I5)");
        assertThrows(InstanceNotActiveException.class, () -> inventory.retirePlacement(mine.tenant(), theirs.instance(),
                theirs.second(), PlacementItSupport.ACTOR, CORRELATION), "another tenant's instance");
        assertEquals("live", retiredAt(theirs.tenant(), theirs.second()));
        assertEquals("live", retiredAt(mine.tenant(), mine.second()));
    }

    @Test
    void anAlreadyRetiredPlacementIsNotLiveASecondTime() {
        Two two = two();
        inventory.retirePlacement(two.tenant(), two.instance(), two.second(), PlacementItSupport.ACTOR, CORRELATION);

        assertThrows(PlacementNotLiveException.class, () -> inventory.retirePlacement(two.tenant(), two.instance(),
                two.second(), PlacementItSupport.ACTOR, CORRELATION));
    }

    @Test
    void aPlacementNamedByANonTerminalRequestIsRefusedAndATerminalOneIsNot() {
        Two two = two();
        UUID applying = request(two.tenant(), two.instance(), two.second(), "apply", "RUNNING");
        assertThrows(PlacementInFlightException.class, () -> inventory.retirePlacement(two.tenant(), two.instance(),
                two.second(), PlacementItSupport.ACTOR, CORRELATION));
        assertEquals("live", retiredAt(two.tenant(), two.second()));

        jdbc.update("UPDATE sm_data.apply_request SET state = 'FAILED_NO_EFFECT', terminal_at = :now"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", two.tenant()).addValue("a", applying)
                        .addValue("now", OffsetDateTime.now()));
        request(two.tenant(), two.instance(), two.second(), "apply", "APPLIED");
        request(two.tenant(), two.instance(), two.declared().placement(), "apply", "RUNNING");   // the OTHER placement's

        InstanceView view = inventory.retirePlacement(two.tenant(), two.instance(), two.second(),
                PlacementItSupport.ACTOR, CORRELATION);
        assertEquals(1, view.placements().size(),
                "a landed request on it, and a request in flight on a DIFFERENT placement, do not hold it");
    }

    @Test
    void anIndeterminateRequestHoldsThePlacementToo() {
        Two two = two();
        request(two.tenant(), two.instance(), two.second(), "apply", "APPLY_INDETERMINATE");

        assertThrows(PlacementInFlightException.class, () -> inventory.retirePlacement(two.tenant(), two.instance(),
                two.second(), PlacementItSupport.ACTOR, CORRELATION));
    }

    @Test
    void anInstanceWideStopInFlightAddressesEveryLivePlacementSoItHoldsThemAll() {
        Two two = two();
        request(two.tenant(), two.instance(), NIL, "stop", "REQUESTED");

        assertThrows(PlacementInFlightException.class, () -> inventory.retirePlacement(two.tenant(), two.instance(),
                two.second(), PlacementItSupport.ACTOR, CORRELATION));
        assertThrows(PlacementInFlightException.class, () -> inventory.retirePlacement(two.tenant(), two.instance(),
                two.declared().placement(), PlacementItSupport.ACTOR, CORRELATION));
    }

    /** Fable F-3 (OR-131 Q2 by analogy): a PENDING schedule on the placement refuses the retire; cancelled, it goes through. */
    @Test
    void aPendingScheduleOnThePlacementRefusesItsRetirementAndAfterCancelItSucceeds() {
        Two two = two();
        UUID schedule = UUID.randomUUID();
        MapSqlParameterSource row = new MapSqlParameterSource().addValue("t", two.tenant()).addValue("s", schedule)
                .addValue("i", two.instance()).addValue("p", two.second()).addValue("by", UUID.randomUUID())
                .addValue("at", OffsetDateTime.now()).addValue("nb", OffsetDateTime.now().plusHours(1))
                .addValue("na", OffsetDateTime.now().plusHours(2));
        jdbc.update("INSERT INTO sm_data.apply_schedule (tenant_id, schedule_id, instance_id, placement_id, operation,"
                + " generation, strategy, not_before, not_after, requested_by, requested_at)"
                + " VALUES (:t, :s, :i, :p, 'apply', 9, 'manual', :nb, :na, :by, :at)", row);

        assertThrows(PlacementSchedulePendingException.class, () -> inventory.retirePlacement(two.tenant(),
                two.instance(), two.second(), PlacementItSupport.ACTOR, CORRELATION));
        assertEquals("live", retiredAt(two.tenant(), two.second()), "nothing changed");
        assertEquals(0, PlacementItSupport.count("instance_retirement", two.tenant()), "no record written");

        jdbc.update("UPDATE sm_data.apply_schedule SET state = 'CANCELLED', cancelled_by = :by, resolved_at = :at"
                + " WHERE tenant_id = :t AND schedule_id = :s", row);
        assertEquals(1, inventory.retirePlacement(two.tenant(), two.instance(), two.second(), PlacementItSupport.ACTOR,
                CORRELATION).placements().size());
    }

    /** A pending schedule on the OTHER placement does not hold this one. */
    @Test
    void aPendingScheduleOnAnotherPlacementDoesNotHoldIt() {
        Two two = two();
        jdbc.update("INSERT INTO sm_data.apply_schedule (tenant_id, schedule_id, instance_id, placement_id, operation,"
                + " generation, strategy, not_before, not_after, requested_by, requested_at)"
                + " VALUES (:t, :s, :i, :p, 'apply', 9, 'manual', :nb, :na, :by, :at)",
                new MapSqlParameterSource().addValue("t", two.tenant()).addValue("s", UUID.randomUUID())
                        .addValue("i", two.instance()).addValue("p", two.declared().placement())
                        .addValue("by", UUID.randomUUID()).addValue("at", OffsetDateTime.now())
                        .addValue("nb", OffsetDateTime.now().plusHours(1)).addValue("na", OffsetDateTime.now().plusHours(2)));

        assertEquals(1, inventory.retirePlacement(two.tenant(), two.instance(), two.second(), PlacementItSupport.ACTOR,
                CORRELATION).placements().size());
    }

    @Test
    void aFleetMemberThatReservesThePlacementHoldsItUntilItSettles() {
        Two two = two();
        UUID fleet = fleetMember(two.tenant(), two.instance(), two.second());

        assertThrows(PlacementInFlightException.class, () -> inventory.retirePlacement(two.tenant(), two.instance(),
                two.second(), PlacementItSupport.ACTOR, CORRELATION), "pending");

        moveMember(two.tenant(), fleet, "UPDATE sm_data.fleet_member SET disposition = 'admitted'"
                + " WHERE tenant_id = :t AND fleet_id = :f");
        assertThrows(PlacementInFlightException.class, () -> inventory.retirePlacement(two.tenant(), two.instance(),
                two.second(), PlacementItSupport.ACTOR, CORRELATION), "admitted, not yet settled");

        moveMember(two.tenant(), fleet, "UPDATE sm_data.fleet_member SET settled = 'succeeded'"
                + " WHERE tenant_id = :t AND fleet_id = :f");
        assertEquals(1, inventory.retirePlacement(two.tenant(), two.instance(), two.second(), PlacementItSupport.ACTOR, CORRELATION)
                .placements().size(), "settled: it holds nothing");
    }

    @Test
    void theFreedContainerNameDirectoryAndPortsCanBeAddedAgain() {
        Two two = two();
        inventory.retirePlacement(two.tenant(), two.instance(), two.second(), PlacementItSupport.ACTOR, CORRELATION);

        // the very same container name, config directory, health port AND metrics host port, on the same target
        InstanceView again = inventory.addPlacement(two.tenant(), two.instance(), new AddPlacementCommand(
                two.secondTarget(), two.secondSpec(), PlacementItSupport.ACTOR,
                PlacementItSupport.declaration(two.secondMetricsPort())));

        assertEquals(2, again.placements().size());
        assertFalse(again.placements().get(1).placementId().equals(two.second()), "a NEW placement: the old row stays retired");
        assertEquals(3, PlacementItSupport.count("placement", two.tenant()));
    }

    // ---- the ladder ----------------------------------------------------------------------------------------

    @Test
    void retiringThePlacementThatLaggedRaisesTheLadderAndAnnouncesIt() {
        Declared d = PlacementItSupport.atRestartObserved();
        Two two = two(d);
        assertEquals("rendered", PlacementItSupport.storedRung(d.tenant(), d.instance()),
                "precondition: the add dropped the rung, as OQ-8.10 (a) says");
        int announced = PlacementItSupport.configStates(d.tenant()).size();

        inventory.retirePlacement(d.tenant(), d.instance(), two.second(), PlacementItSupport.ACTOR, CORRELATION);

        assertEquals("restart-observed", PlacementItSupport.storedRung(d.tenant(), d.instance()),
                "the remaining placement is the instance's lowest rung again");
        List<EventEnvelope> states = PlacementItSupport.configStates(d.tenant());
        assertEquals(announced + 1, states.size());
        assertEquals(Map.of("reached", "restart-observed"), states.get(states.size() - 1).payload());
    }

    // ---- the lock ------------------------------------------------------------------------------------------

    /**
     * Retire and add serialize on the instance's {@code apply_scope} row, then on the instance row. The writer holds the
     * instance row {@code FOR NO KEY UPDATE} (see {@link PlacementItSupport.LifecycleWriter}: {@code FOR UPDATE} would also
     * block the foreign-key {@code KEY SHARE} of the scope row's insert, and this test could not tell "took the instance
     * lock" from "blocked on a foreign key"), and the scope row is created first for the same reason (W2 review W2A-4).
     * What this proves is the ordering and the liveness of two operations queued behind one writer; that retire takes the
     * instance lock AT ALL is proved by {@link #retireWaitsForALifecycleWriterAndRefusesIfTheInstanceLeftActive}, which
     * separates it from the stamp's own backstop by the exception type.
     */
    @Test
    void retireAndAddQueueBehindOneInstanceWriterAndBothLand() throws Exception {
        Two two = two();
        PlacementItSupport.ensureScopeRow(two.tenant(), two.instance());
        try (PlacementItSupport.LifecycleWriter writer =
                new PlacementItSupport.LifecycleWriter(two.tenant(), two.instance(), false)) {
            Future<InstanceView> retire = pool.submit(() -> inventory.retirePlacement(two.tenant(), two.instance(),
                    two.second(), PlacementItSupport.ACTOR, CORRELATION));
            PlacementItSupport.awaitLockWaiters(1);
            Future<InstanceView> add = pool.submit(() -> inventory.addPlacement(two.tenant(), two.instance(),
                    new AddPlacementCommand(InventoryFixtures.target(two.tenant()),
                            PlacementItSupport.freshSpec(PlacementItSupport.freshPort()), PlacementItSupport.ACTOR,
                            PlacementItSupport.declaration(PlacementItSupport.freshPort()))));
            PlacementItSupport.awaitLockWaiters(2);
            assertEquals("live", retiredAt(two.tenant(), two.second()), "nothing has been written while the writer holds");

            writer.commit();

            retire.get(15, TimeUnit.SECONDS);
            add.get(15, TimeUnit.SECONDS);
        }
        assertEquals(2, PlacementItSupport.livePlacements(two.tenant(), two.instance()), "P1 + the added one");
    }

    /**
     * W2 review W2A-4: the proof that retire takes the instance row lock. A writer flips the instance to {@code abandoned}
     * and holds; retire must wait, then refuse CLEANLY as {@link InstanceNotActiveException}. With the lock removed from
     * {@code retirePlacement} the read would see {@code active}, the retirement would be written, and the stamp's own
     * {@code lifecycle = 'active'} predicate would refuse it as an {@link IllegalStateException}: the exception TYPE is
     * what tells the lock from the backstop.
     */
    @Test
    void retireWaitsForALifecycleWriterAndRefusesIfTheInstanceLeftActive() throws Exception {
        Two two = two();
        PlacementItSupport.ensureScopeRow(two.tenant(), two.instance());
        try (PlacementItSupport.LifecycleWriter writer =
                new PlacementItSupport.LifecycleWriter(two.tenant(), two.instance(), true)) {
            Future<InstanceView> retire = pool.submit(() -> inventory.retirePlacement(two.tenant(), two.instance(),
                    two.second(), PlacementItSupport.ACTOR, CORRELATION));
            PlacementItSupport.awaitLockWaiters(1);
            assertThrows(TimeoutException.class, () -> retire.get(300, TimeUnit.MILLISECONDS),
                    "retire is WAITING on the instance row");
            assertEquals("live", retiredAt(two.tenant(), two.second()));

            writer.commit();

            ExecutionException refused = assertThrows(ExecutionException.class, () -> retire.get(15, TimeUnit.SECONDS));
            assertInstanceOf(InstanceNotActiveException.class, refused.getCause(),
                    "decided under the lock, before any write, not by the stamp's backstop");
        }
        assertEquals("live", retiredAt(two.tenant(), two.second()), "nothing was retired on an instance that left active");
    }

    @Test
    void twoRetirementsRacingForTheLastTwoPlacementsHaveExactlyOneWinner() throws Exception {
        Two two = two();
        List<Future<InstanceView>> results = pool.invokeAll(List.<Callable<InstanceView>>of(
                () -> inventory.retirePlacement(two.tenant(), two.instance(), two.second(), PlacementItSupport.ACTOR, CORRELATION),
                () -> inventory.retirePlacement(two.tenant(), two.instance(), two.declared().placement(),
                        PlacementItSupport.ACTOR, CORRELATION)), 30, TimeUnit.SECONDS);

        int succeeded = 0;
        int refused = 0;
        for (Future<InstanceView> result : results) {
            try {
                result.get();
                succeeded++;
            } catch (ExecutionException e) {
                assertInstanceOf(LastLivePlacementException.class, e.getCause(), "the loser, and only that: " + e);
                refused++;
            }
        }
        assertEquals(1, succeeded);
        assertEquals(1, refused);
        assertEquals(1, PlacementItSupport.livePlacements(two.tenant(), two.instance()),
                "never zero: an instance with no live placement is undeclare's business");
    }
}
