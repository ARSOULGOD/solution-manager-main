package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.apply.ApplyAcceptedView;
import com.solace.sm.dp.api.apply.ApplyObservationView;
import com.solace.sm.dp.api.apply.ApplyRefusedException;
import com.solace.sm.dp.api.apply.ApplyService;
import com.solace.sm.dp.api.apply.NotImplementedStrategyException;
import com.solace.sm.dp.api.apply.ObserveQuery;
import com.solace.sm.dp.api.apply.StopCommand;
import com.solace.sm.dp.api.apply.SubmitApplyCommand;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.apply.service.ApplyServiceImpl;
import com.solace.sm.dp.inventory.PlacementRows;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T6.3 at the data plane: {@code submitApply}, {@code stopInstance} and {@code observeApply} over a real
 * PostgreSQL, a real {@link ApplyExecutor} (admission, outcomes, intents) and an in-memory adapter that counts every
 * call it receives. Lives in this package for the engine harness it shares with the other journal ITs.
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyServiceIT {

    private static final String DIGEST = "hmacsha256v1:" + "c".repeat(64);
    private static final UUID ACTOR = UUID.randomUUID();
    private static final RenderedConfig CONFIG = TestHashers.rendered("generation: 1\n".getBytes(StandardCharsets.UTF_8));

    private static NamedParameterJdbcTemplate superuser;
    private static PlacementRows placementRows;
    private static JdbcApplyObservationRepository observations;

    private UUID tenant;
    private UUID instance;
    private UUID placement;
    private InMemoryRuntimeAdapter adapter;
    private ApplyExecutor executor;
    private AtomicInteger nudges;
    private ApplyService service;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
        placementRows = new JdbcPlacementRepository(SmDataPostgres.runtimeJdbc());
        observations = new JdbcApplyObservationRepository(SmDataPostgres.runtimeJdbc());
    }

    @BeforeEach
    void declare() {
        tenant = SmDataPostgres.freshTenant();
        instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        placement = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        adapter = new InMemoryRuntimeAdapter(new PlacementRef(new TenantId(tenant),
                new com.solace.sm.dp.apply.model.InstanceId(instance), new PlacementId(placement)), true,
                "replica-1");
        executor = ApplyEngineHarness.executor(adapter, (p, g) -> CONFIG);
        nudges = new AtomicInteger();
        com.solace.sm.dp.config.ConfigRecordServiceImpl config = new com.solace.sm.dp.config.ConfigRecordServiceImpl(
                ApplyEngineHarness.transactions(), new JdbcConfigGenerationRepository(SmDataPostgres.runtimeJdbc()),
                TestHashers.keyed(), new com.solace.sm.dp.config.Retention(10), java.time.Clock.systemUTC(),
                new JdbcRollbackTargetRepository(SmDataPostgres.runtimeJdbc()));
        service = new ApplyServiceImpl(() -> executor, placementRows, ApplyEngineHarness.requests(), observations,
                config, new com.solace.sm.dp.config.RollbackTargets(new JdbcRollbackTargetRepository(SmDataPostgres.runtimeJdbc())),
                nudges::incrementAndGet);
    }

    private void generation(UUID forInstance, long g) {
        superuser.update("INSERT INTO sm_data.config_generation (tenant_id, instance_id, generation, package_solution,"
                        + " package_version, component_selection, draft_values, rendered_entries, content_digest,"
                        + " origin, created_by, created_at) VALUES (:t, :i, :g, 'cache', '2.4', '{}'::jsonb,"
                        + " '{}'::jsonb, '[]'::jsonb, :d, 'edit', :u, now())",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", forInstance).addValue("g", g)
                        .addValue("d", DIGEST + g).addValue("u", UUID.randomUUID()));
        superuser.update("UPDATE sm_data.instance_state SET last_generation = :g WHERE tenant_id = :t"
                        + " AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", forInstance).addValue("g", g));
    }

    private SubmitApplyCommand manual(UUID placementId, long generation) {
        return new SubmitApplyCommand(placementId, generation, "manual", null, ACTOR);
    }

    private int requests() {
        return superuser.queryForObject("SELECT count(*)::int FROM sm_data.apply_request WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Integer.class);
    }

    private String column(UUID applyId, String column) {
        return superuser.queryForObject("SELECT " + column + "::text FROM sm_data.apply_request WHERE apply_id = :a",
                new MapSqlParameterSource("a", applyId), String.class);
    }

    // ------------------------------------------------------------------------------------------------ submit

    @Test
    void aSubmissionIsDurableAsRequestedBeforeAnyAdapterCallAndNudgesTheWorkerAfterwards() {
        generation(instance, 1);

        ApplyAcceptedView accepted = service.submitApply(tenant, instance, manual(placement, 1));

        assertEquals("REQUESTED", column(accepted.applyId(), "state"));
        assertEquals("apply", column(accepted.applyId(), "operation"));
        assertEquals(ACTOR.toString(), column(accepted.applyId(), "requested_by"), "the actor is the caller's principal");
        assertEquals(column(accepted.applyId(), "state_version"), String.valueOf(accepted.stateVersion()),
                "the answer carries the request's own stamp");
        assertEquals(0, adapter.prepareCalls() + adapter.materializeCalls() + adapter.restarts(),
                "admission never touches the adapter");
        assertEquals(1, nudges.get(), "the worker is nudged once, after the admission commits");
    }

    @Test
    void aSubmissionUnderAPreMintedIdIsIdempotentInFlightAndOnceLandedAndNudgesTheWorkerOnce() {
        // OR-125 (5): a retry after a crash between this admission and the caller's own record is told "that one".
        generation(instance, 1);
        UUID reserved = UUID.randomUUID();

        ApplyAcceptedView first = service.submitApply(tenant, instance, manual(placement, 1), reserved);
        ApplyAcceptedView whileInFlight = service.submitApply(tenant, instance, manual(placement, 1), reserved);
        ApplyEngineHarness.outcomes().start(new TenantId(tenant), new ApplyId(reserved));
        executor.runAdmitted(new TenantId(tenant), new ApplyId(reserved));
        ApplyAcceptedView afterItLanded = service.submitApply(tenant, instance, manual(placement, 1), reserved);

        assertEquals(reserved, first.applyId());
        assertEquals(reserved, whileInFlight.applyId(), "in flight: not SCOPE_BUSY");
        assertEquals(reserved, afterItLanded.applyId(), "landed: not GENERATION_NOT_ABOVE_WATERMARK");
        assertEquals(1, requests(), "one request");
        assertEquals(1, nudges.get(), "the worker was nudged by the admission, not by the answers that wrote nothing");
    }

    @Test
    void anAbsentForeignRetiredOrOtherTenantsPlacementIsTheSameNotFoundAndWritesNothing() {
        generation(instance, 1);
        UUID otherInstance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, otherInstance);
        UUID foreign = InventoryFixtures.placement(tenant, otherInstance, InventoryFixtures.target(tenant));
        UUID otherTenant = SmDataPostgres.freshTenant();
        UUID otherTenantInstance = UUID.randomUUID();
        InventoryFixtures.declare(otherTenant, otherTenantInstance);
        UUID theirs = InventoryFixtures.placement(otherTenant, otherTenantInstance, InventoryFixtures.target(otherTenant));
        UUID retired = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        superuser.update("UPDATE sm_data.placement SET retired_at = now() WHERE placement_id = :p",
                new MapSqlParameterSource("p", retired));

        for (UUID candidate : List.of(UUID.randomUUID(), foreign, theirs, retired)) {
            ApplyRefusedException refused = assertThrows(ApplyRefusedException.class,
                    () -> service.submitApply(tenant, instance, manual(candidate, 1)));
            assertEquals(ApplyRefusedException.Reason.NOT_FOUND, refused.reason());
        }
        assertEquals(0, requests());
        assertEquals(0, nudges.get());
    }

    @Test
    void aGenerationThatWasNeverStagedIsRefusedAsUnstagedAndNothingIsWritten() {
        generation(instance, 1);

        ApplyRefusedException refused = assertThrows(ApplyRefusedException.class,
                () -> service.submitApply(tenant, instance, manual(placement, 2)));

        assertEquals(ApplyRefusedException.Reason.GENERATION_NOT_STAGED, refused.reason());
        assertEquals(0, requests());
        assertEquals(0, nudges.get());
    }

    @Test
    void anUndeclaredOrNotYetActiveInstanceIsNotFound() {
        UUID pending = UUID.randomUUID();
        InventoryFixtures.declare(tenant, pending, "pending");

        assertEquals(ApplyRefusedException.Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class,
                () -> service.submitApply(tenant, UUID.randomUUID(), manual(placement, 1))).reason());
        assertEquals(ApplyRefusedException.Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class,
                () -> service.submitApply(tenant, pending, manual(placement, 1))).reason());
    }

    @Test
    void onlyScheduledIsStillNotImplementedAndWritesNothing() {
        generation(instance, 1);
        assertThrows(NotImplementedStrategyException.class, () -> service.submitApply(tenant, instance,
                new SubmitApplyCommand(placement, 1, "scheduled", null, ACTOR)), "scheduled (plan 012 T7 builds it)");
        assertEquals(0, requests());
    }

    @Test
    void theTwoUngatedStrategiesAreServedAndTheOutageConfirmationStoredIsTheCommandsNeverATypedName() {
        // Plan 012 T2 (OPS-Q7 C-2): restart-all and rolling-no-validation are admitted. The DP stores exactly the
        // confirmation the gateway hands it (the principal id); a strategy that asserts no outage stores none.
        generation(instance, 1);
        UUID second = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));

        ApplyAcceptedView noValidation = service.submitApply(tenant, instance,
                new SubmitApplyCommand(placement, 1, "rolling-no-validation", null, ACTOR));
        assertEquals("REQUESTED", column(noValidation.applyId(), "state"));
        assertEquals("rolling-no-validation", column(noValidation.applyId(), "strategy"));
        assertNull(column(noValidation.applyId(), "outage_confirmed_by"), "rolling-no-validation asserts no outage");

        ApplyAcceptedView all = service.submitApply(tenant, instance,
                new SubmitApplyCommand(second, 1, "restart-all", ACTOR.toString(), ACTOR));
        assertEquals("REQUESTED", column(all.applyId(), "state"));
        assertEquals("restart-all", column(all.applyId(), "strategy"));
        assertEquals(ACTOR.toString(), column(all.applyId(), "outage_confirmed_by"),
                "the principal id the gateway wrote, on apply_request");
        assertEquals(2, requests());
    }

    @Test
    void anUnknownStrategyIsAnIllegalArgumentNotAnImplementationGap() {
        generation(instance, 1);

        assertThrows(IllegalArgumentException.class, () -> service.submitApply(tenant, instance,
                new SubmitApplyCommand(placement, 1, "yolo", null, ACTOR)));
        assertEquals(0, requests());
    }

    @Test
    void rollingHealthGatedIsAdmittedWithItsStrategyRecorded() {
        generation(instance, 1);

        ApplyAcceptedView accepted = service.submitApply(tenant, instance,
                new SubmitApplyCommand(placement, 1, "rolling-health-gated", null, ACTOR));

        assertEquals("rolling-health-gated", column(accepted.applyId(), "strategy"));
        assertEquals("REQUESTED", column(accepted.applyId(), "state"));
    }

    @Test
    void aSecondApplyOnTheSameScopeIsRefusedAtAdmissionNotQueued() {
        generation(instance, 1);
        service.submitApply(tenant, instance, manual(placement, 1));
        generation(instance, 2);

        ApplyRefusedException refused = assertThrows(ApplyRefusedException.class,
                () -> service.submitApply(tenant, instance, manual(placement, 2)));

        assertEquals(ApplyRefusedException.Reason.SCOPE_BUSY, refused.reason());
        assertEquals(1, requests());
    }

    // -------------------------------------------------------------------------------------------------- stop

    @Test
    void anOmittedPlacementStopsTheWholeInstanceUnderOneApplyIdWithNoGenerationOrStrategy() {
        ApplyAcceptedView accepted = service.stopInstance(tenant, instance, new StopCommand(null, ACTOR));

        assertEquals("stop", column(accepted.applyId(), "operation"));
        assertEquals(PlacementId.INSTANCE_WIDE.value().toString(), column(accepted.applyId(), "placement_id"));
        assertNull(superuser.queryForObject("SELECT generation FROM sm_data.apply_request WHERE apply_id = :a",
                new MapSqlParameterSource("a", accepted.applyId()), Long.class));
        assertNull(column(accepted.applyId(), "strategy"));
        assertEquals(ACTOR.toString(), column(accepted.applyId(), "requested_by"));
        assertEquals(1, nudges.get());
    }

    @Test
    void aPlacementTargetedStopAddressesThatPlacementOnly() {
        ApplyAcceptedView accepted = service.stopInstance(tenant, instance, new StopCommand(placement, ACTOR));

        assertEquals(placement.toString(), column(accepted.applyId(), "placement_id"));
    }

    @Test
    void aForeignOrAbsentPlacementOnAStopIsNotFound() {
        UUID otherInstance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, otherInstance);
        UUID foreign = InventoryFixtures.placement(tenant, otherInstance, InventoryFixtures.target(tenant));

        for (UUID candidate : List.of(UUID.randomUUID(), foreign)) {
            assertEquals(ApplyRefusedException.Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class,
                    () -> service.stopInstance(tenant, instance, new StopCommand(candidate, ACTOR))).reason());
        }
        assertEquals(0, requests());
    }

    @Test
    void aSecondStopInFlightOnTheSameScopeIsRefusedAndEachAdmittedStopIsOneJournalRow() {
        service.stopInstance(tenant, instance, new StopCommand(null, ACTOR));

        assertEquals(ApplyRefusedException.Reason.STOP_IN_FLIGHT, assertThrows(ApplyRefusedException.class,
                () -> service.stopInstance(tenant, instance, new StopCommand(null, ACTOR))).reason());
        assertEquals(1, requests(), "the journal row IS the audit record: one admitted stop, one row");
    }

    @Test
    void stoppingAnUndeclaredInstanceIsNotFound() {
        assertEquals(ApplyRefusedException.Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class,
                () -> service.stopInstance(tenant, UUID.randomUUID(), new StopCommand(null, ACTOR))).reason());
    }

    // ------------------------------------------------------------------------------------------------ observe

    /** Claims and runs a submitted apply to its terminal landing through the engine, as the worker does. */
    private void run(ApplyAcceptedView accepted) {
        TenantId t = new TenantId(tenant);
        ApplyId id = new ApplyId(accepted.applyId());
        ApplyEngineHarness.outcomes().start(t, id);
        executor.runAdmitted(t, id);
    }

    @Test
    void observeAnswersFromTheJournalWithTheStepsTheEngineJournaled() {
        generation(instance, 1);
        ApplyAcceptedView accepted = service.submitApply(tenant, instance, manual(placement, 1));

        ApplyObservationView before = service.observeApply(tenant, instance,
                new ObserveQuery(accepted.applyId(), null, 50)).get(0);
        assertEquals("REQUESTED", before.state());
        assertFalse(before.terminal());
        assertTrue(before.steps().isEmpty(), "nothing has run: no intent exists yet");

        run(accepted);
        ApplyObservationView after = service.observeApply(tenant, instance,
                new ObserveQuery(accepted.applyId(), null, 50)).get(0);

        assertEquals("STAGED", after.state());
        assertTrue(after.terminal());
        assertEquals("apply", after.operation());
        assertEquals(Long.valueOf(1), after.generation());
        assertEquals(placement, after.placementId());
        assertTrue(after.stateVersion() > before.stateVersion(), "a transition draws a higher stamp");
        assertFalse(after.steps().isEmpty());
        assertTrue(after.steps().stream().allMatch(s -> s.outcome().equals("succeeded")),
                after.steps().toString());
        assertTrue(after.steps().stream().anyMatch(s -> s.step().equals("preflight")));
        assertTrue(after.steps().stream().anyMatch(s -> s.step().equals("materialize")));
    }

    @Test
    void observeIsIdempotentAndWritesNothingAndCallsNoAdapter() {
        generation(instance, 1);
        ApplyAcceptedView accepted = service.submitApply(tenant, instance, manual(placement, 1));
        run(accepted);
        int calls = adapter.prepareCalls() + adapter.materializeCalls() + adapter.assessCalls() + adapter.restarts();
        String stamp = column(accepted.applyId(), "state_version");

        ApplyObservationView first = service.observeApply(tenant, instance, new ObserveQuery(accepted.applyId(), null, 50)).get(0);
        ApplyObservationView second = service.observeApply(tenant, instance, new ObserveQuery(accepted.applyId(), null, 50)).get(0);

        assertEquals(first, second);
        assertEquals(stamp, column(accepted.applyId(), "state_version"), "observing writes nothing");
        assertEquals(calls, adapter.prepareCalls() + adapter.materializeCalls() + adapter.assessCalls() + adapter.restarts());
    }

    @Test
    void sinceStateVersionReturnsStrictlyNewerRowsAscendingBoundedByLimit() {
        generation(instance, 1);
        ApplyAcceptedView first = service.submitApply(tenant, instance, manual(placement, 1));
        run(first);
        UUID second = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        ApplyAcceptedView other = service.submitApply(tenant, instance, manual(second, 1));
        ApplyAcceptedView stop = service.stopInstance(tenant, instance, new StopCommand(second, ACTOR));

        List<ApplyObservationView> all = service.observeApply(tenant, instance, new ObserveQuery(null, null, 50));
        assertEquals(3, all.size());
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1).stateVersion() < all.get(i).stateVersion(), "ascending by stateVersion");
        }

        long watermark = all.get(0).stateVersion();
        List<ApplyObservationView> newer = service.observeApply(tenant, instance, new ObserveQuery(null, watermark, 50));
        assertEquals(2, newer.size());
        assertTrue(newer.stream().allMatch(v -> v.stateVersion() > watermark), "strictly newer");

        List<ApplyObservationView> bounded = service.observeApply(tenant, instance, new ObserveQuery(null, null, 1));
        assertEquals(1, bounded.size());
        assertEquals(all.get(0).applyId(), bounded.get(0).applyId(), "the bound keeps the OLDEST rows, so a backfill advances");
        assertNotNull(other);
        assertNotNull(stop);
    }

    @Test
    void anApplyIdOfAnotherInstanceOrTenantIsNotFoundAndNeverLeaks() {
        generation(instance, 1);
        ApplyAcceptedView mine = service.submitApply(tenant, instance, manual(placement, 1));
        UUID otherInstance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, otherInstance);
        UUID otherTenant = SmDataPostgres.freshTenant();
        InventoryFixtures.declare(otherTenant, instance);

        assertEquals(ApplyRefusedException.Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class,
                () -> service.observeApply(tenant, otherInstance, new ObserveQuery(mine.applyId(), null, 50))).reason());
        assertEquals(ApplyRefusedException.Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class,
                () -> service.observeApply(otherTenant, instance, new ObserveQuery(mine.applyId(), null, 50))).reason());
        assertTrue(service.observeApply(tenant, otherInstance, new ObserveQuery(null, null, 50)).isEmpty());
        assertTrue(service.observeApply(otherTenant, instance, new ObserveQuery(null, null, 50)).isEmpty());
    }

    @Test
    void aStopsOwnRequestIsObservableByItsApplyId() {
        ApplyAcceptedView stop = service.stopInstance(tenant, instance, new StopCommand(null, ACTOR));

        ApplyObservationView view = service.observeApply(tenant, instance, new ObserveQuery(stop.applyId(), null, 50)).get(0);

        assertEquals("stop", view.operation());
        assertEquals("REQUESTED", view.state());
        assertFalse(view.terminal());
        assertNull(view.generation());
        assertNull(view.placementId(), "instance-wide: no single placement");
    }
}
