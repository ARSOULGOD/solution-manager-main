package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.RefusalReason;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.ApplyResolutionEvidence;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.Resolution;
import com.solace.sm.dp.apply.model.ResolutionOutcome;
import com.solace.sm.dp.apply.model.RestartObservation;
import com.solace.sm.dp.apply.model.RestartResolutionEvidence;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligation (n): generation monotonicity refused in ALL FOUR cross-scope directions, plus the fifth
 * case the obligation calls out by name — "including immediately after an
 * {@code INDETERMINATE_RESOLVED} with {@code resolution_outcome = 'applied'}".
 *
 * <p>The cross-scope maxima are the whole point (design §2.2 rule 1): an instance-wide {@code APPLIED}
 * at 10 must fence a later placement-scoped 9, and vice versa. A per-key lookup gets every same-shape
 * case right and every cross-shape case wrong, which is exactly the bug this obligation exists to
 * catch — so each test below asserts the WATERMARK TABLE's value after the landing as well as the
 * refusal, because a refusal with the wrong watermark behind it is a coincidence.
 */
@Testcontainers(disabledWithoutDocker = true)
class MonotonicityIT {

    private static AdmissionService admission;
    private static OutcomeService outcomes;
    private static ApplyScopeRepository scopes;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        outcomes = ApplyEngineHarness.outcomes();
        scopes = ApplyEngineHarness.scopes();
    }

    @Test
    void anInstanceWideAppliedAtTenFencesALaterPlacementScopedNineAndAdmitsEleven() {
        Fixture f = new Fixture();
        f.land(f.wide, 10, RequestState.APPLIED);

        assertEquals(10, scopes.instanceWatermark(f.tenant, f.instance),
                "an instance-wide landing raises apply_scope.instance_watermark");
        assertEquals(0, scopes.placementWatermark(f.tenant, f.instance, f.p1),
                "…and no placement row is created for it — the sentinel has no business there");

        assertRefused(admission.admit(ApplyEngineHarness.apply(f.at(f.p1), 9)));
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.at(f.p1), 11)));
    }

    @Test
    void aPlacementStagedAtTwelveFencesTheSamePlacementAtTwelveAndAdmitsThirteen() {
        Fixture f = new Fixture();
        f.land(f.at(f.p1), 12, RequestState.STAGED);

        assertEquals(12, scopes.placementWatermark(f.tenant, f.instance, f.p1),
                "STAGED is the manual strategy's terminal state and counts for monotonicity");
        assertEquals(0, scopes.instanceWatermark(f.tenant, f.instance),
                "a placement landing must NOT raise the instance watermark");

        assertRefused(admission.admit(ApplyEngineHarness.apply(f.at(f.p1), 12)),
                "the comparison is <=, not <");
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.at(f.p1), 13)));
    }

    /**
     * Direction 3 of obligation (n) — instance-wide fenced by its OWN watermark — exercised via a
     * PLAIN {@code APPLIED} landing. Fix round 1, minor 3: before this test, direction 3 was only
     * ever exercised through {@link #resolvingAnIndeterminateAsAppliedRaisesTheWatermarkImmediately},
     * which conflates "the same-shape fence works" with "resolved-applied counts as a landing" — two
     * different claims that happened to share one test. This one isolates the first claim.
     */
    @Test
    void anInstanceWideAppliedAtTwentyFencesTheSameShapeAtTwentyAndAdmitsTwentyOne() {
        Fixture f = new Fixture();
        f.land(f.wide, 20, RequestState.APPLIED);

        assertEquals(20, scopes.instanceWatermark(f.tenant, f.instance));
        assertEquals(0, scopes.maxPlacementWatermark(f.tenant, f.instance),
                "no placement landing happened — the complementary watermark stays untouched");

        assertRefused(admission.admit(ApplyEngineHarness.apply(f.wide, 20)),
                "the comparison is <=, not < — instance-wide fenced by its own, same-shape watermark");
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.wide, 21)));
    }

    @Test
    void aPlacementWatermarkOfTwelveFencesAnInstanceWideEleven() {
        Fixture f = new Fixture();
        ApplyId stagedOnP1 = f.land(f.at(f.p1), 12, RequestState.STAGED);

        assertEquals(12, scopes.maxPlacementWatermark(f.tenant, f.instance));
        assertEquals(0, scopes.instanceWatermark(f.tenant, f.instance),
                "no instance-wide landing has happened yet — the complementary watermark stays 0, "
                        + "evened up with the other cross-scope tests (fix round 1, minor 3)");
        assertRefused(admission.admit(ApplyEngineHarness.apply(f.wide, 11)),
                "an instance-wide apply is fenced by ANY placement's watermark — the direction a "
                        + "per-key index lookup cannot see across the sentinel boundary");
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.wide, 13)));

        // Design §8 row 14, pinned rather than implied: admission supersedes the elder STAGED row on
        // the EXACT scope only, so the placement's STAGED row survives an admitted instance-wide
        // apply. When the architect group decides cross-scope supersession, this is the assertion
        // that must change with it.
        assertEquals(RequestState.STAGED,
                ApplyEngineHarness.requests().find(f.tenant, stagedOnP1).orElseThrow().state(),
                "v1 is exact-scope supersession (design §8 row 14)");
    }

    @Test
    void anotherPlacementIsUnaffectedByTheFirstPlacementsWatermarkThePositiveControl() {
        Fixture f = new Fixture();
        f.land(f.at(f.p1), 12, RequestState.STAGED);

        assertEquals(12, scopes.placementWatermark(f.tenant, f.instance, f.p1),
                "the driving watermark — evened up with the other cross-scope tests (fix round 1, "
                        + "minor 3)");
        assertEquals(0, scopes.placementWatermark(f.tenant, f.instance, f.p2),
                "the complementary watermark — p2 has never landed anything");

        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.at(f.p2), 5)),
                "placement watermarks are per placement — a shared fence would serialize the whole "
                        + "instance, which §2.2 rule 1 deliberately does not do");
    }

    @Test
    void resolvingAnIndeterminateAsAppliedRaisesTheWatermarkImmediately() {
        Fixture f = new Fixture();
        ApplyId stuck = f.admit(f.wide, 20);
        outcomes.start(f.tenant, stuck);
        outcomes.land(f.tenant, stuck, RequestState.APPLY_INDETERMINATE);
        assertEquals(0, scopes.instanceWatermark(f.tenant, f.instance),
                "nothing is claimed while the request is unresolved");

        outcomes.resolveIndeterminate(f.tenant, stuck, new Resolution("duty-operator",
                ResolutionOutcome.APPLIED,
                new ApplyResolutionEvidence(20, List.of(new ReplicaObservation(
                        new ReplicaRef("cache-engine-1"), true, null)))));

        assertEquals(20, scopes.instanceWatermark(f.tenant, f.instance),
                "§2.2 rule 1 names resolved-'applied' alongside STAGED and APPLIED");
        assertRefused(admission.admit(ApplyEngineHarness.apply(f.wide, 20)));
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.wide, 21)));
    }

    @Test
    void resolvingAnIndeterminateAsNotAppliedRaisesNothing() {
        Fixture f = new Fixture();
        ApplyId stuck = f.admit(f.wide, 20);
        outcomes.start(f.tenant, stuck);
        outcomes.land(f.tenant, stuck, RequestState.APPLY_INDETERMINATE);

        outcomes.resolveIndeterminate(f.tenant, stuck, new Resolution("duty-operator",
                ResolutionOutcome.NOT_APPLIED,
                new ApplyResolutionEvidence(20, List.of(new ReplicaObservation(
                        new ReplicaRef("cache-engine-1"), false, null)))));

        assertEquals(0, scopes.instanceWatermark(f.tenant, f.instance),
                "not_applied means nothing landed, so nothing may be fenced");
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.wide, 20)),
                "…and the very same generation must be re-admissible, or the operator's honest "
                        + "'it did not happen' would have cost them the generation");
    }

    /**
     * Minor 3 (R3 fix round 1, `BE-D17`): "a restart resolution raises no watermark" was pinned only
     * incidentally before this test — by the fact that {@code raiseInstanceWatermark(…, long)} would
     * unbox a null {@code generation} and NPE, an accident of boxing rather than a proof, and this
     * path only became reachable once `BE-D17` made a restart's {@code INDETERMINATE_RESOLVED} carry
     * real, well-formed evidence at all. Read the watermark after a fully-evidenced restart
     * resolution with {@code outcome = applied} and assert it did not move.
     */
    @Test
    void resolvingARestartIndeterminateAsAppliedRaisesNoWatermark() {
        Fixture f = new Fixture();
        ApplyId stuck = assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.restart(f.wide)),
                "setup admission must succeed").applyId();
        outcomes.start(f.tenant, stuck);
        outcomes.land(f.tenant, stuck, RequestState.APPLY_INDETERMINATE);

        outcomes.resolveIndeterminate(f.tenant, stuck, new Resolution("duty-operator",
                ResolutionOutcome.APPLIED,
                new RestartResolutionEvidence(List.of(new RestartObservation(
                        new ReplicaRef("cache-engine-1"), true)))));

        assertEquals(RequestState.INDETERMINATE_RESOLVED,
                ApplyEngineHarness.requests().find(f.tenant, stuck).orElseThrow().state());
        assertEquals(0, scopes.instanceWatermark(f.tenant, f.instance),
                "a restart carries no generation to raise a watermark to — operation == APPLY is "
                        + "resolveIndeterminate()'s own gate, proven here rather than left to an "
                        + "accident of boxing");
        assertEquals(0, scopes.maxPlacementWatermark(f.tenant, f.instance),
                "…and no placement watermark either — an instance-wide restart has no placement to "
                        + "raise one for in the first place, but the complementary check costs nothing");
    }

    /**
     * The honest half of obligation (n), fix round 1's Important finding 1. The plan's obligation map
     * marks (n) "engine — no DB artifact for the comparison", and its stated method for every
     * engine-marked obligation is to prove BOTH sides: the service refusing, and a raw repository
     * write the database ACCEPTS. Every test above proves only the first side — a refusal with no
     * proof that nothing else would have refused it is not evidence the fence lives only in the
     * engine.
     *
     * <p>A raw insert of the same generation, going around {@link AdmissionService} entirely, is
     * bound only by the request-level CHECKs ({@code apply_has_generation}, {@code
     * generation_positive}) and the three named fences — none of which mentions a watermark value —
     * so it MUST be accepted even though 5 sits well below the just-raised watermark of 10. If a
     * future migration ever added a monotonicity CHECK or trigger, THIS is the assertion that would
     * turn red and announce the engine's fence had silently become redundant, or worse, out of step
     * with the database.
     */
    @Test
    void obligationNHasNoDatabaseArtifactARawInsertBelowTheWatermarkIsAccepted() {
        Fixture f = new Fixture();
        f.land(f.wide, 10, RequestState.APPLIED);
        assertEquals(10, scopes.instanceWatermark(f.tenant, f.instance));

        assertRefused(admission.admit(ApplyEngineHarness.apply(f.wide, 5)),
                "the engine's own refusal — proven already, restated here as the paired half");

        ApplyRequestRow raw = ApplyEngineHarness.requestedApply(f.wide, 5);
        ApplyEngineHarness.requests().insert(raw);
        assertEquals(RequestState.REQUESTED,
                ApplyEngineHarness.requests().find(f.tenant, raw.applyId()).orElseThrow().state(),
                "the database accepted generation 5 below the watermark of 10 — the fence is "
                        + "entirely the engine's, exactly as obligation (n) is stated");
    }

    @Test
    void aFailedLandingRaisesNoWatermarkEither() {
        Fixture f = new Fixture();
        ApplyId failed = f.admit(f.wide, 30);
        outcomes.start(f.tenant, failed);
        outcomes.land(f.tenant, failed, RequestState.FAILED_NO_EFFECT);

        assertEquals(0, scopes.instanceWatermark(f.tenant, f.instance));
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.wide, 30)),
                "only STAGED, APPLIED and resolved-applied count — a failure must not burn the "
                        + "generation the operator is about to retry");
    }

    // -------------------------------------------------------------------------------------------

    private static void assertRefused(AdmissionOutcome outcome) {
        assertRefused(outcome, "the generation is at or below the fence");
    }

    private static void assertRefused(AdmissionOutcome outcome, String because) {
        AdmissionOutcome.Refused refused = assertInstanceOf(AdmissionOutcome.Refused.class, outcome,
                "expected a refusal, got " + outcome);
        assertEquals(RefusalReason.GENERATION_NOT_ABOVE_WATERMARK, refused.reason(),
                because + " — detail was: " + refused.detail());
    }

    /** A fresh tenant/instance with two placements. */
    private static final class Fixture {

        private final TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        private final InstanceId instance = new InstanceId(UUID.randomUUID());
        private final PlacementId p1 = new PlacementId(UUID.randomUUID());
        private final PlacementId p2 = new PlacementId(UUID.randomUUID());
        private final ScopeKey wide = InventoryFixtures.registered(
                new ScopeKey(tenant, instance, PlacementId.INSTANCE_WIDE));

        ScopeKey at(PlacementId p) {
            // V024 (BE-D48): a placement-scoped request names a placement the registry holds for this instance.
            return InventoryFixtures.registered(new ScopeKey(tenant, instance, p));
        }

        ApplyId admit(ScopeKey scope, long generation) {
            return assertInstanceOf(AdmissionOutcome.Admitted.class,
                    admission.admit(ApplyEngineHarness.apply(scope, generation)),
                    "setup admission must succeed").applyId();
        }

        ApplyId land(ScopeKey scope, long generation, RequestState terminal) {
            ApplyId id = admit(scope, generation);
            outcomes.start(tenant, id);
            outcomes.land(tenant, id, terminal);
            return id;
        }
    }
}
