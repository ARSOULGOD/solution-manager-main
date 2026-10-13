package com.solace.sm.dp.apply.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.Map;
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

    /**
     * Operations an adapter may DECLARE it refuses (plan 015 T3.1): the host-local agent path keeps no pre-image the data plane
     * could restore from and cannot read a file back as bytes. The fixture names the refusals; the kit then asserts each is a
     * coded refusal (or, for the read-only {@code assessRollback}, a contract-legal {@code Unknowable}) AND that nothing moved.
     * An adapter that declares nothing is held to every obligation below, as before.
     */
    public enum Unsupported {
        /** {@code restore} throws the fixture's code; {@code assessRollback} is never {@code Restorable}, only {@code Unknowable}. */
        ROLLBACK,
        /** {@code readConfig} throws the fixture's code. */
        READ_CONFIG,
        /** A second {@code stop} of a stopped replica may report that it acted: the agent path has no "already stopped" signal. */
        STOP_NOOP_REPORT
    }

    /**
     * Everything the kit needs to exercise one real placement of the adapter under test.
     *
     * @param unsupported the operations the adapter declares it refuses, with the classifier each refuses with (empty: none)
     */
    public record Fixture(RuntimeAdapter adapter, PlacementRef placement,
            List<TargetReplica> replicas, ConfigLocation location, boolean healthSignalDeclared,
            ConfigHasher hasher, Map<Unsupported, String> unsupported) {

        public Fixture {
            unsupported = Map.copyOf(unsupported);
        }

        /** A fixture that declares no refusal: every adapter before plan 015. */
        public Fixture(RuntimeAdapter adapter, PlacementRef placement, List<TargetReplica> replicas, ConfigLocation location,
                boolean healthSignalDeclared, ConfigHasher hasher) {
            this(adapter, placement, replicas, location, healthSignalDeclared, hasher, Map.of());
        }

        boolean refuses(Unsupported what) {
            return unsupported.containsKey(what);
        }

        String code(Unsupported what) {
            return unsupported.get(what);
        }
    }

    /** The refusal a declared-unsupported operation answers: a coded {@link RuntimeAdapterException}, nothing else. */
    private static void assertRefusedWithCode(String code, org.junit.jupiter.api.function.Executable call) {
        RuntimeAdapterException refused = assertThrows(RuntimeAdapterException.class, call);
        assertEquals(code, refused.errorClass().token());
    }

    /** A fresh, isolated fixture per test. Implementations create and clean their own runtime. */
    protected abstract Fixture newFixture() throws Exception;

    /**
     * Rendered bytes hashed the way THIS fixture's adapter hashes them: the fixture's own hasher, for the
     * fixture's own tenant. A kit that hashed with a fixed test hasher would pass over an adapter that hashed
     * differently, which is the OR-32 defect (an unkeyed digest) the kit now exists to catch.
     */
    protected static RenderedConfig config(Fixture f, String yaml) {
        return RenderedConfig.of(f.placement().tenant(), yaml.getBytes(StandardCharsets.UTF_8), f.hasher());
    }

    /** A well-formed hash that no fixture in this kit ever writes. */
    protected static String foreignHash(Fixture f) {
        return f.hasher().hash(f.placement().tenant(),
                "a config nobody in this kit renders\n".getBytes(StandardCharsets.UTF_8));
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
        RenderedConfig c = config(f, "materialized: true\n");

        MaterializeResult result = f.adapter().materializeConfig(r, 4, c);

        assertEquals(c.hash(), result.writtenHash());
        assertEquals(c.hash(), f.adapter().status(r).hash().orElseThrow(),
                "status must read back what materialize wrote, or the ladder is fiction");
    }

    /**
     * OR-32: the hash an adapter reports is the one ITS hasher computes for the placement's tenant, over the bytes
     * it read back. Reported by materialize, and by status (the ladder's observation).
     */
    @Test
    void theAdapterReportsBackTheHashItsOwnHasherComputes() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        byte[] bytes = "owned: hash\n".getBytes(StandardCharsets.UTF_8);
        String expected = f.hasher().hash(f.placement().tenant(), bytes);

        MaterializeResult result = f.adapter().materializeConfig(r, 4,
                RenderedConfig.of(f.placement().tenant(), bytes, f.hasher()));

        assertEquals(expected, result.writtenHash());
        assertEquals(expected, f.adapter().status(r).hash().orElseThrow());
    }

    /** {@code SM-D15}/{@code AG-D3}: writing configuration never restarts anything. Ever. */
    @Test
    void materializeHasZeroLifecycleEffect() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        PreBaseline before = f.adapter().status(r).startBaseline().orElseThrow();

        f.adapter().materializeConfig(r, 4, config(f, "materialized: true\n"));

        assertEquals(before, f.adapter().status(r).startBaseline().orElseThrow(),
                "the start baseline must be byte-identical: a config write that moved it would make "
                        + "restart-observed unfalsifiable");
    }

    /** §2.2 rule 2: re-executing under the same key is success, not failure. */
    @Test
    void materializeIsIdempotentUnderTheSameGenerationAndBytes() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RenderedConfig c = config(f, "idempotent: true\n");

        String first = f.adapter().materializeConfig(r, 4, c).writtenHash();
        String second = f.adapter().materializeConfig(r, 4, c).writtenHash();

        assertEquals(first, second);
    }

    // ---- plan 010 T6.8: the reconciler's conditional re-write -----------------------------------

    /**
     * The conditional write behaves as {@code materializeConfig} when the file is still what the caller observed: the bytes
     * land, the hash comes back, and a later read sees them.
     */
    @Test
    void rematerializeIfCurrentWritesWhenTheFileIsStillWhatWasObserved() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RenderedConfig prior = config(f, "prior: true\n");
        RenderedConfig intended = config(f, "intended: true\n");
        f.adapter().materializeConfig(r, 4, prior);

        MaterializeResult result = f.adapter().rematerializeIfCurrent(r, 5, intended, prior.hash());

        assertEquals(intended.hash(), result.writtenHash());
        assertEquals(intended.hash(), f.adapter().status(r).hash().orElseThrow(), "and the read-back sees it");
    }

    /** M-4: the file moved since the observation. Nothing is written, and the refusal says why. */
    @Test
    void rematerializeIfCurrentRefusesAndWritesNothingWhenTheFileMoved() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RenderedConfig theirs = config(f, "somebody else wrote this\n");
        f.adapter().materializeConfig(r, 4, theirs);

        RuntimeAdapterException refused = assertThrows(RuntimeAdapterException.class,
                () -> f.adapter().rematerializeIfCurrent(r, 5, config(f, "intended: true\n"), foreignHash(f)));

        assertEquals("reconcile.target.moved", refused.errorClass().token());
        assertEquals(theirs.hash(), f.adapter().status(r).hash().orElseThrow(),
                "content SM did not write is never overwritten");
    }

    /** A {@code null} expectation means "still absent": a file that has appeared is a moved target. */
    @Test
    void rematerializeIfCurrentExpectingAbsenceRefusesOverAFile() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RenderedConfig there = config(f, "already here\n");
        f.adapter().materializeConfig(r, 4, there);

        RuntimeAdapterException refused = assertThrows(RuntimeAdapterException.class,
                () -> f.adapter().rematerializeIfCurrent(r, 5, config(f, "intended: true\n"), null));

        assertEquals("reconcile.target.moved", refused.errorClass().token());
        assertEquals(there.hash(), f.adapter().status(r).hash().orElseThrow());
    }

    /** {@code SM-D15}/{@code AG-D3}: like every write, it never restarts anything. */
    @Test
    void rematerializeIfCurrentHasZeroLifecycleEffect() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RenderedConfig prior = config(f, "prior: true\n");
        f.adapter().materializeConfig(r, 4, prior);
        PreBaseline before = f.adapter().status(r).startBaseline().orElseThrow();

        f.adapter().rematerializeIfCurrent(r, 5, config(f, "intended: true\n"), prior.hash());

        assertEquals(before, f.adapter().status(r).startBaseline().orElseThrow(),
                "a config write that moved the start baseline would make restart-observed unfalsifiable");
    }

    /**
     * It captures no pre-image: the generation's was retained by the original write, and capturing here could retain content
     * SM did not write. A generation this adapter never materialized therefore still has nothing to restore afterwards.
     */
    @Test
    void rematerializeIfCurrentRetainsNoPreImage() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RenderedConfig prior = config(f, "prior: true\n");
        RenderedConfig intended = config(f, "intended: true\n");
        f.adapter().materializeConfig(r, 4, prior);

        f.adapter().rematerializeIfCurrent(r, 9, intended, prior.hash());

        if (f.refuses(Unsupported.ROLLBACK)) {
            assertInstanceOf(RollbackAssessment.Unknowable.class,
                    f.adapter().assessRollback(f.placement(), 9, intended.hash()),
                    "an adapter that retains no pre-image can never offer a restore target");
            return;
        }
        assertInstanceOf(RollbackAssessment.NothingToRestore.class,
                f.adapter().assessRollback(f.placement(), 9, intended.hash()),
                "generation 9 was never materialized through materializeConfig: it holds no rollback target");
    }

    @Test
    void startOrUpdateMovesTheStartBaselineAndNothingElse() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        f.adapter().materializeConfig(r, 4, config(f, "restarted: true\n"));
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

        if (f.refuses(Unsupported.STOP_NOOP_REPORT)) {
            f.adapter().stop(r);        // idempotent: it succeeds again; whether it says it acted is the declared difference
            assertEquals(Liveness.STOPPED, f.adapter().status(r).liveness());
            return;
        }
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
        RenderedConfig four = config(f, "generation: 4\n");
        RenderedConfig five = config(f, "generation: 5\n");
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 4, four);
        }
        String atFour = f.adapter().status(r).hash().orElseThrow();
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 5, five);
        }
        RuntimeStatus before = f.adapter().status(r);
        assertNotEquals(atFour, before.hash().orElseThrow());

        if (f.refuses(Unsupported.ROLLBACK)) {
            RollbackAssessment.Unknowable unknowable = assertInstanceOf(RollbackAssessment.Unknowable.class,
                    f.adapter().assessRollback(f.placement(), 5, five.hash()));
            assertEquals(f.code(Unsupported.ROLLBACK), unknowable.why().token());
            RuntimeStatus after = f.adapter().status(r);
            assertEquals(before.observedHash(), after.observedHash(), "the declared-unsupported assessment moved nothing");
            assertEquals(before.startBaseline().map(PreBaseline::startedAt), after.startBaseline().map(PreBaseline::startedAt));
            return;
        }

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
        if (f.refuses(Unsupported.ROLLBACK)) {
            assertInstanceOf(RollbackAssessment.Unknowable.class,
                    f.adapter().assessRollback(f.placement(), 99, foreignHash(f)));
            return;
        }
        assertInstanceOf(RollbackAssessment.NothingToRestore.class,
                f.adapter().assessRollback(f.placement(), 99, foreignHash(f)));
    }

    /**
     * The case {@code hash_on_config_steps} cannot express: a first apply, whose prior state was the
     * ABSENCE of a file, has no hash for a {@code ROLLBACK} intent to carry. The adapter must say so
     * rather than offer a restore the engine is forbidden to journal ({@code BE-D22}).
     */
    @Test
    void aRestoreTargetOfAbsenceIsUnknowableRatherThanRestorable() throws Exception {
        Fixture f = newFixture();
        RenderedConfig first = config(f, "first: true\n");
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 6, first);   // nothing was there before
        }

        if (f.refuses(Unsupported.ROLLBACK)) {
            assertEquals(f.code(Unsupported.ROLLBACK), assertInstanceOf(RollbackAssessment.Unknowable.class,
                    f.adapter().assessRollback(f.placement(), 6, first.hash())).why().token());
            return;
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
        RenderedConfig four = config(f, "generation: 4\n");
        RenderedConfig five = config(f, "generation: 5\n");
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 4, four);
        }
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 5, five);
        }

        if (f.refuses(Unsupported.ROLLBACK)) {
            assertEquals(f.code(Unsupported.ROLLBACK), assertInstanceOf(RollbackAssessment.Unknowable.class,
                    f.adapter().assessRollback(f.placement(), 5, foreignHash(f))).why().token());
            return;
        }
        // The engine hands in an intendedHash that is NOT what the replicas hold, so what they DO
        // hold is, from the journal's point of view, a third and unrecognized value.
        RollbackAssessment.Unknowable unknowable = assertInstanceOf(RollbackAssessment.Unknowable.class,
                f.adapter().assessRollback(f.placement(), 5, foreignHash(f)));
        assertEquals("rollback.state.unknown", unknowable.why().token());
    }

    @Test
    void restorePutsTheTargetHashBackOnEveryReplicaAndIsIdempotent() throws Exception {
        Fixture f = newFixture();
        RenderedConfig four = config(f, "generation: 4\n");
        RenderedConfig five = config(f, "generation: 5\n");
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 4, four);
        }
        String atFour = f.adapter().status(f.replicas().get(0)).hash().orElseThrow();
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 5, five);
        }

        if (f.refuses(Unsupported.ROLLBACK)) {
            assertRefusedWithCode(f.code(Unsupported.ROLLBACK), () -> f.adapter().restore(f.placement(), 5, atFour));
            for (TargetReplica replica : f.replicas()) {
                assertEquals(five.hash(), f.adapter().status(replica).hash().orElseThrow(), "a refused restore wrote nothing");
            }
            return;
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
        RenderedConfig four = config(f, "generation: 4\n");
        RenderedConfig five = config(f, "generation: 5\n");
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 4, four);
        }
        for (TargetReplica replica : f.replicas()) {
            f.adapter().materializeConfig(replica, 5, five);
        }
        String beforeRestore = f.adapter().status(f.replicas().get(0)).hash().orElseThrow();

        if (f.refuses(Unsupported.ROLLBACK)) {
            assertRefusedWithCode(f.code(Unsupported.ROLLBACK), () -> f.adapter().restore(f.placement(), 5, foreignHash(f)));
            assertEquals(beforeRestore, f.adapter().status(f.replicas().get(0)).hash().orElseThrow());
            return;
        }
        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                () -> f.adapter().restore(f.placement(), 5, foreignHash(f)));
        assertEquals("rollback.preimage.altered", e.errorClass().token());
        assertEquals(beforeRestore, f.adapter().status(f.replicas().get(0)).hash().orElseThrow(),
                "a restore that cannot prove its target writes nothing at all");
    }

    // ---- readConfig (BE-D39, D-ACCEPT): the tenth, READ-ONLY operation ---------------------------------------------

    /** The bytes the host holds, and the keyed hash of exactly those bytes, for the placement's tenant. */
    @Test
    void readConfigReturnsTheBytesTheHostHoldsAndTheKeyedHashOfThem() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RenderedConfig c = config(f, "read: back\nsecret: hunter2\n");
        f.adapter().materializeConfig(r, 4, c);

        if (f.refuses(Unsupported.READ_CONFIG)) {
            assertRefusedWithCode(f.code(Unsupported.READ_CONFIG), () -> f.adapter().readConfig(r));
            return;
        }
        try (ConfigReadBack back = f.adapter().readConfig(r)) {
            assertArrayEquals(c.bytes(), back.bytes());
            assertEquals(f.hasher().hash(f.placement().tenant(), c.bytes()), back.hash(),
                    "the hash is the DP's keyed one over the bytes read, never one computed on the host");
            assertEquals(c.hash(), back.hash());
        }
    }

    /** Read-only, structurally: no generation, no pre-image, no lifecycle, no write of any kind. */
    @Test
    void readConfigNeverMutatesTheTarget() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        f.adapter().materializeConfig(r, 4, config(f, "before: read\n"));
        RuntimeStatus before = f.adapter().status(r);

        if (f.refuses(Unsupported.READ_CONFIG)) {
            assertRefusedWithCode(f.code(Unsupported.READ_CONFIG), () -> f.adapter().readConfig(r));
        } else {
            f.adapter().readConfig(r).close();
        }

        RuntimeStatus after = f.adapter().status(r);
        assertEquals(before.observedHash(), after.observedHash());
        assertEquals(before.liveness(), after.liveness());
        assertEquals(before.startBaseline().map(PreBaseline::startedAt), after.startBaseline().map(PreBaseline::startedAt),
                "a read is not a lifecycle operation");
    }

    /** Two reads of an unchanged file agree, and closing one does not disturb the next (the buffer is per call). */
    @Test
    void aSecondReadAfterTheFirstIsClosedStillSeesTheFile() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RenderedConfig c = config(f, "twice: read\n");
        f.adapter().materializeConfig(r, 4, c);

        if (f.refuses(Unsupported.READ_CONFIG)) {
            assertRefusedWithCode(f.code(Unsupported.READ_CONFIG), () -> f.adapter().readConfig(r));
            assertRefusedWithCode(f.code(Unsupported.READ_CONFIG), () -> f.adapter().readConfig(r));
            return;
        }
        ConfigReadBack first = f.adapter().readConfig(r);
        first.close();
        try (ConfigReadBack second = f.adapter().readConfig(r)) {
            assertArrayEquals(c.bytes(), second.bytes());
        }
    }

    /** A replica with no file is a classified refusal, not an empty read: an empty file and an absent one differ. */
    @Test
    void readConfigOfAnAbsentFileIsRefusedWithAClassifier() throws Exception {
        Fixture f = newFixture();
        if (f.refuses(Unsupported.READ_CONFIG)) {
            assertRefusedWithCode(f.code(Unsupported.READ_CONFIG), () -> f.adapter().readConfig(f.replicas().get(0)));
            return;
        }
        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                () -> f.adapter().readConfig(f.replicas().get(0)));
        assertEquals("config.read.absent", e.errorClass().token());
    }
}
