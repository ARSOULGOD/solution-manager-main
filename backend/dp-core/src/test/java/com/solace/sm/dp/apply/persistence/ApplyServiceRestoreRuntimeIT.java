package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.apply.ApplyAcceptedView;
import com.solace.sm.dp.api.apply.ApplyObservationView;
import com.solace.sm.dp.api.apply.ApplyRefusedException;
import com.solace.sm.dp.api.apply.ApplyRefusedException.Reason;
import com.solace.sm.dp.api.apply.NotImplementedStrategyException;
import com.solace.sm.dp.api.apply.ObserveQuery;
import com.solace.sm.dp.api.apply.RestoreRuntimeCommand;
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
 * Plan 010 T10.2 through the data plane's apply service (OR-45, {@code B/D12}'s manual drift reconcile): a restore is a
 * copy-forward of the CURRENT DESIRED generation as a NEW higher one, then a normal apply of it. Real PostgreSQL, the
 * real config of record, the real engine and an in-memory host. Admission checks the watermark, so the copy being a
 * higher number is what makes it applicable to a placement that already holds the desired generation.
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyServiceRestoreRuntimeIT {

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

    private RestoreRuntimeCommand restoreManual() {
        return new RestoreRuntimeCommand(placement, "manual", null, ACTOR);
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
    void restoreReappliesTheDesiredContentUnderANewGeneration() {
        stage("first");
        stage("second");
        applied(1);              // the host holds generation 1; the desired generation is 2, staged and never applied

        ApplyAcceptedView accepted = service.restoreRuntime(tenant, instance, restoreManual());

        ApplyObservationView before = observed(accepted);
        assertEquals(Long.valueOf(3), before.generation(), "the apply is of the NEW generation");
        assertEquals(3L, generations());
        assertTrue(sameContent(2, 3), "generation 3 carries the DESIRED generation 2's content");
        assertFalse(sameContent(1, 3), "...not the latest applied generation 1's");
        assertEquals("restore-runtime", column(3, "origin"));
        assertEquals("2", column(3, "derived_from_generation"));
        run(accepted);
        assertEquals("STAGED", observed(accepted).state(), "the default manual strategy ends staged");
    }

    @Test
    void theTwoUngatedStrategiesAreServedOnARestoreAndTheConfirmationStoredIsTheCommandsOwn() {
        // Plan 012 T2 (OPS-Q7 C-2)
        stage("first");
        applied(1);

        ApplyAcceptedView noValidation = service.restoreRuntime(tenant, instance,
                new RestoreRuntimeCommand(placement, "rolling-no-validation", null, ACTOR));
        assertEquals("rolling-no-validation", requestColumn(noValidation.applyId(), "strategy"));
        assertNull(requestColumn(noValidation.applyId(), "outage_confirmed_by"));
        run(noValidation);

        ApplyAcceptedView all = service.restoreRuntime(tenant, instance,
                new RestoreRuntimeCommand(placement, "restart-all", ACTOR.toString(), ACTOR));
        assertEquals("restart-all", requestColumn(all.applyId(), "strategy"));
        assertEquals(ACTOR.toString(), requestColumn(all.applyId(), "outage_confirmed_by"));
    }

    private String requestColumn(UUID applyId, String column) {
        return superuser.queryForObject("SELECT " + column + "::text FROM sm_data.apply_request WHERE apply_id = :a",
                new MapSqlParameterSource("a", applyId), String.class);
    }

    @Test
    void theDesiredGenerationsOwnNumberCannotBeResubmittedWhichIsWhyTheCopyIsHigher() {
        stage("first");
        applied(1);

        assertRefused(Reason.GENERATION_NOT_ABOVE_WATERMARK, () -> service.submitApply(tenant, instance,
                new SubmitApplyCommand(placement, 1, "manual", null, ACTOR)));
    }

    @Test
    void aRestoreOfAnAlreadyAppliedDesiredGenerationStillMintsANewOneEvenThoughTheContentIsEqual() {
        stage("only");
        applied(1);              // the host drifts after this; the record does not change

        ApplyAcceptedView accepted = service.restoreRuntime(tenant, instance, restoreManual());

        assertEquals(Long.valueOf(2), observed(accepted).generation(),
                "OR-33 guard 1 answers an edit that changes nothing; a restore must still be applicable");
        assertEquals(2L, generations());
        assertTrue(sameContent(1, 2));
    }

    @Test
    void theCopyForwardAndTheSubmitAreSeparateTransactions() {
        stage("first");
        stage("second");
        applied(1);
        long generations = generations();
        long requests = requests();

        // an outer transaction that is then rolled back: neither half may have joined it
        ApplyAcceptedView[] accepted = new ApplyAcceptedView[1];
        ApplyEngineHarness.transactions().executeWithoutResult(status -> {
            accepted[0] = service.restoreRuntime(tenant, instance, restoreManual());
            status.setRollbackOnly();
        });

        assertEquals(generations + 1, generations(), "the copy-forward committed in a transaction of its own");
        assertEquals(requests + 1, requests(), "so did the admission, in another");
        assertEquals("REQUESTED", observed(accepted[0]).state());
    }

    @Test
    void aRetryAfterACrashAdoptsTheUnappliedCopyAndASecondRestoreAfterItsApplyMintsAFreshOne() {
        stage("first");
        applied(1);
        // the crash window: the copy-forward committed, the apply was never admitted
        config.copyLatestForward(tenant, instance, GenerationOrigin.RESTORE_RUNTIME, ACTOR).orElseThrow();
        assertEquals(2L, generations());

        ApplyAcceptedView first = service.restoreRuntime(tenant, instance, restoreManual());

        assertEquals(Long.valueOf(2), observed(first).generation(), "the retry applies the copy that was left");
        assertEquals(2L, generations(), "no second restore generation");
        run(first);

        // the host drifts again: that copy is now named by an apply request, so this is a NEW restore
        ApplyAcceptedView second = service.restoreRuntime(tenant, instance, restoreManual());

        assertEquals(Long.valueOf(3), observed(second).generation());
        assertEquals(3L, generations());
    }

    @Test
    void aRestoreRefusedForABusyScopeMintsNothingAndALaterOneAfterItsApplyMintsOne() {
        stage("first");
        applied(1);
        ApplyAcceptedView first = service.restoreRuntime(tenant, instance, restoreManual());   // holds the scope
        assertEquals(2L, generations());

        // S811-2: the scope check runs BEFORE the copy, so a refusal leaves the desired configuration untouched
        assertRefused(Reason.SCOPE_BUSY, () -> service.restoreRuntime(tenant, instance, restoreManual()));
        assertEquals(2L, generations(), "the refused attempt minted nothing");
        assertRefused(Reason.SCOPE_BUSY, () -> service.restoreRuntime(tenant, instance, restoreManual()));
        assertEquals(2L, generations(), "nor did the next");

        run(first);
        ApplyAcceptedView third = service.restoreRuntime(tenant, instance, restoreManual());
        assertEquals(Long.valueOf(3), observed(third).generation());
        assertEquals(3L, generations());
    }

    @Test
    void anInstanceWithNothingStagedAnAbsentPlacementAndAnUnbuiltStrategyAreRefusedAndMintNothing() {
        assertRefused(Reason.GENERATION_NOT_STAGED, () -> service.restoreRuntime(tenant, instance, restoreManual()));
        stage("first");
        applied(1);
        long generations = generations();

        assertRefused(Reason.NOT_FOUND, () -> service.restoreRuntime(tenant, instance,
                new RestoreRuntimeCommand(UUID.randomUUID(), "manual", null, ACTOR)));
        assertThrows(NotImplementedStrategyException.class, () -> service.restoreRuntime(tenant, instance,
                new RestoreRuntimeCommand(placement, "scheduled", null, ACTOR)), "scheduled is plan 012 T7's");
        assertThrows(ApplyRefusedException.class,
                () -> service.restoreRuntime(UUID.randomUUID(), instance, restoreManual()),
                "another organization's call is a 404");
        assertEquals(generations, generations());
    }

    private String column(long generation, String column) {
        return superuser.queryForObject("SELECT " + column + "::text FROM sm_data.config_generation WHERE tenant_id = :t"
                + " AND instance_id = :i AND generation = :g", key().addValue("g", generation), String.class);
    }
}
