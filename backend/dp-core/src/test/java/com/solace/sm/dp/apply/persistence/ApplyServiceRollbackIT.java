package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.apply.ApplyAcceptedView;
import com.solace.sm.dp.api.apply.ApplyObservationView;
import com.solace.sm.dp.api.apply.ApplyRefusedException;
import com.solace.sm.dp.api.apply.ApplyRefusedException.Reason;
import com.solace.sm.dp.api.apply.NotImplementedStrategyException;
import com.solace.sm.dp.api.apply.ObserveQuery;
import com.solace.sm.dp.api.apply.RollbackCommand;
import com.solace.sm.dp.api.apply.SubmitApplyCommand;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.apply.service.ApplyServiceImpl;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.Retention;
import com.solace.sm.dp.config.RollbackTargets;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T8.1 through the data plane's apply service (OR-42): a rollback is a copy-forward of a prior generation as a
 * NEW higher one, then a normal apply of it. Real PostgreSQL, the real config of record, the real engine and an
 * in-memory host. Admission checks the watermark, so the copy being a higher number is what makes it applicable.
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyServiceRollbackIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\"}]}";
    private static final RenderedConfig CONFIG = TestHashers.rendered("generation: 1\n".getBytes(StandardCharsets.UTF_8));

    private static NamedParameterJdbcTemplate superuser;
    private static JdbcApplyObservationRepository observations;

    private UUID tenant;
    private UUID instance;
    private UUID placement;
    private ConfigRecordServiceImpl config;
    private ApplyExecutor executor;
    private ApplyServiceImpl service;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
        observations = new JdbcApplyObservationRepository(SmDataPostgres.runtimeJdbc());
    }

    @BeforeEach
    void declare() {
        tenant = SmDataPostgres.freshTenant();
        instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        placement = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        InMemoryRuntimeAdapter adapter = new InMemoryRuntimeAdapter(new PlacementRef(new TenantId(tenant),
                new InstanceId(instance), new PlacementId(placement)), true, "replica-1");
        executor = ApplyEngineHarness.executor(adapter, (p, g) -> CONFIG);
        config = new ConfigRecordServiceImpl(ApplyEngineHarness.transactions(),
                new JdbcConfigGenerationRepository(SmDataPostgres.runtimeJdbc()), TestHashers.keyed(),
                new Retention(50), Clock.systemUTC(),
                new JdbcRollbackTargetRepository(SmDataPostgres.runtimeJdbc()));
        service = new ApplyServiceImpl(() -> executor, new JdbcPlacementRepository(SmDataPostgres.runtimeJdbc()),
                ApplyEngineHarness.requests(), observations, config,
                new RollbackTargets(new JdbcRollbackTargetRepository(SmDataPostgres.runtimeJdbc())), () -> { });
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

    private MapSqlParameterSource key() {
        return new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance);
    }

    private long stage(String marker) {
        long base = superuser.queryForObject("SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);
        return config.stage(tenant, instance, new StageCommand(base, "cache", "2.4", SELECTION,
                "{\"cache-1\":{\"marker\":\"" + marker + "\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"" + marker + "\"")),
                GenerationOrigin.EDIT, null, ACTOR)).generation().generation();
    }

    /** Submits and runs a manual apply to its terminal landing, the way the worker would. */
    private ApplyAcceptedView applied(long generation) {
        ApplyAcceptedView accepted = service.submitApply(tenant, instance,
                new SubmitApplyCommand(placement, generation, "manual", null, ACTOR));
        run(accepted);
        return accepted;
    }

    private void run(ApplyAcceptedView accepted) {
        TenantId t = new TenantId(tenant);
        ApplyId id = new ApplyId(accepted.applyId());
        ApplyEngineHarness.outcomes().start(t, id);
        executor.runAdmitted(t, id);
    }

    private RollbackCommand toGeneration(long generation) {
        return new RollbackCommand(placement, null, generation, "manual", null, ACTOR);
    }

    private ApplyObservationView observed(ApplyAcceptedView accepted) {
        return service.observeApply(tenant, instance, new ObserveQuery(accepted.applyId(), null, 50)).get(0);
    }

    private long generations() {
        return superuser.queryForObject("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);
    }

    private long requests() {
        return superuser.queryForObject("SELECT count(*) FROM sm_data.apply_request WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);
    }

    private boolean sameContent(long a, long b) {
        return superuser.queryForObject("SELECT x.component_selection = y.component_selection"
                + " AND x.draft_values = y.draft_values AND x.rendered_entries = y.rendered_entries"
                + " FROM sm_data.config_generation x, sm_data.config_generation y"
                + " WHERE x.tenant_id = :t AND x.instance_id = :i AND x.generation = :a"
                + " AND y.tenant_id = :t AND y.instance_id = :i AND y.generation = :b",
                key().addValue("a", a).addValue("b", b), Boolean.class);
    }

    private static void assertRefused(Reason reason, Runnable call) {
        assertEquals(reason, assertThrows(ApplyRefusedException.class, call::run).reason());
    }

    // ---- tests ------------------------------------------------------------------------------------------------

    @Test
    void rollbackMintsAHigherGenerationWithTheTargetsContent() {
        stage("first");
        stage("second");
        applied(1);
        applied(2);

        ApplyAcceptedView accepted = service.rollbackApply(tenant, instance, toGeneration(1));

        ApplyObservationView before = observed(accepted);
        assertEquals(Long.valueOf(3), before.generation(), "the apply is of the NEW generation, never of 1 again");
        assertEquals("apply", before.operation());
        assertEquals(3L, generations());
        assertTrue(sameContent(1, 3), "generation 3 carries generation 1's content");
        assertEquals("rollback", superuser.queryForObject("SELECT origin FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i AND generation = 3", key(), String.class));
        assertEquals(1L, superuser.queryForObject("SELECT derived_from_generation FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i AND generation = 3", key(), Long.class));
        run(accepted);
        assertEquals("STAGED", observed(accepted).state(), "the manual strategy ends staged");
    }

    @Test
    void aRollbackUnderAPreMintedIdIsIdempotentEvenOnceItHasRunSoARetryAfterACrashFindsItsOwnApply() {
        // OR-125 (5): the schedule's fire pre-mints the id. A retry after a crash between the engine's commit and the
        // schedule's record must be told "that one" - not refused as SCOPE_BUSY while it is in flight, nor as below the
        // watermark once it has landed, and it must not copy the target forward a second time.
        stage("first");
        stage("second");
        applied(1);
        applied(2);
        UUID reserved = UUID.randomUUID();

        ApplyAcceptedView first = service.rollbackApply(tenant, instance, toGeneration(1), reserved);
        assertEquals(reserved, first.applyId());
        long generationsAfterFirst = generations();
        long requestsAfterFirst = requests();

        ApplyAcceptedView whileInFlight = service.rollbackApply(tenant, instance, toGeneration(1), reserved);
        run(first);
        ApplyAcceptedView afterItLanded = service.rollbackApply(tenant, instance, toGeneration(1), reserved);

        assertEquals(reserved, whileInFlight.applyId(), "in flight: not SCOPE_BUSY");
        assertEquals(reserved, afterItLanded.applyId(), "landed: not GENERATION_NOT_ABOVE_WATERMARK");
        assertEquals(generationsAfterFirst, generations(), "the target was copied forward once");
        assertEquals(requestsAfterFirst, requests(), "and exactly one apply exists");
    }

    @Test
    void aPreMintedIdDoesNotAttachToAnotherApplyNorBypassTheFenceForARequestThatDoesNotExist() {
        // Ported from the dropped lane-B duplicate (W5 review, OR-125 (5)): a reserved id answers only a request that EXISTS
        // under it for THIS scope. An id nothing holds gets the ordinary fences, so a pre-minted id is no way past them.
        stage("first");
        stage("second");
        applied(1);
        applied(2);
        ApplyAcceptedView holder = service.rollbackApply(tenant, instance, toGeneration(1));   // another apply holds the scope
        long generations = generations();
        long requests = requests();

        assertRefused(Reason.SCOPE_BUSY,
                () -> service.rollbackApply(tenant, instance, toGeneration(1), UUID.randomUUID()));
        assertRefused(Reason.SCOPE_BUSY, () -> service.submitApply(tenant, instance,
                new SubmitApplyCommand(placement, 2, "manual", null, ACTOR), UUID.randomUUID()));

        assertEquals(generations, generations(), "a refused reserved-id rollback minted nothing");
        assertEquals(requests, requests(), "and wrote no request");
        // another apply's own id is not another scope's to claim: the lookup matches the SCOPE too, so the primary key refuses
        // it as the invariant violation it is - the caller is never handed the other apply's view
        UUID otherPlacement = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        assertThrows(RuntimeException.class, () -> service.rollbackApply(tenant, instance,
                new RollbackCommand(otherPlacement, null, 1L, "manual", null, ACTOR), holder.applyId()));
        assertEquals(requests, requests(), "no second request exists under the other apply's id");
    }

    @Test
    void theOldGenerationNumberCannotBeReadmittedWhichIsWhyTheCopyIsHigher() {
        stage("first");
        stage("second");
        applied(1);
        applied(2);

        assertRefused(Reason.GENERATION_NOT_ABOVE_WATERMARK, () -> service.submitApply(tenant, instance,
                new SubmitApplyCommand(placement, 1, "manual", null, ACTOR)));
    }

    @Test
    void withAnApplyIdTheTargetIsTheHighestEligibleBelowThatApply() {
        stage("first");
        stage("second");
        stage("third");
        applied(1);
        applied(2);
        ApplyAcceptedView three = applied(3);

        ApplyAcceptedView accepted = service.rollbackApply(tenant, instance,
                new RollbackCommand(placement, three.applyId(), null, "manual", null, ACTOR));

        assertEquals(Long.valueOf(4), observed(accepted).generation());
        assertTrue(sameContent(2, 4), "undoing apply 3 lands generation 2's content");
        assertEquals(2L, superuser.queryForObject("SELECT derived_from_generation FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i AND generation = 4", key(), Long.class));
    }

    @Test
    void aGenerationNeverWrittenToThatPlacementIsRefusedAndMintsNothing() {
        stage("first");
        stage("second");
        applied(2);          // generation 1 was staged but never applied to this placement
        long generations = generations();
        long requests = requests();

        assertRefused(Reason.ROLLBACK_TARGET_NOT_ELIGIBLE, () -> service.rollbackApply(tenant, instance, toGeneration(1)));

        assertEquals(generations, generations(), "a refused rollback copies nothing forward");
        assertEquals(requests, requests());
    }

    @Test
    void anAbsentPlacementAndAnUnbuiltStrategyAreRefusedBeforeAnythingIsWritten() {
        stage("first");
        stage("second");
        applied(1);
        applied(2);
        long generations = generations();

        assertRefused(Reason.NOT_FOUND, () -> service.rollbackApply(tenant, instance,
                new RollbackCommand(UUID.randomUUID(), null, 1L, "manual", null, ACTOR)));
        assertThrows(NotImplementedStrategyException.class, () -> service.rollbackApply(tenant, instance,
                new RollbackCommand(placement, null, 1L, "scheduled", null, ACTOR)), "scheduled is plan 012 T7's");
        assertThrows(ApplyRefusedException.class, () -> service.rollbackApply(UUID.randomUUID(), instance,
                toGeneration(1)), "another organization's call is a 404");
        assertEquals(generations, generations());
    }

    @Test
    void theTwoUngatedStrategiesAreServedOnARollbackAndTheConfirmationStoredIsTheCommandsOwn() {
        // Plan 012 T2 (OPS-Q7 C-2): a rollback names restart-all with the principal the gateway wrote - never a body string.
        stage("first");
        stage("second");
        stage("third");
        applied(1);
        applied(2);

        ApplyAcceptedView noValidation = service.rollbackApply(tenant, instance,
                new RollbackCommand(placement, null, 1L, "rolling-no-validation", null, ACTOR));
        assertEquals("rolling-no-validation", requestColumn(noValidation.applyId(), "strategy"));
        assertNull(requestColumn(noValidation.applyId(), "outage_confirmed_by"));
        run(noValidation);

        ApplyAcceptedView all = service.rollbackApply(tenant, instance,
                new RollbackCommand(placement, null, 2L, "restart-all", ACTOR.toString(), ACTOR));
        assertEquals("restart-all", requestColumn(all.applyId(), "strategy"));
        assertEquals(ACTOR.toString(), requestColumn(all.applyId(), "outage_confirmed_by"),
                "the principal id, on apply_request");
    }

    private String requestColumn(UUID applyId, String column) {
        return superuser.queryForObject("SELECT " + column + "::text FROM sm_data.apply_request WHERE apply_id = :a",
                new MapSqlParameterSource("a", applyId), String.class);
    }

    @Test
    void aRetryAfterACrashBetweenTheCopyAndTheSubmitAdoptsTheSameGenerationNeverASecond() {
        stage("first");
        stage("second");
        applied(1);
        applied(2);
        // the crash window: the copy-forward committed, the apply was never admitted
        config.copyForward(tenant, instance, 1, GenerationOrigin.ROLLBACK, ACTOR);
        assertEquals(3L, generations());

        ApplyAcceptedView accepted = service.rollbackApply(tenant, instance, toGeneration(1));

        assertEquals(Long.valueOf(3), observed(accepted).generation(), "the retry applies the copy that was left");
        assertEquals(3L, generations(), "no second rollback generation");
    }

    @Test
    void aRefusedAdmissionLeavesTheCopyAndTheNextRetryAddsNoSecond() {
        stage("first");
        stage("second");
        applied(1);
        applied(2);
        service.rollbackApply(tenant, instance, toGeneration(1));   // admitted, REQUESTED: holds the scope
        assertEquals(3L, generations());

        assertRefused(Reason.SCOPE_BUSY, () -> service.rollbackApply(tenant, instance, toGeneration(1)));

        assertEquals(3L, generations(), "the second attempt found the copy already latest");
    }

    @Test
    void aRollbackRefusedForABusyScopeMintsNoGenerationAndLeavesTheDesiredConfigurationAlone() {
        stage("first");
        stage("second");
        stage("third");
        applied(1);
        applied(2);
        service.submitApply(tenant, instance, new SubmitApplyCommand(placement, 3, "manual", null, ACTOR)); // REQUESTED
        assertEquals(3L, generations());

        // S811-2: the refusal is decided BEFORE the copy, so the head of the desired configuration is not replaced
        assertRefused(Reason.SCOPE_BUSY, () -> service.rollbackApply(tenant, instance, toGeneration(1)));

        assertEquals(3L, generations(), "no rollback generation was minted by a refused rollback");
        assertEquals(3L, superuser.queryForObject("SELECT last_generation FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", key(), Long.class));
        assertRefused(Reason.SCOPE_BUSY, () -> service.restoreRuntime(tenant, instance,
                new com.solace.sm.dp.api.apply.RestoreRuntimeCommand(placement, "manual", null, ACTOR)));
        assertEquals(3L, generations(), "nor does a refused restore");
    }

    @Test
    void aRollbackRefusedForAStopInFlightMintsNoGeneration() {
        stage("first");
        stage("second");
        applied(1);
        applied(2);
        service.stopInstance(tenant, instance, new com.solace.sm.dp.api.apply.StopCommand(placement, ACTOR)); // REQUESTED

        assertRefused(Reason.STOP_IN_FLIGHT, () -> service.rollbackApply(tenant, instance, toGeneration(1)));

        assertEquals(2L, generations());
        assertRefused(Reason.STOP_IN_FLIGHT, () -> service.restoreRuntime(tenant, instance,
                new com.solace.sm.dp.api.apply.RestoreRuntimeCommand(placement, "manual", null, ACTOR)));
        assertEquals(2L, generations());
    }
}
