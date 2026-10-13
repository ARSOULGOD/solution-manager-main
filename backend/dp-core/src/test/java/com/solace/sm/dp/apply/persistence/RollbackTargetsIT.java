package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.apply.ApplyRefusedException;
import com.solace.sm.dp.api.apply.ApplyRefusedException.Reason;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.config.StageResult;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.Retention;
import com.solace.sm.dp.config.RollbackTargets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T8.1 at the data plane (OR-42): which generations an operator rollback may copy forward, and the
 * copy-forward itself, over a real PostgreSQL and the real config of record.
 *
 * <p>Eligible means the placement's {@code apply} request for that generation reached {@code STAGED},
 * {@code APPLIED}, or {@code SUPERSEDED} (which the terminal-guard trigger only allows after {@code STAGED}). The ruling
 * lists those three, and OR-108 (7) adds a fourth: {@code INDETERMINATE_RESOLVED} with outcome {@code applied} (it was
 * confirmed on the host). {@code INDETERMINATE_RESOLVED} with outcome {@code not_applied} stays excluded.
 *
 * <p>(In the persistence test package for the same reason as {@code ConfigLadderIT}: the engine harness is package
 * private.)
 */
@Testcontainers(disabledWithoutDocker = true)
class RollbackTargetsIT {

    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\"}]}";
    private static final UUID ACTOR = UUID.randomUUID();

    private NamedParameterJdbcTemplate superuser;
    private UUID tenant;
    private UUID instance;
    private UUID placement;
    private ConfigRecordServiceImpl config;
    private RollbackTargets targets;

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
        tenant = SmDataPostgres.freshTenant();
        instance = UUID.randomUUID();
        ApplyRows.insertScope(superuser, tenant, instance);
        placement = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        config = new ConfigRecordServiceImpl(ApplyEngineHarness.transactions(),
                new JdbcConfigGenerationRepository(SmDataPostgres.runtimeJdbc()), TestHashers.keyed(),
                new Retention(50), Clock.systemUTC(),
                new JdbcRollbackTargetRepository(SmDataPostgres.runtimeJdbc()));
        targets = new RollbackTargets(new JdbcRollbackTargetRepository(SmDataPostgres.runtimeJdbc()));
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

    private long base() {
        return superuser.queryForObject("SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);
    }

    private MapSqlParameterSource key() {
        return new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance);
    }

