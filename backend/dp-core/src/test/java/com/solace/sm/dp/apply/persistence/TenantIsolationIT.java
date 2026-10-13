package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.Resolution;
import com.solace.sm.dp.apply.model.ApplyResolutionEvidence;
import com.solace.sm.dp.apply.model.ResolutionOutcome;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code I5}, as a FAILING guard: every repository statement must carry {@code tenant_id = :t}, and
 * this class is what turns red when one does not.
 *
 * <p>Why it is needed at all — measured in plan 004's adversarial review: with {@code tenant_id}
 * dropped from nine statements across all four repositories, the whole suite stayed green. Every id in
 * the fixtures is a globally unique UUID, so the tenant column was redundant <i>in the tests</i>, and
 * the one deliberately cross-tenant admission test survived only because {@code ScopeKey.overlaps}
 * re-checks the tenant in Java. A tenant filter that no test can miss is a tenant filter that will be
 * refactored away.
 *
 * <p>The shape of every probe here: tenant A owns a row; tenant B addresses that row BY ITS PRIMARY
 * KEY — the apply id, the intent id, the attempt id, the (instance, placement) pair — and must see
 * nothing and change nothing. Only the {@code tenant_id} predicate can make that true, because the key
 * alone would match.
 *
 * <p><b>Three fixtures below exist ONLY to close a measured hole in this class itself.</b> Task 6's
 * mutation sweep found that dropping {@code tenant_id} from {@code ApplyRequestRepository.stopInFlight},
 * {@code .stagedRequest} and {@code .resolveIndeterminate} left this suite green: this class had no
 * genuine {@code stop}, {@code STAGED} or {@code APPLY_INDETERMINATE} row for A, so those three
 * statements had nothing to leak. {@code stopApplyOfA}, {@code stagedApplyOfA} and
 * {@code indeterminateApplyOfA} give each of them something real to fail to hide.
 */
@Testcontainers(disabledWithoutDocker = true)
class TenantIsolationIT {

    private static final String HASH = "sha256:5555555555555555555555555555555555555555";

    private static TenantId a;
    private static TenantId b;
    private static InstanceId instance;
    private static PlacementId placement;
    private static ApplyId applyOfA;
    private static StepIntentRow intentOfA;
    private static AttemptRow attemptOfA;
    private static ApplyId stopApplyOfA;
    private static PlacementId stagedPlacement;
    private static ApplyId stagedApplyOfA;
    private static ApplyId indeterminateApplyOfA;

    @BeforeAll
    static void tenantAOwnsARequestAnIntentAnAttemptAndBothWatermarks() {
        SmDataPostgres.bootstrap();
        a = new TenantId(SmDataPostgres.freshTenant());
        b = new TenantId(SmDataPostgres.freshTenant());
        instance = new InstanceId(UUID.randomUUID());
        placement = new PlacementId(UUID.randomUUID());

        ScopeKey scopeOfA = InventoryFixtures.registered(new ScopeKey(a, instance, placement));
        applyOfA = assertInstanceOf(AdmissionOutcome.Admitted.class,
                ApplyEngineHarness.admission().admit(ApplyEngineHarness.apply(scopeOfA, 10))).applyId();
        ApplyEngineHarness.outcomes().start(a, applyOfA);
        intentOfA = new StepIntentRow(Uuid7.next(), applyOfA, placement, Step.MATERIALIZE,
                new ReplicaRef("cache-engine-1"), "idem-" + UUID.randomUUID(), HASH, Instant.now());
        ApplyEngineHarness.intents().insert(a, intentOfA);
        attemptOfA = new AttemptRow(Uuid7.next(), intentOfA.intentId(), Step.MATERIALIZE, (short) 1,
                null, Instant.now(), null, Instant.now().plusSeconds(60), null, null, null, null);
        ApplyEngineHarness.attempts().open(a, applyOfA, attemptOfA);
        ApplyEngineHarness.scopes().raiseInstanceWatermark(a, instance, 7);
        ApplyEngineHarness.scopes().raisePlacementWatermark(a, instance, placement, 9);

        // Tenant B has its own scope row for the SAME instance, so that B's reads have a row to hit
        // and the only thing distinguishing A's rows from B's is tenant_id.
        InventoryFixtures.declare(b, instance);
        ApplyEngineHarness.scopes().ensureScopeRow(b, instance);

        // A genuine STOP in flight for A, at the SAME scope as A's RUNNING apply — obligation (a)'s
        // exemption means the two coexist, and stopInFlight has nothing to leak without this row.
        ApplyRequestRow stopRow = ApplyEngineHarness.requestedRow(scopeOfA, Operation.STOP, null, null);
        ApplyEngineHarness.requests().insert(stopRow);
        stopApplyOfA = stopRow.applyId();

        // A genuine STAGED row for A, on a DIFFERENT placement of the same instance — reusing
        // `placement` would collide with A's RUNNING apply on apply_inflight_fence.
        stagedPlacement = new PlacementId(UUID.randomUUID());
        ScopeKey stagedScope = InventoryFixtures.registered(new ScopeKey(a, instance, stagedPlacement));
        ApplyRequestRow stagedRow = ApplyEngineHarness.requestedApply(stagedScope, 1);
        ApplyEngineHarness.requests().insert(stagedRow);
        stagedApplyOfA = stagedRow.applyId();
        ApplyEngineHarness.outcomes().land(a, stagedApplyOfA, RequestState.STAGED);

        // A genuine APPLY_INDETERMINATE row for A, on its OWN instance — blockingRequests(t, i) is
        // instance-scoped only (no placement filter), so an indeterminate row on `instance` would
        // wrongly inflate `blockingRequests(a, instance).size()` above the 1 the other test asserts.
        InstanceId indeterminateInstance = new InstanceId(UUID.randomUUID());
        ScopeKey indeterminateScope = InventoryFixtures.registered(
                new ScopeKey(a, indeterminateInstance, PlacementId.INSTANCE_WIDE));
        indeterminateApplyOfA = assertInstanceOf(AdmissionOutcome.Admitted.class,
                ApplyEngineHarness.admission().admit(ApplyEngineHarness.apply(indeterminateScope, 1)))
                .applyId();
        ApplyEngineHarness.outcomes().start(a, indeterminateApplyOfA);
        ApplyEngineHarness.outcomes().land(a, indeterminateApplyOfA, RequestState.APPLY_INDETERMINATE);
    }

