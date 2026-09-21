package com.solace.sm.dp.apply.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.RuntimeRef;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The obligations every {@link RuntimeAdapter} owes, regardless of runtime — the
 * {@code AbstractEventPublisherContractTest} idiom (design §5.2), applied to the second seam this
 * backend has.
 *
 * <p>A new adapter ships <b>with</b> a subclass of this class. {@code PlaneBoundaryTest}'s adapter
 * inventory records the promise; this class is what makes it cost something to break.
 *
 * <p><b>What a systemd or Kubernetes adapter inherits, and what it must decide</b> (design §2.3's
 * three-runtime walk-through, which this kit is the executable half of):
 *
 * <ul>
 *   <li><b>systemd</b> reuses {@link ConfigFileStore} unchanged — a real host directory has the same
 *       file semantics as a bind mount. It owes: unit-name resolution for {@code prepare}, the
 *       {@code systemctl} verbs, and a start-time source for {@link RuntimeStatus#baseline()}.
 *   <li><b>Kubernetes</b> does <b>not</b> reuse {@link ConfigFileStore}: {@code SM-D25} makes
 *       materialization a new generation-named immutable ConfigMap, one per generation rather than
 *       one per replica, so the per-replica calls after the first verify-and-succeed under §2.2
 *       rule 2. It owes the {@code SM-D25} GC rule and the {@code B/D13} disposition: the platform
 *       sequences pods, SM still owns the verdict.
 * </ul>
 *
 * <p>Neither is in scope for plan 005. Both pass this kit unchanged or the seam was not generic,
 * which is the claim §2.3 makes at design time and this class is where it becomes checkable.
 */
public abstract class AbstractRuntimeAdapterContractTest {

    /** Everything the kit needs to exercise one real placement of the adapter under test. */
    public record Fixture(RuntimeAdapter adapter, PlacementRef placement,
            List<TargetReplica> replicas, ConfigLocation location, boolean healthSignalDeclared) {
    }

    /** A fresh, isolated fixture per test. Implementations create and clean their own runtime. */
    protected abstract Fixture newFixture() throws Exception;

    protected static RenderedConfig config(String yaml) {
        return RenderedConfig.of(yaml.getBytes(StandardCharsets.UTF_8));
    }

    /** A well-formed hash that no fixture in this kit ever writes. */
    protected static String foreignHash() {
        return RenderedConfig.of("a config nobody in this kit renders\n"
                .getBytes(StandardCharsets.UTF_8)).hash();
    }

    @Test
    void prepareIsReadyAndNamesEveryReplicaItWillAddress() throws Exception {
        Fixture f = newFixture();
        PreflightResult.Ready ready =
                assertInstanceOf(PreflightResult.Ready.class, f.adapter().prepare(f.placement(), 1));
        assertEquals(f.replicas(), ready.replicas());
        assertEquals(f.healthSignalDeclared(), ready.healthSignalDeclared(),
                "the gate's own precondition is an observation, not a configuration flag");
        assertNotNull(ready.runtimeRef());
    }

    @Test
    void prepareNeverMutatesTheTarget() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RuntimeStatus before = f.adapter().status(r);
        f.adapter().prepare(f.placement(), 1);
        RuntimeStatus after = f.adapter().status(r);
        assertEquals(before.liveness(), after.liveness());
        assertEquals(before.observedHash(), after.observedHash());
        assertEquals(before.startBaseline().map(PreBaseline::startedAt),
                after.startBaseline().map(PreBaseline::startedAt),
                "prepare returns a plan or a refusal; it is not a lifecycle operation");
    }

    @Test
    void materializeWritesTheBytesAndReportsTheirHash() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RenderedConfig c = config("materialized: true\n");

        MaterializeResult result = f.adapter().materializeConfig(r, 4, c);

        assertEquals(c.hash(), result.writtenHash());
        assertEquals(c.hash(), f.adapter().status(r).hash().orElseThrow(),
                "status must read back what materialize wrote, or the ladder is fiction");
    }

    /** {@code SM-D15}/{@code AG-D3}: writing configuration never restarts anything. Ever. */
    @Test
    void materializeHasZeroLifecycleEffect() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        PreBaseline before = f.adapter().status(r).startBaseline().orElseThrow();

        f.adapter().materializeConfig(r, 4, config("materialized: true\n"));

        assertEquals(before, f.adapter().status(r).startBaseline().orElseThrow(),
                "the start baseline must be byte-identical: a config write that moved it would make "
                        + "restart-observed unfalsifiable");
    }

    /** §2.2 rule 2: re-executing under the same key is success, not failure. */
    @Test
    void materializeIsIdempotentUnderTheSameGenerationAndBytes() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RenderedConfig c = config("idempotent: true\n");

        String first = f.adapter().materializeConfig(r, 4, c).writtenHash();
        String second = f.adapter().materializeConfig(r, 4, c).writtenHash();

        assertEquals(first, second);
    }

    @Test
    void startOrUpdateMovesTheStartBaselineAndNothingElse() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        f.adapter().materializeConfig(r, 4, config("restarted: true\n"));
        RuntimeStatus before = f.adapter().status(r);

        LifecycleResult result = f.adapter().startOrUpdate(r, 4);
        assertTrue(result.issued());

        RuntimeStatus after = f.adapter().status(r);
        assertNotEquals(before.startBaseline().orElseThrow().startedAt(),
                after.startBaseline().orElseThrow().startedAt(),
                "restart-observed is a baseline comparison; without a moving baseline there is "
                        + "nothing to observe");
        assertEquals(before.observedHash(), after.observedHash(),
                "a restart reads config, it does not rewrite it");
        assertEquals(Liveness.RUNNING, after.liveness());
    }

    /**
     * {@code BE-D25}'s whole content, as an obligation: {@code replicas} and {@code prepare} must
     * agree.
     *
     * <p>An adapter free to answer these differently would let a stop address a set the apply
     * engine never validated — which is the failure the operation is added to avoid, not a
     * tidiness rule. {@code prepare} is "{@code replicas} plus the per-apply validation", and this
     * is the "plus" being checked.
     */
    @Test
    void replicasAgreesWithPrepareAndMutatesNothing() throws Exception {
        Fixture f = newFixture();
        PreflightResult.Ready ready =
                (PreflightResult.Ready) f.adapter().prepare(f.placement(), 3);

        List<TargetReplica> observed = f.adapter().replicas(f.placement());

        assertEquals(ready.replicas(), observed,
                "replicas() must return the same set prepare() does, in the same order: a stop "
                        + "addresses what replicas() says, and an apply what prepare() says");

        // Read-only, and asserted the same way assessRollback's read-onlyness is: nothing the
        // runtime holds may differ afterwards.
        for (TargetReplica r : observed) {
            RuntimeStatus before = f.adapter().status(r);
            f.adapter().replicas(f.placement());
            RuntimeStatus after = f.adapter().status(r);
            assertEquals(before.observedHash(), after.observedHash(), "replicas() wrote config");
            assertEquals(before.liveness(), after.liveness(), "replicas() changed liveness");
            assertEquals(before.startBaseline().map(b -> b.startedAt()),
                    after.startBaseline().map(b -> b.startedAt()), "replicas() restarted something");
        }
    }

    /**
     * {@code SM-D2} / {@code I1}, executable: deployment is the customer's CI/CD. An adapter that
     * pulled, replaced or re-tagged an artifact would change the image identity across a restart.
     */
    @Test
    void startOrUpdateNeverChangesTheArtifact() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RuntimeRef.Key key = RuntimeRef.Key.IMAGE_DIGEST;
        String before = f.adapter().status(r).runtimeRef().fields().get(key);
        Assumptions.assumeTrue(before != null,
                "this runtime does not express an artifact identity; the rule is vacuous for it");

        f.adapter().startOrUpdate(r, 4);

        assertEquals(before, f.adapter().status(r).runtimeRef().fields().get(key),
                "the artifact identity must be unchanged across a restart");
    }

    @Test
    void stopIsIdempotentAndReportsWhetherItActed() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);

        assertTrue(f.adapter().stop(r).issued());
        assertEquals(Liveness.STOPPED, f.adapter().status(r).liveness());

        assertFalse(f.adapter().stop(r).issued(),
                "a replica already in the desired state reports success without acting (§2.2 rule 2)");
    }

    /** §2.2 rule 3's trigger: a health signal the runtime does not publish is NOT_DECLARED. */
    @Test
    void theHealthSignalIsNeverInventedWhileTheReplicaIsDown() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        f.adapter().stop(r);

        assertNotEquals(HealthSignal.HEALTHY, f.adapter().status(r).health(),
                "a stopped replica is never reported healthy — Docker's own State.Health goes STALE "
                        + "rather than absent, measured, which is exactly the trap this asserts against");
    }

    // ---- BE-D22: the exhaustion pair ------------------------------------------------------------

    /**
     * {@code BE-D13} §3.1's step-label-truthfulness invariant — "a restore journals {@code ROLLBACK},
     * never {@code ROLLBACK_ASSESS}" — whose enforcement home that table names as "adapter contract
     * tests, per adapter". This is that test. An {@code assessRollback} that restored would make the
     * engine's hashless {@code ROLLBACK_ASSESS} intent a lie, and no database CHECK can see it.
     */
    @Test
    void assessRollbackNamesTheRestoreTargetAndMutatesNothing() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RenderedConfig four = config("generation: 4\n");
        RenderedConfig five = config("generation: 5\n");
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 4, four);
        }
        String atFour = f.adapter().status(r).hash().orElseThrow();
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 5, five);
        }
        RuntimeStatus before = f.adapter().status(r);
        assertNotEquals(atFour, before.hash().orElseThrow());

        RollbackAssessment.Restorable assessment = assertInstanceOf(RollbackAssessment.Restorable.class,
                f.adapter().assessRollback(f.placement(), 5, five.hash()));
        assertEquals(atFour, assessment.targetHash(),
                "the assessment names the hash the engine will journal as the ROLLBACK intent's "
                        + "config_hash BEFORE the destructive call");

        RuntimeStatus after = f.adapter().status(r);
        assertEquals(before.observedHash(), after.observedHash(), "assessRollback restored nothing");
        assertEquals(before.startBaseline().map(PreBaseline::startedAt),
                after.startBaseline().map(PreBaseline::startedAt),
                "…and issued no lifecycle action either");
    }

    @Test
    void assessRollbackOfAGenerationNeverWrittenReportsNothingToRestore() throws Exception {
        Fixture f = newFixture();
        assertInstanceOf(RollbackAssessment.NothingToRestore.class,
                f.adapter().assessRollback(f.placement(), 99, foreignHash()));
    }

    /**
     * The case {@code hash_on_config_steps} cannot express: a first apply, whose prior state was the
     * ABSENCE of a file, has no hash for a {@code ROLLBACK} intent to carry. The adapter must say so
     * rather than offer a restore the engine is forbidden to journal ({@code BE-D22}).
     */
    @Test
    void aRestoreTargetOfAbsenceIsUnknowableRatherThanRestorable() throws Exception {
        Fixture f = newFixture();
        RenderedConfig first = config("first: true\n");
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 6, first);   // nothing was there before
        }

        RollbackAssessment.Unknowable unknowable = assertInstanceOf(RollbackAssessment.Unknowable.class,
                f.adapter().assessRollback(f.placement(), 6, first.hash()));
        assertEquals("rollback.target.absent", unknowable.why().token());
    }

    /**
     * {@code BE-D22} correction A. Exactly two values are journal-known: the rollback target and the
     * {@code MATERIALIZE} intent's own hash. A replica holding anything else means somebody who is
     * not this apply wrote the file, and {@code B/D12} forbids reverting them.
     */
    @Test
    void aValueThisApplyNeverWroteIsUnknowableRatherThanRestorable() throws Exception {
        Fixture f = newFixture();
        RenderedConfig four = config("generation: 4\n");
        RenderedConfig five = config("generation: 5\n");
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 4, four);
        }
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 5, five);
        }

        // The engine hands in an intendedHash that is NOT what the replicas hold, so what they DO
        // hold is, from the journal's point of view, a third and unrecognized value.
        RollbackAssessment.Unknowable unknowable = assertInstanceOf(RollbackAssessment.Unknowable.class,
                f.adapter().assessRollback(f.placement(), 5, foreignHash()));
        assertEquals("rollback.state.unknown", unknowable.why().token());
    }

    @Test
    void restorePutsTheTargetHashBackOnEveryReplicaAndIsIdempotent() throws Exception {
        Fixture f = newFixture();
        RenderedConfig four = config("generation: 4\n");
        RenderedConfig five = config("generation: 5\n");
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 4, four);
        }
        String atFour = f.adapter().status(f.replicas().get(0)).hash().orElseThrow();
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 5, five);
        }

        f.adapter().restore(f.placement(), 5, atFour);
        for (TargetReplica replica : f.replicas()) {
            assertEquals(atFour, f.adapter().status(replica).hash().orElseThrow());
        }

        // §2.2 rule 2, and exactly what resume does when a crash lands between the restore and its
        // close: re-executing under the same key is success.
        f.adapter().restore(f.placement(), 5, atFour);
        for (TargetReplica replica : f.replicas()) {
            assertEquals(atFour, f.adapter().status(replica).hash().orElseThrow());
        }
    }

    /**
     * {@code BE-D22} correction B: {@code restore} re-checks its own target before writing. Handing
     * it a hash the retained pre-image does not match stands in for "the world moved between assess
     * and restore" — and nothing may be written.
     */
    @Test
    void restoreWithAHashThatIsNotTheTargetRefusesAndWritesNothing() throws Exception {
        Fixture f = newFixture();
        RenderedConfig four = config("generation: 4\n");
        RenderedConfig five = config("generation: 5\n");
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 4, four);
        }
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 5, five);
        }
        String beforeRestore = f.adapter().status(f.replicas().get(0)).hash().orElseThrow();

        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                () -> f.adapter().restore(f.placement(), 5, foreignHash()));
        assertEquals("rollback.preimage.altered", e.errorClass().token());
        assertEquals(beforeRestore, f.adapter().status(f.replicas().get(0)).hash().orElseThrow(),
                "a restore that cannot prove its target writes nothing at all");
    }
}
