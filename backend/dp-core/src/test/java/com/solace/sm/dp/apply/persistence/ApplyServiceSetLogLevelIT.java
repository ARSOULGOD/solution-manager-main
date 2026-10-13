package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.apply.ApplyAcceptedView;
import com.solace.sm.dp.api.apply.ApplyObservationView;
import com.solace.sm.dp.api.apply.ApplyRefusedException;
import com.solace.sm.dp.api.apply.ApplyRefusedException.Reason;
import com.solace.sm.dp.api.apply.ObserveQuery;
import com.solace.sm.dp.api.apply.RestoreRuntimeCommand;
import com.solace.sm.dp.api.apply.SetLogLevelCommand;
import com.solace.sm.dp.api.apply.SubmitApplyCommand;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.LoggerSupport;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.logs.LoggerLevel;
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
import com.solace.sm.dp.config.ConfigSecretServiceImpl;
import com.solace.sm.dp.config.Retention;
import com.solace.sm.dp.config.RollbackTargets;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
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
 * Plan 010 T9.4 through the data plane (OR-103, P2): a log level is configuration. {@code setLogLevel} mints a NEW
 * generation (origin {@code edit}) carrying the latest content plus one SM-owned overlay entry, then admits a normal
 * {@code manual} apply of it. Real PostgreSQL, the real config of record, the real engine and an in-memory host.
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyServiceSetLogLevelIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final String PREFIX = "com.solace.solutions.cache";
    private static final String LEVEL_PATH = "logging.level." + PREFIX;
    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\"}]}";
    private static final LoggerSupport SUPPORT = new LoggerSupport(List.of("health", "info"));
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

    /** An operator edit from a package that declares logger support (the gateway's stage). */
    private long stage(String marker) {
        long base = superuser.queryForObject("SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);
        return config.stage(tenant, instance, new StageCommand(base, "cache", "2.4", SELECTION,
                "{\"cache-1\":{\"marker\":\"" + marker + "\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"" + marker + "\"")),
                GenerationOrigin.EDIT, null, ACTOR, null, SUPPORT)).generation().generation();
    }

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

    private SetLogLevelCommand set(LoggerLevel level) {
        return new SetLogLevelCommand(placement, PREFIX, level, ACTOR);
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

    private String column(long generation, String column) {
        return superuser.queryForObject("SELECT " + column + "::text FROM sm_data.config_generation WHERE tenant_id = :t"
                + " AND instance_id = :i AND generation = :g", key().addValue("g", generation), String.class);
    }

    /** The overlay's level value stored in a generation, or null. */
    private String storedLevel(long generation) {
        List<String> values = superuser.queryForList("SELECT e->>'value' FROM sm_data.config_generation g,"
                + " jsonb_array_elements(g.rendered_entries) e WHERE g.tenant_id = :t AND g.instance_id = :i"
                + " AND g.generation = :g AND e->>'instanceKey' = '@sm-overlay' AND e->>'renderedPath' = :p",
                key().addValue("g", generation).addValue("p", LEVEL_PATH), String.class);
        return values.isEmpty() ? null : values.get(0);
    }

    private boolean storedHas(long generation, String instanceKey, String path) {
        return !superuser.queryForList("SELECT 1 FROM sm_data.config_generation g,"
                + " jsonb_array_elements(g.rendered_entries) e WHERE g.tenant_id = :t AND g.instance_id = :i"
                + " AND g.generation = :g AND e->>'instanceKey' = :k AND e->>'renderedPath' = :p",
                key().addValue("g", generation).addValue("k", instanceKey).addValue("p", path), Integer.class).isEmpty();
    }

    private static void assertRefused(Reason reason, Runnable call) {
        assertEquals(reason, assertThrows(ApplyRefusedException.class, call::run).reason());
    }

    // ---- tests ------------------------------------------------------------------------------------------------

    @Test
    void aConfigLevelIsACopyForwardGenerationAppliedNormally() {
        stage("first");
        applied(1);

        ApplyAcceptedView accepted = service.setLogLevel(tenant, instance, set(LoggerLevel.DEBUG));

        ApplyObservationView before = observed(accepted);
        assertEquals("apply", before.operation(), "an ordinary apply, not a fourth operation (OR-103)");
        assertEquals(Long.valueOf(2), before.generation(), "the apply is of the NEW generation");
        assertEquals(2L, generations());
        assertEquals("DEBUG", storedLevel(2));
        assertEquals("edit", column(2, "origin"));
        assertEquals(column(1, "draft_values"), column(2, "draft_values"), "only the overlay differs");
        assertTrue(storedHas(2, "cache-1", "cache-engine.ttl"), "the package's fields are carried");
        assertTrue(storedHas(2, "@sm-overlay", "management.endpoint.loggers.access"), "the baseline overlay stays");
        assertFalse(column(1, "content_digest").equals(column(2, "content_digest")), "the level is in the keyed digest");
        run(accepted);
        assertEquals("STAGED", observed(accepted).state(), "manual: the file is written and nothing restarts");
    }

    @Test
    void aSecondLevelReplacesTheFirstAndTheSameLevelAgainMintsNothing() {
        stage("first");
        applied(1);
        run(service.setLogLevel(tenant, instance, set(LoggerLevel.DEBUG)));

        ApplyAcceptedView next = service.setLogLevel(tenant, instance, set(LoggerLevel.WARN));

        assertEquals(3L, generations());
        assertEquals("WARN", storedLevel(3));
        assertEquals("DEBUG", storedLevel(2), "history keeps the old generation immutable");
        run(next);

        // OR-33 guard 1: nothing observable changes, so no generation is minted; the apply of the existing latest is
        // then refused by the watermark exactly as a re-submit of an applied generation is
        assertRefused(Reason.GENERATION_NOT_ABOVE_WATERMARK, () -> service.setLogLevel(tenant, instance,
                set(LoggerLevel.WARN)));
        assertEquals(3L, generations());
    }

    @Test
    void aDesignerEditKeepsTheConfiguredLogLevel() {
        stage("first");
        applied(1);
        run(service.setLogLevel(tenant, instance, set(LoggerLevel.DEBUG)));

        long edited = stage("second");

        assertEquals(3L, edited);
        assertEquals("DEBUG", storedLevel(3), "the stage re-renders from the draft, which has no overlay: it must carry it");
        assertTrue(storedHas(3, "@sm-overlay", "management.endpoint.loggers.access"));
        assertEquals("second", superuser.queryForObject("SELECT e->>'value' FROM sm_data.config_generation g,"
                + " jsonb_array_elements(g.rendered_entries) e WHERE g.tenant_id = :t AND g.instance_id = :i"
                + " AND g.generation = 3 AND e->>'renderedPath' = 'cache-engine.ttl'", key(), String.class));
    }

    /**
     * Fable review APPLY-01: the third edit path. Setting a config secret mints a generation too, and it must carry the
     * configured level exactly as the designer edit and accept-runtime do; the package declares logger support, so the
     * level is the operator's configuration and a secret change is not a request to drop it.
     */
    @Test
    void aSecretSetKeepsTheConfiguredLogLevel() {
        stage("first");
        applied(1);
        run(service.setLogLevel(tenant, instance, set(LoggerLevel.DEBUG)));
        assertEquals("DEBUG", storedLevel(2));
        long base = superuser.queryForObject("SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);
        EnvelopeSecretStore secrets = SecretFixtures.store(SecretFixtures.ring(
                SecretFixtures.key(SecretFixtures.freshKeyId(), 41), null));
        ConfigSecretServiceImpl secretService = new ConfigSecretServiceImpl(ApplyEngineHarness.transactions(),
                new JdbcConfigGenerationRepository(SmDataPostgres.runtimeJdbc()), secrets, TestHashers.keyed(),
                new Retention(50), Clock.systemUTC());

        long minted = secretService.storeAndStage(tenant, instance, base, "s3cret-value".toCharArray(), ACTOR,
                ref -> new StageCommand(base, "cache", "2.4", SELECTION,
                        "{\"cache-1\":{\"marker\":\"first\",\"solace.password\":\"secretRef://" + ref + "\"}}",
                        List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"first\""),
                                RenderedEntryValue.secret("cache-1", "cache-engine.solace.password", ref)),
                        GenerationOrigin.EDIT, null, ACTOR, null, SUPPORT)).generation().generation();

        assertEquals(3L, minted);
        assertEquals("DEBUG", storedLevel(3), "a secret set re-renders from the draft: it must carry the level too");
        assertTrue(storedHas(3, "@sm-overlay", "management.endpoint.loggers.access"));
        assertEquals(java.util.Map.of(PREFIX, LoggerLevel.DEBUG), config.configuredLogLevels(tenant, instance));
    }

    @Test
    void anAcceptedRuntimeEditKeepsTheConfiguredLevelToo() {
        stage("first");
        applied(1);
        run(service.setLogLevel(tenant, instance, set(LoggerLevel.WARN)));
        long base = superuser.queryForObject("SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);

        // accept-runtime re-renders from the accepted draft through the same stage, with the package's declaration
        long accepted = config.stage(tenant, instance, new StageCommand(base, "cache", "2.4", SELECTION,
                "{\"cache-1\":{\"marker\":\"adopted\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"adopted\"")),
                GenerationOrigin.ACCEPT_RUNTIME, 2L, ACTOR, null, SUPPORT)).generation().generation();

        assertEquals(3L, accepted);
        assertEquals("accept-runtime", column(3, "origin"));
        assertEquals("WARN", storedLevel(3), "the host's edit was adopted; the level SM configured was not lost");
    }

    @Test
    void anUnrelatedEditThatChangesNothingIsStillUnchangedWithALevelConfigured() {
        stage("first");
        applied(1);
        run(service.setLogLevel(tenant, instance, set(LoggerLevel.INFO)));

        long before = generations();
        long base = superuser.queryForObject("SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);
        boolean unchanged = config.stage(tenant, instance, new StageCommand(base, "cache", "2.4", SELECTION,
                "{\"cache-1\":{\"marker\":\"first\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"first\"")),
                GenerationOrigin.EDIT, null, ACTOR, null, SUPPORT)).unchanged();

        assertTrue(unchanged, "carrying the level makes the repeated stage equal to the latest (OR-33 guard 1)");
        assertEquals(before, generations());
    }

    @Test
    void restoreAndACopyForwardCarryTheLevelUnchanged() {
        stage("first");
        applied(1);
        run(service.setLogLevel(tenant, instance, set(LoggerLevel.TRACE)));

        ApplyAcceptedView restored = service.restoreRuntime(tenant, instance,
                new RestoreRuntimeCommand(placement, "manual", null, ACTOR));
        long copy = observed(restored).generation();
        run(restored);
        long rolledBack = config.copyForward(tenant, instance, 1, GenerationOrigin.ROLLBACK, ACTOR).generation()
                .generation();
        long forwardOfLevel = config.copyForward(tenant, instance, 2, GenerationOrigin.ROLLBACK, ACTOR).generation()
                .generation();

        assertEquals("TRACE", storedLevel(copy), "restore-runtime keeps the level");
        assertEquals("TRACE", storedLevel(forwardOfLevel), "a rollback to the level's generation keeps it");
        assertEquals(null, storedLevel(rolledBack), "a rollback to a generation before the level restores that content");
    }

    @Test
    void getReportsTheConfiguredLevelsOfTheLatestGenerationAndNothingBeforeOne() {
        assertEquals(java.util.Map.of(), config.configuredLogLevels(tenant, instance), "nothing staged yet");
        stage("first");
        applied(1);
        assertEquals(java.util.Map.of(), config.configuredLogLevels(tenant, instance), "a stage configures no level");

        run(service.setLogLevel(tenant, instance, set(LoggerLevel.ERROR)));
        assertEquals(java.util.Map.of(PREFIX, LoggerLevel.ERROR), config.configuredLogLevels(tenant, instance));
        run(service.setLogLevel(tenant, instance, new SetLogLevelCommand(placement, "com.other.logger",
                LoggerLevel.TRACE, ACTOR)));
        assertEquals(java.util.Map.of(PREFIX, LoggerLevel.ERROR, "com.other.logger", LoggerLevel.TRACE),
                config.configuredLogLevels(tenant, instance), "one entry per logger, and the first is kept");
        assertEquals(java.util.Map.of(), config.configuredLogLevels(UUID.randomUUID(), instance),
                "another organization's read sees nothing (I5)");
    }

    @Test
    void aLevelRefusedForABusyScopeMintsNothing() {
        stage("first");
        applied(1);
        ApplyAcceptedView first = service.setLogLevel(tenant, instance, set(LoggerLevel.DEBUG)); // holds the scope
        assertEquals(2L, generations());

        assertRefused(Reason.SCOPE_BUSY, () -> service.setLogLevel(tenant, instance, set(LoggerLevel.WARN)));
        assertEquals(2L, generations(), "the scope check runs BEFORE the copy (S811-2): the refusal minted nothing");
        assertEquals("DEBUG", storedLevel(2));

        run(first);
        service.setLogLevel(tenant, instance, set(LoggerLevel.WARN));
        assertEquals(3L, generations());
    }

    @Test
    void theCopyAndTheAdmissionAreSeparateTransactions() {
        stage("first");
        applied(1);
        long generations = generations();
        long requests = requests();

        ApplyAcceptedView[] accepted = new ApplyAcceptedView[1];
        ApplyEngineHarness.transactions().executeWithoutResult(status -> {
            accepted[0] = service.setLogLevel(tenant, instance, set(LoggerLevel.DEBUG));
            status.setRollbackOnly();
        });

        assertEquals(generations + 1, generations(), "the copy committed in a transaction of its own");
        assertEquals(requests + 1, requests(), "so did the admission");
        assertEquals("REQUESTED", observed(accepted[0]).state());
    }

    @Test
    void anInstanceWithNothingStagedAnAbsentPlacementAndAForeignOrganizationAreRefusedAndMintNothing() {
        assertRefused(Reason.GENERATION_NOT_STAGED, () -> service.setLogLevel(tenant, instance, set(LoggerLevel.DEBUG)));
        stage("first");
        applied(1);
        long generations = generations();

        assertRefused(Reason.NOT_FOUND, () -> service.setLogLevel(tenant, instance,
                new SetLogLevelCommand(UUID.randomUUID(), PREFIX, LoggerLevel.DEBUG, ACTOR)));
        assertThrows(ApplyRefusedException.class,
                () -> service.setLogLevel(UUID.randomUUID(), instance, set(LoggerLevel.DEBUG)));
        for (String bad : List.of("ROOT", "root", "a$b", "", "a b")) {
            assertThrows(IllegalArgumentException.class, () -> service.setLogLevel(tenant, instance,
                    new SetLogLevelCommand(placement, bad, LoggerLevel.DEBUG, ACTOR)), bad);
        }
        assertEquals(generations, generations());
    }

    // ---- W2-01: a level change must not carry an unapproved designer edit to the host ---------------------------

    @Test
    void aStagedButUnappliedEditIsNeverWrittenByALevelChange() {
        stage("first");
        applied(1);
        long edited = stage("second"); // staged, waiting for whoever holds config.apply
        long generations = generations();
        long requests = requests();

        assertRefused(Reason.UNAPPLIED_DRAFT_PENDING, () -> service.setLogLevel(tenant, instance, set(LoggerLevel.DEBUG)));

        assertEquals(2L, edited);
        assertEquals(generations, generations(), "nothing was minted");
        assertEquals(requests, requests(), "nothing was admitted");
        assertEquals(java.util.Map.of(), config.configuredLogLevels(tenant, instance));

        // once the edit is applied (approved) the level change is an ordinary copy of what is on the host
        run(service.submitApply(tenant, instance, new SubmitApplyCommand(placement, edited, "manual", null, ACTOR)));
        ApplyAcceptedView accepted = service.setLogLevel(tenant, instance, set(LoggerLevel.DEBUG));
        assertEquals(3L, observed(accepted).generation());
        assertEquals("DEBUG", storedLevel(3));
    }

    @Test
    void aPlacementNothingWasEverWrittenToRefusesALevelChange() {
        stage("first");

        assertRefused(Reason.UNAPPLIED_DRAFT_PENDING, () -> service.setLogLevel(tenant, instance, set(LoggerLevel.DEBUG)));
        assertEquals(1L, generations());
    }

    @Test
    void aLevelOnlyGenerationThatWasNeverApplied_IsNotADraft() {
        stage("first");
        applied(1);
        // a level generation minted and never admitted (W2-12's window): it differs from the host by a level entry only
        config.copyLatestWithLogLevel(tenant, instance, placement, PREFIX, LoggerLevel.DEBUG, ACTOR);
        assertEquals(2L, generations());

        ApplyAcceptedView accepted = service.setLogLevel(tenant, instance, set(LoggerLevel.WARN));

        assertEquals(3L, observed(accepted).generation());
        assertEquals("WARN", storedLevel(3));
    }

    @Test
    void aLevelGenerationWhoseAdmissionWasRefusedStaysStagedAndAResubmitAdmitsItWithoutMintingAnother() {
        // W2-12: the commit of the copy and the admission are separate transactions, so an admission refused after the
        // copy committed leaves the level generation as the head. Simulated by minting it without admitting it.
        stage("first");
        applied(1);
        config.copyLatestWithLogLevel(tenant, instance, placement, PREFIX, LoggerLevel.DEBUG, ACTOR);
        assertEquals(java.util.Map.of(PREFIX, LoggerLevel.DEBUG), config.configuredLogLevels(tenant, instance),
                "the console would read DEBUG for a change it was told was refused: documented on the 409");

        ApplyAcceptedView accepted = service.setLogLevel(tenant, instance, set(LoggerLevel.DEBUG));

        assertEquals(2L, generations(), "the same level again mints nothing (OR-33 guard 1)");
        assertEquals(2L, observed(accepted).generation(), "it admits the staged level generation");
    }

    @Test
    void theLevelIsNeverAFieldOfTheDraft() {
        stage("first");
        applied(1);
        run(service.setLogLevel(tenant, instance, set(LoggerLevel.DEBUG)));

        assertFalse(column(2, "draft_values").contains("DEBUG"), "the level lives in the overlay, not in a draft field");
        assertFalse(column(2, "component_selection").contains("logging"));
    }
}