    // --- ApplyRequestRepository ---------------------------------------------------------------

    @Test
    void tenantBCannotFindTenantAsRequestByItsId() {
        assertTrue(ApplyEngineHarness.requests().find(b, applyOfA).isEmpty());
        assertTrue(ApplyEngineHarness.requests().find(a, applyOfA).isPresent(),
                "positive control: the owner sees it");
    }

    @Test
    void tenantBSeesNoBlockingStagedOrStoppingRequestOnTheSharedInstance() {
        assertTrue(ApplyEngineHarness.requests().blockingRequests(b, instance).isEmpty(),
                "A's RUNNING apply must not fence B");
        assertEquals(1, ApplyEngineHarness.requests().blockingRequests(a, instance).size());
        assertFalse(ApplyEngineHarness.requests().stopInFlight(b, instance, placement));
        assertTrue(ApplyEngineHarness.requests().stopInFlight(a, instance, placement),
                "positive control: A's own stop IS in flight — the assertion above is non-vacuous");
        assertTrue(ApplyEngineHarness.requests().stagedRequest(b, instance, placement).isEmpty());
        assertTrue(ApplyEngineHarness.requests().nonTerminal(b).isEmpty(),
                "resume for B must not load A's in-flight request");
    }

    /** Closes the {@code stagedRequest} gap the mutation sweep found: a real STAGED row for A. */
    @Test
    void tenantBSeesNoStagedRequestOnASeparatePlacementScope() {
        assertTrue(ApplyEngineHarness.requests().stagedRequest(b, instance, stagedPlacement).isEmpty());
        assertEquals(stagedApplyOfA,
                ApplyEngineHarness.requests().stagedRequest(a, instance, stagedPlacement).orElseThrow(),
                "positive control: A's own STAGED row IS there — the assertion above is non-vacuous");
    }

    @Test
    void tenantBCannotWalkFoldOrResolveTenantAsRequest() {
        assertEquals(0, ApplyEngineHarness.requests().transition(b, applyOfA, RequestState.RUNNING,
                RequestState.APPLIED, Instant.now()));
        assertEquals(0, ApplyEngineHarness.requests().foldAttemptSummary(b, applyOfA, "{}"));
        assertEquals(0, ApplyEngineHarness.requests().resolveIndeterminate(b, applyOfA,
                new Resolution("b", ResolutionOutcome.APPLIED,
                        new ApplyResolutionEvidence(10, List.of(new ReplicaObservation(
                                new ReplicaRef("cache-engine-1"), true, null)))),
                Instant.now()));
        assertEquals(RequestState.RUNNING,
                ApplyEngineHarness.requests().find(a, applyOfA).orElseThrow().state(),
                "A's row is exactly as A left it");
    }