    /** Stages the next generation through the real config of record and returns its number. */
    private long stage(String marker) {
        return config.stage(tenant, instance, new StageCommand(base(), "cache", "2.4", SELECTION,
                "{\"cache-1\":{\"marker\":\"" + marker + "\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"" + marker + "\"")),
                GenerationOrigin.EDIT, null, ACTOR)).generation().generation();
    }

    /** A request row in {@code state}, written the way the engine's own landings leave it (terminal_at when terminal). */
    private UUID request(UUID onPlacement, long generation, String state) {
        UUID applyId = UUID.randomUUID();
        boolean open = state.equals("REQUESTED") || state.equals("RUNNING") || state.equals("APPLY_INDETERMINATE");
        superuser.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id, operation,"
                        + " generation, strategy, requested_by, requested_at, state, terminal_at)"
                        + " VALUES (:a, :t, :i, :p, 'apply', :g, 'manual', 'operator-1', :now, :s, :term)",
                key().addValue("a", applyId).addValue("p", onPlacement).addValue("g", generation)
                        .addValue("now", OffsetDateTime.now()).addValue("s", state)
                        .addValue("term", open ? null : OffsetDateTime.now()));
        return applyId;
    }

    private UUID resolvedApplied(UUID onPlacement, long generation) {
        return resolved(onPlacement, generation, "applied");
    }

    private UUID resolved(UUID onPlacement, long generation, String outcome) {
        UUID applyId = UUID.randomUUID();
        superuser.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id, operation,"
                        + " generation, strategy, requested_by, requested_at, state, terminal_at, resolved_by,"
                        + " resolution_outcome, resolution_observed)"
                        + " VALUES (:a, :t, :i, :p, 'apply', :g, 'manual', 'operator-1', :now,"
                        + " 'INDETERMINATE_RESOLVED', :now, 'operator-2', :outcome,"
                        + " jsonb_build_object('stagedGeneration', CAST(:g AS bigint), 'replicas',"
                        + " jsonb_build_array(jsonb_build_object('ref', 'r1', 'restarted', false,"
                        + " 'observedHash', NULL))))",
                key().addValue("a", applyId).addValue("p", onPlacement).addValue("g", generation)
                        .addValue("outcome", outcome).addValue("now", OffsetDateTime.now()));
        return applyId;
    }

    private long resolve(UUID onPlacement, UUID applyId, Long generation) {
        return targets.resolve(new TenantId(tenant), new InstanceId(instance), new PlacementId(onPlacement), applyId,
                generation);
    }

    private static void assertRefused(Reason reason, Runnable call) {
        assertEquals(reason, assertThrows(ApplyRefusedException.class, call::run).reason());
    }

    // ---- eligibility ------------------------------------------------------------------------------------------

    @Test
    void aGenerationNeverWrittenToThatPlacementIsNotEligible() {
        stage("a");
        stage("b");
        long three = stage("c");
        request(placement, 1, "APPLIED");
        request(placement, 3, "STAGED");
        // generation 2 exists in the config of record but no request ever took it to this placement

        assertEquals(1, resolve(placement, null, 1L));
        assertRefused(Reason.ROLLBACK_TARGET_NOT_ELIGIBLE, () -> resolve(placement, null, 2L));
        assertEquals(3, three);
    }

    @Test
    void stagedAppliedAndSupersededAfterStagingAreTheThreeEligibleStates() {
        UUID other = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        for (int g = 1; g <= 3; g++) {
            stage("m" + g);
        }
        request(placement, 1, "APPLIED");
        request(placement, 2, "SUPERSEDED");
        request(placement, 3, "STAGED");

        assertEquals(1, resolve(placement, null, 1L));
        assertEquals(2, resolve(placement, null, 2L));
        assertEquals(3, resolve(placement, null, 3L));
        assertRefused(Reason.ROLLBACK_TARGET_NOT_ELIGIBLE, () -> resolve(other, null, 1L));
    }

    @Test
    void everyOtherStateIsNotEligible() {
        List<String> states = List.of("REQUESTED", "FAILED_NO_EFFECT", "FAILED_ROLLED_BACK", "CONFIG_DIVERGED",
                "APPLY_INDETERMINATE");
        for (int g = 1; g <= states.size(); g++) {
            stage("m" + g);
        }
        // one placement per state: an open request fences its placement, and the point is each state's own verdict
        for (int i = 0; i < states.size(); i++) {
            UUID own = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
            request(own, i + 1, states.get(i));
            long generation = i + 1;
            assertRefused(Reason.ROLLBACK_TARGET_NOT_ELIGIBLE, () -> resolve(own, null, generation));
        }
    }

    /** OR-108 (7): an apply the operator resolved as applied was confirmed on the host, so it is a rollback target. */
    @Test
    void resolvedAppliedIsEligibleAsARollbackTargetOR108() {
        stage("a");
        resolvedApplied(placement, 1);

        assertEquals(1, resolve(placement, null, 1L));
    }

    /** By apply id too: "undo this apply" finds a resolved-applied elder as the highest eligible generation below. */
    @Test
    void aResolvedAppliedElderIsFoundByTheApplyIdForm() {
        stage("a");
        stage("b");
        resolvedApplied(placement, 1);
        UUID newer = request(placement, 2, "APPLIED");

        assertEquals(1, resolve(placement, newer, null));
    }

    /** OR-108 (7) extends the list by one case only: resolved as NOT applied was never written, so it stays out. */
    @Test
    void resolvedNotAppliedIsStillNotEligible() {
        stage("a");
        resolved(placement, 1, "not_applied");

        assertRefused(Reason.ROLLBACK_TARGET_NOT_ELIGIBLE, () -> resolve(placement, null, 1L));
    }

    @Test
    void aGenerationWrittenToAnotherPlacementIsNotEligibleHereAndAnotherTenantsRequestIsInvisible() {
        stage("a");
        UUID other = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        request(other, 1, "APPLIED");
        assertRefused(Reason.ROLLBACK_TARGET_NOT_ELIGIBLE, () -> resolve(placement, null, 1L));

        UUID foreignTenant = SmDataPostgres.freshTenant();
        UUID foreignInstance = UUID.randomUUID();
        ApplyRows.insertScope(superuser, foreignTenant, foreignInstance);
        UUID foreignPlacement = InventoryFixtures.placement(foreignTenant, foreignInstance,
                InventoryFixtures.target(foreignTenant));
        superuser.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id,"
                        + " operation, generation, strategy, requested_by, requested_at, state, terminal_at)"
                        + " VALUES (:a, :t, :i, :p, 'apply', 1, 'manual', 'operator-1', :now, 'APPLIED', :now)",
                new MapSqlParameterSource().addValue("a", UUID.randomUUID()).addValue("t", foreignTenant)
                        .addValue("i", foreignInstance).addValue("p", foreignPlacement)
                        .addValue("now", OffsetDateTime.now()));
        // the same placement id and generation, asked about under THIS tenant: nothing is visible
        assertRefused(Reason.ROLLBACK_TARGET_NOT_ELIGIBLE, () -> targets.resolve(new TenantId(tenant),
                new InstanceId(instance), new PlacementId(foreignPlacement), null, 1L));
    }

    @Test
    void withAnApplyIdTheTargetIsTheHighestEligibleGenerationBelowThatApplysGeneration() {
        for (int g = 1; g <= 5; g++) {
            stage("m" + g);
        }
        request(placement, 1, "APPLIED");
        request(placement, 2, "SUPERSEDED");
        request(placement, 3, "FAILED_NO_EFFECT");   // never written: skipped
        request(placement, 4, "APPLIED");
        UUID five = request(placement, 5, "STAGED");

        assertEquals(4, resolve(placement, five, null), "the highest eligible below 5 is 4");
        UUID four = superuser.queryForObject("SELECT apply_id FROM sm_data.apply_request WHERE tenant_id = :t"
                + " AND generation = 4", key(), UUID.class);
        assertEquals(2, resolve(placement, four, null), "below 4: 3 was never written, so 2");
        UUID two = superuser.queryForObject("SELECT apply_id FROM sm_data.apply_request WHERE tenant_id = :t"
                + " AND generation = 2", key(), UUID.class);
        assertEquals(1, resolve(placement, two, null));
        UUID one = superuser.queryForObject("SELECT apply_id FROM sm_data.apply_request WHERE tenant_id = :t"
                + " AND generation = 1", key(), UUID.class);
        assertRefused(Reason.ROLLBACK_TARGET_NOT_ELIGIBLE, () -> resolve(placement, one, null));
    }

    @Test
    void anApplyIdThatIsNotAnApplyOfThisInstanceIsNotFound() {
        stage("a");
        assertRefused(Reason.NOT_FOUND, () -> resolve(placement, UUID.randomUUID(), null));

        // a stop request of this instance is not an apply either
        UUID stop = UUID.randomUUID();
        superuser.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id,"
                        + " operation, requested_by, requested_at, state, terminal_at)"
                        + " VALUES (:a, :t, :i, :p, 'stop', 'operator-1', :now, 'STOPPED', :now)",
                key().addValue("a", stop).addValue("p", placement).addValue("now", OffsetDateTime.now()));
        assertRefused(Reason.NOT_FOUND, () -> resolve(placement, stop, null));

        // another instance's apply, even of the same tenant, is not this instance's
        UUID otherInstance = UUID.randomUUID();
        ApplyRows.insertScope(superuser, tenant, otherInstance);
        UUID otherPlacement = InventoryFixtures.placement(tenant, otherInstance, InventoryFixtures.target(tenant));
        UUID foreign = UUID.randomUUID();
        superuser.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id,"
                        + " operation, generation, strategy, requested_by, requested_at, state, terminal_at)"
                        + " VALUES (:a, :t, :i, :p, 'apply', 2, 'manual', 'operator-1', :now, 'APPLIED', :now)",
                new MapSqlParameterSource().addValue("a", foreign).addValue("t", tenant)
                        .addValue("i", otherInstance).addValue("p", otherPlacement)
                        .addValue("now", OffsetDateTime.now()));
        assertRefused(Reason.NOT_FOUND, () -> resolve(placement, foreign, null));
    }

    @Test
    void anApplyIdOfAnotherPlacementIsNotFoundForThisPlacement() {
        for (int g = 1; g <= 3; g++) {
            stage("m" + g);
        }
        UUID other = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        request(placement, 1, "APPLIED");
        request(other, 2, "APPLIED");
        UUID onOther = request(other, 3, "STAGED");

        // S811-5: the anchor must be an apply of THIS placement, else the rollback would borrow another host's history
        assertRefused(Reason.NOT_FOUND, () -> resolve(placement, onOther, null));
        assertEquals(2, resolve(other, onOther, null), "the same apply names its own placement's history");
    }

    // ---- the copy-forward -------------------------------------------------------------------------------------

    private boolean sameContent(long a, long b) {
        return superuser.queryForObject("SELECT x.component_selection = y.component_selection"
                + " AND x.draft_values = y.draft_values AND x.rendered_entries = y.rendered_entries"
                + " AND x.package_solution = y.package_solution AND x.package_version = y.package_version"
                + " AND x.content_digest = y.content_digest FROM sm_data.config_generation x,"
                + " sm_data.config_generation y WHERE x.tenant_id = :t AND x.instance_id = :i AND x.generation = :a"
                + " AND y.tenant_id = :t AND y.instance_id = :i AND y.generation = :b",
                key().addValue("a", a).addValue("b", b), Boolean.class);
    }

    @Test
    void copyForwardMintsAHigherGenerationWithTheTargetsContent() {
        stage("first");
        stage("second");

        StageResult copied = config.copyForward(tenant, instance, 1, GenerationOrigin.ROLLBACK, ACTOR);

        assertFalse(copied.unchanged());
        assertEquals(3, copied.generation().generation(), "a new, higher number: the only-goes-up fence is untouched");
        assertEquals(GenerationOrigin.ROLLBACK, copied.generation().origin());
        assertEquals(1L, copied.generation().derivedFromGeneration());
        assertEquals(ACTOR, copied.generation().createdBy());
        assertTrue(sameContent(1, 3), "selection, draft, rendered set, package and keyed digest all equal gen 1's");
        assertFalse(sameContent(2, 3));
        assertEquals(3L, superuser.queryForObject("SELECT last_generation FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", key(), Long.class), "the head moved to the copy");
        assertEquals(copied.stateVersion(), base());
    }

    @Test
    void aRetryAfterACrashBetweenTheCopyAndTheSubmitReturnsTheSameGenerationNeverASecond() {
        stage("first");
        stage("second");
        StageResult first = config.copyForward(tenant, instance, 1, GenerationOrigin.ROLLBACK, ACTOR);

        StageResult retry = config.copyForward(tenant, instance, 1, GenerationOrigin.ROLLBACK, ACTOR);

        assertTrue(retry.unchanged(), "OR-33 guard 1: the latest generation already has that content");
        assertEquals(first.generation().generation(), retry.generation().generation());
        assertEquals(3L, superuser.queryForObject("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class), "no second rollback generation");
    }

    @Test
    void copyForwardLeavesTheLayoutRecordAlone() {
        config.stage(tenant, instance, new StageCommand(base(), "cache", "2.4", SELECTION, "{\"cache-1\":{\"m\":\"1\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"1\"")), GenerationOrigin.EDIT, null,
                ACTOR, "{\"cache-1\":{\"x\":1,\"y\":2}}"));
        config.stage(tenant, instance, new StageCommand(base(), "cache", "2.4", SELECTION, "{\"cache-1\":{\"m\":\"2\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"2\"")), GenerationOrigin.EDIT, null,
                ACTOR, "{\"cache-1\":{\"x\":9,\"y\":9}}"));

        config.copyForward(tenant, instance, 1, GenerationOrigin.ROLLBACK, ACTOR);

        assertEquals("{\"cache-1\": {\"x\": 9, \"y\": 9}}", superuser.queryForObject("SELECT layout::text FROM"
                + " sm_data.designer_layout WHERE tenant_id = :t AND instance_id = :i", key(), String.class),
                "layout is presentation state; a rollback copies content, not box positions");
    }

    @Test
    void aSecretIsCopiedAsAReferenceAndNeverAsAValue() {
        UUID ref = UUID.randomUUID();
        config.stage(tenant, instance, new StageCommand(base(), "cache", "2.4", SELECTION, "{\"cache-1\":{}}",
                List.of(RenderedEntryValue.secret("cache-1", "cache-engine.password", ref),
                        RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "1")), GenerationOrigin.EDIT, null,
                ACTOR));
        stage("second");

        config.copyForward(tenant, instance, 1, GenerationOrigin.ROLLBACK, ACTOR);

        String rendered = superuser.queryForObject("SELECT rendered_entries::text FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i AND generation = 3", key(), String.class);
        assertTrue(rendered.contains(ref.toString()), "the reference is carried");
        assertFalse(superuser.queryForObject("SELECT jsonb_path_exists(rendered_entries,"
                + " '$[*] ? (exists(@.secretRef) && exists(@.value))') FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i AND generation = 3", key(), Boolean.class));
    }

    @Test
    void aGenerationThatDoesNotExistAnInstanceOfAnotherTenantAndAnEditOriginAreRefused() {
        stage("first");

        assertThrows(NoSuchElementException.class,
                () -> config.copyForward(tenant, instance, 99, GenerationOrigin.ROLLBACK, ACTOR));
        assertThrows(NoSuchElementException.class,
                () -> config.copyForward(UUID.randomUUID(), instance, 1, GenerationOrigin.ROLLBACK, ACTOR),
                "another organization cannot copy this instance's generation");
        assertThrows(IllegalArgumentException.class,
                () -> config.copyForward(tenant, instance, 1, GenerationOrigin.EDIT, ACTOR));
        assertEquals(1L, superuser.queryForObject("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class), "nothing was written");
    }
}
