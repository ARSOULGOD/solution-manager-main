package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.apply.ApplyAcceptedView;
import com.solace.sm.dp.api.apply.RollbackCommand;
import com.solace.sm.dp.api.apply.ScheduleCommand;
import com.solace.sm.dp.api.apply.ScheduleFireResult;
import com.solace.sm.dp.api.apply.ScheduleFireResult.Outcome;
import com.solace.sm.dp.api.apply.ScheduleRefusedException;
import com.solace.sm.dp.api.apply.StopCommand;
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
import com.solace.sm.dp.schedule.ApplyScheduleProperties;
import com.solace.sm.dp.schedule.ApplyScheduleServiceImpl;
import com.solace.sm.dp.schedule.RowScheduleTargets;
import com.solace.sm.dp.schedule.ScheduleApplyIds;
import com.solace.sm.dp.schedule.ScheduleTargets;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code OR-125} (5) reconciliation against the REAL pieces (W5 review W5A-1 and W5A-2): the real config of record, the real
 * engine, the real {@link RowScheduleTargets} adapter and the real schedule repository, where {@code ApplyScheduleServiceIT}
 * uses a fake engine and a hand-written lookup. Two things are measured here that nothing else reaches:
 * <ul>
 *   <li>the adapter's own behaviour - the instance filter ({@code I5}), the tenant scope, the generation filter and the
 *       provenance read for a real copy-forward;</li>
 *   <li>a rollback whose copy-forward minted nothing ({@code OR-33} guard 1: the latest generation already carried the
 *       target's content) and whose fire crashed before the schedule row was marked - reconciled, not wedged.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyScheduleReconcileIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\"}]}";
    private static final RenderedConfig CONFIG = TestHashers.rendered("generation: 1\n".getBytes(StandardCharsets.UTF_8));
    private static final Instant OPENS = Instant.parse("2030-01-02T02:00:00Z");
    private static final Instant CLOSES = Instant.parse("2030-01-02T04:00:00Z");

    private static NamedParameterJdbcTemplate superuser;
    private static NamedParameterJdbcTemplate runtime;
    private static TransactionTemplate tx;

    private UUID tenant;
    private UUID instance;
    private UUID placement;
    private ConfigRecordServiceImpl config;
    private ApplyExecutor executor;
    private ApplyServiceImpl service;
    private RowScheduleTargets realTargets;
    private ApplyScheduleServiceImpl schedules;
    private volatile Instant now;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
        runtime = SmDataPostgres.runtimeJdbc();
        tx = new TransactionTemplate(new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource()));
    }

    @BeforeEach
    void declare() {
        now = Instant.parse("2030-01-01T12:00:00Z");
        tenant = SmDataPostgres.freshTenant();
        instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        placement = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        InMemoryRuntimeAdapter adapter = new InMemoryRuntimeAdapter(new PlacementRef(new TenantId(tenant),
                new InstanceId(instance), new PlacementId(placement)), true, "replica-1");
        executor = ApplyEngineHarness.executor(adapter, (p, g) -> CONFIG);
        config = new ConfigRecordServiceImpl(ApplyEngineHarness.transactions(),
                new JdbcConfigGenerationRepository(runtime), TestHashers.keyed(), new Retention(50), Clock.systemUTC(),
                new JdbcRollbackTargetRepository(runtime));
        RollbackTargets rollbackTargets = new RollbackTargets(new JdbcRollbackTargetRepository(runtime));
        service = new ApplyServiceImpl(() -> executor, new JdbcPlacementRepository(runtime),
                ApplyEngineHarness.requests(), new JdbcApplyObservationRepository(runtime), config, rollbackTargets,
                () -> { });
        realTargets = new RowScheduleTargets(new JdbcInstanceStateRepository(runtime), new JdbcPlacementRepository(runtime),
                new JdbcConfigGenerationRepository(runtime), ApplyEngineHarness.requests(), ApplyEngineHarness.scopes());
        schedules = new ApplyScheduleServiceImpl(tx, new JdbcApplyScheduleRepository(runtime), realTargets, service,
                rollbackTargets, ApplyScheduleProperties.defaults(), new Clock() {
                    @Override public ZoneOffset getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    @Override public Instant instant() {
                        return now;
                    }
                }, UUID::randomUUID);
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

    private void applied(long generation) {
        ApplyAcceptedView accepted = service.submitApply(tenant, instance,
                new SubmitApplyCommand(placement, generation, "manual", null, ACTOR));
        run(accepted);
    }

    private void run(ApplyAcceptedView accepted) {
        TenantId t = new TenantId(tenant);
        ApplyId id = new ApplyId(accepted.applyId());
        ApplyEngineHarness.outcomes().start(t, id);
        executor.runAdmitted(t, id);
    }

    private long generations() {
        return superuser.queryForObject("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);
    }

    private UUID rollbackScheduleTo(long generation) {
        return schedules.schedule(tenant, instance,
                ScheduleCommand.rollback(placement, null, generation, "manual", null, OPENS, CLOSES, ACTOR)).scheduleId();
    }

    private RollbackCommand rollbackCommand(long generation) {
        return new RollbackCommand(placement, null, generation, "manual", null, ACTOR);
    }

    private <T> T column(UUID schedule, String column, Class<T> type) {
        return superuser.queryForObject("SELECT " + column + " FROM sm_data.apply_schedule WHERE tenant_id = :t"
                + " AND schedule_id = :s", new MapSqlParameterSource("t", tenant).addValue("s", schedule), type);
    }

    /**
     * Generations 1 and 2 applied, then a plain edit (generation 3) whose content equals generation 1's and which is never
     * applied: the latest generation carries rollback target 1's content, so OR-33 guard 1 will not mint a copy.
     */
    private void latestEqualsTheTarget() {
        stage("first");
        stage("second");
        applied(1);
        applied(2);
        stage("first");
        assertEquals(3L, generations());
    }

    // ---- W5A-1: a copy-forward that minted nothing ------------------------------------------------------------

    @Test
    void aCrashedRollbackWhoseCopyForwardMintedNothingIsReconciledAndNotWedgedW5A1() {
        latestEqualsTheTarget();
        UUID schedule = rollbackScheduleTo(1);
        // the crash window: the engine admitted the rollback under the schedule's pre-minted id (guard 1 handed back
        // generation 3, an EDIT with no provenance), and the process died before the row was marked
        ApplyAcceptedView crashed = service.rollbackApply(tenant, instance, rollbackCommand(1),
                ScheduleApplyIds.forSchedule(schedule));
        assertEquals(ScheduleApplyIds.forSchedule(schedule), crashed.applyId());
        assertEquals(3L, generations(), "guard 1: no copy was minted");
        assertEquals("PENDING", column(schedule, "state", String.class));

        now = OPENS.plusSeconds(30);
        ScheduleFireResult result = schedules.admit(tenant, schedule);

        assertEquals(Outcome.ADMITTED, result.outcome());
        assertEquals("ADMITTED", column(schedule, "state", String.class));
        assertEquals(ScheduleApplyIds.forSchedule(schedule), column(schedule, "apply_id", UUID.class));
        assertEquals(1L, column(schedule, "target_generation", Long.class), "the target the schedule named");
        assertEquals(3L, column(schedule, "admitted_generation", Long.class), "the generation the engine admitted");
        assertEquals(3L, generations(), "and nothing was admitted or copied a second time");
    }

    @Test
    void aCancelAndAMissOnTheSameCrashedRowReconcileInsteadOfFailingW5A1() {
        latestEqualsTheTarget();
        UUID cancelled = rollbackScheduleTo(1);
        service.rollbackApply(tenant, instance, rollbackCommand(1), ScheduleApplyIds.forSchedule(cancelled));

        ScheduleRefusedException refused = assertThrows(ScheduleRefusedException.class,
                () -> schedules.cancel(tenant, instance, cancelled, ACTOR));

        assertEquals(ScheduleRefusedException.Reason.NOT_PENDING, refused.reason(), "not a 500: the row is reconciled first");
        assertEquals("ADMITTED", column(cancelled, "state", String.class));
        assertEquals(1L, column(cancelled, "target_generation", Long.class));
        assertEquals(3L, column(cancelled, "admitted_generation", Long.class));
    }

    // ---- W5A-2: the production adapter ------------------------------------------------------------------------

    @Test
    void theAdapterFindsTheRequestUnderTheIdWithTheProvenanceOfARealCopyForward() {
        stage("first");
        stage("second");
        applied(1);
        applied(2);
        UUID reserved = UUID.randomUUID();
        ApplyAcceptedView accepted = service.rollbackApply(tenant, instance, rollbackCommand(1), reserved);

        ScheduleTargets.AdmittedRequest found = realTargets.admittedUnder(tenant, instance, reserved).orElseThrow();

        assertEquals(accepted.applyId(), found.applyId());
        assertEquals(placement, found.placementId());
        assertEquals(3L, found.generation(), "the copy-forward the rollback minted");
        assertEquals(Long.valueOf(1), found.derivedFromGeneration(), "read through the config of record: copied from 1");
        assertEquals(superuser.queryForObject("SELECT requested_at FROM sm_data.apply_request WHERE apply_id = :a",
                new MapSqlParameterSource("a", reserved), java.sql.Timestamp.class).toInstant(), found.requestedAt());
    }

    @Test
    void anEditedGenerationHasNoProvenanceAndAGenerationLessRequestIsNotAnAdmittedApply() {
        stage("first");
        UUID reserved = UUID.randomUUID();
        service.submitApply(tenant, instance, new SubmitApplyCommand(placement, 1, "manual", null, ACTOR), reserved);

        ScheduleTargets.AdmittedRequest found = realTargets.admittedUnder(tenant, instance, reserved).orElseThrow();
        assertEquals(1L, found.generation());
        assertNull(found.derivedFromGeneration(), "generation 1 was staged by an edit");

        // a stop carries no generation: the adapter filters it out rather than mapping a null into a long
        ApplyAcceptedView stop = service.stopInstance(tenant, instance, new StopCommand(placement, ACTOR));
        assertTrue(realTargets.admittedUnder(tenant, instance, stop.applyId()).isEmpty());
    }

    @Test
    void theAdapterNeverAnswersForAnotherInstanceAnotherTenantOrAnUnknownIdI5() {
        stage("first");
        UUID reserved = UUID.randomUUID();
        service.submitApply(tenant, instance, new SubmitApplyCommand(placement, 1, "manual", null, ACTOR), reserved);
        UUID otherInstance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, otherInstance);

        assertTrue(realTargets.admittedUnder(tenant, instance, reserved).isPresent(), "control: found where it belongs");
        assertTrue(realTargets.admittedUnder(tenant, otherInstance, reserved).isEmpty(),
                "the same tenant's other instance: the instance filter");
        assertTrue(realTargets.admittedUnder(SmDataPostgres.freshTenant(), instance, reserved).isEmpty(),
                "another tenant");
        assertTrue(realTargets.admittedUnder(tenant, instance, UUID.randomUUID()).isEmpty(), "an id nothing holds");
    }

    @Test
    void theAdaptersSubmitTimeQuestionsAreScopedToTheTenantAndInstanceToo() {
        stage("first");
        UUID otherInstance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, otherInstance);
        UUID otherTenant = SmDataPostgres.freshTenant();

        assertTrue(realTargets.instanceActive(tenant, instance));
        assertFalse(realTargets.instanceActive(otherTenant, instance), "another tenant's instance is absent");
        assertFalse(realTargets.instanceActive(tenant, UUID.randomUUID()));
        assertTrue(realTargets.placementLive(tenant, instance, placement));
        assertFalse(realTargets.placementLive(tenant, otherInstance, placement), "a placement of ANOTHER instance");
        assertFalse(realTargets.placementLive(otherTenant, instance, placement));
        assertTrue(realTargets.generationStaged(tenant, instance, 1));
        assertFalse(realTargets.generationStaged(tenant, instance, 99));
        assertFalse(realTargets.generationStaged(tenant, otherInstance, 1));
    }
}