    /**
     * Closes the {@code resolveIndeterminate} gap the mutation sweep found: {@code applyOfA} above is
     * RUNNING, so its {@code state = 'APPLY_INDETERMINATE'} fence alone already refuses B's call — a
     * mutation dropping {@code tenant_id} from that statement stayed green. This probe uses a row that
     * IS {@code APPLY_INDETERMINATE}, so only the tenant predicate stands between B and it.
     */
    @Test
    void tenantBCannotResolveTenantAsIndeterminateRequest() {
        assertEquals(0, ApplyEngineHarness.requests().resolveIndeterminate(b, indeterminateApplyOfA,
                new Resolution("b", ResolutionOutcome.APPLIED,
                        new ApplyResolutionEvidence(1, List.of(new ReplicaObservation(
                                new ReplicaRef("cache-engine-1"), true, null)))),
                Instant.now()));
        assertEquals(RequestState.APPLY_INDETERMINATE,
                ApplyEngineHarness.requests().find(a, indeterminateApplyOfA).orElseThrow().state(),
                "A's row is exactly as A left it — still unresolved, not silently resolved by B");
    }

    // --- StepIntentRepository -----------------------------------------------------------------

    @Test
    void tenantBCannotFindOrListTenantAsIntents() {
        assertTrue(ApplyEngineHarness.intents()
                .find(b, applyOfA, placement, Step.MATERIALIZE, intentOfA.replica()).isEmpty());
        assertTrue(ApplyEngineHarness.intents().forRequest(b, applyOfA).isEmpty());
        assertEquals(1, ApplyEngineHarness.intents().forRequest(a, applyOfA).size());
    }

    // --- AttemptRepository --------------------------------------------------------------------

    @Test
    void tenantBCannotSeeCloseCountDeleteOrDetectOpenTenantAsAttempts() {
        assertTrue(ApplyEngineHarness.attempts().latest(b, intentOfA.intentId()).isEmpty());
        assertEquals(0, ApplyEngineHarness.attempts().count(b, intentOfA.intentId()));
        AttemptRepository.OpenState bOnAsIntent = ApplyEngineHarness.attempts()
                .openState(b, intentOfA.intentId());
        assertEquals(0, bOnAsIntent.spent(), "B's own read of openState.spent must be 0 too — the "
                + "tenant_id predicate on THIS query, not only on count(), is under test here");
        assertFalse(bOnAsIntent.hasOpen(),
                "B must not see A's still-open attempt through openState either — only the "
                        + "tenant_id predicate stands between them, exactly as for count/latest");
        assertEquals(0, ApplyEngineHarness.attempts().close(b, attemptOfA.attemptId(),
                AttemptOutcome.FAILED, null, new ErrorClass("b.tried"), null, Instant.now()));
        assertEquals(0, ApplyEngineHarness.attempts().deleteForRequest(b, applyOfA));
        assertEquals(1, ApplyEngineHarness.attempts().count(a, intentOfA.intentId()),
                "A's attempt is still there, still open");
        assertTrue(ApplyEngineHarness.attempts().latest(a, intentOfA.intentId()).orElseThrow()
                .outcome() == null);
        AttemptRepository.OpenState aOnItsOwnIntent = ApplyEngineHarness.attempts()
                .openState(a, intentOfA.intentId());
        assertEquals(1, aOnItsOwnIntent.spent());
        assertTrue(aOnItsOwnIntent.hasOpen(),
                "positive control: A's own open attempt IS visible to A — openState cannot pass "
                        + "the assertion above by simply always returning hasOpen=false");
    }

    // --- ApplyScopeRepository -----------------------------------------------------------------

    @Test
    void tenantBReadsAndRaisesItsOwnWatermarksAndTenantAsStayExactlyWhereAPutThem() {
        assertEquals(0, ApplyEngineHarness.scopes().instanceWatermark(b, instance));
        assertEquals(0, ApplyEngineHarness.scopes().placementWatermark(b, instance, placement));
        assertEquals(0, ApplyEngineHarness.scopes().maxPlacementWatermark(b, instance));
        assertEquals(7, ApplyEngineHarness.scopes().instanceWatermark(a, instance));
        assertEquals(9, ApplyEngineHarness.scopes().placementWatermark(a, instance, placement));

        long lockedWatermark = ApplyEngineHarness.transactions().execute(st ->
                ApplyEngineHarness.scopes().lockScope(b, instance));
        assertEquals(0L, lockedWatermark, "B locks B's row and reads B's watermark, not A's 7");

        ApplyEngineHarness.scopes().raiseInstanceWatermark(b, instance, 50);
        ApplyEngineHarness.scopes().raisePlacementWatermark(b, instance, placement, 60);
        assertEquals(50, ApplyEngineHarness.scopes().instanceWatermark(b, instance));
        assertEquals(60, ApplyEngineHarness.scopes().placementWatermark(b, instance, placement));
        assertEquals(7, ApplyEngineHarness.scopes().instanceWatermark(a, instance),
                "a raise keyed only by instance would have moved A's fence to 50");
        assertEquals(9, ApplyEngineHarness.scopes().placementWatermark(a, instance, placement));
    }
}
