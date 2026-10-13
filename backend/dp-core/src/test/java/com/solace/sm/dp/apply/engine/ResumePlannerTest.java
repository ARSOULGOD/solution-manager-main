package com.solace.sm.dp.apply.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.Uuid7;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Design §2.2 rule 3, cell by cell: the fourteen rows the plan specified, five more the enums
 * admit and the table did not name, and one test that the table is <b>total</b>.
 *
 * <p>Driving this through a database and a daemon would make exhaustive coverage unaffordable,
 * which is exactly why {@link ResumePlanner#decide} takes rows and observations and returns a
 * verdict. {@code ResumeIT} then proves the wiring on the cells that cannot be faked.
 */
class ResumePlannerTest {

    private static final String TARGET = "sha256:" + "a".repeat(64);
    private static final String INTENDED = "sha256:" + "b".repeat(64);
    private static final String STRANGER = "sha256:" + "c".repeat(64);
    private static final Instant T0 = Instant.parse("2026-09-14T00:00:00Z");

    private final ResumePlanner planner = new ResumePlanner();

    // ---- "no attempt row" is one rule for every step -------------------------------------------

    /** §2.2 rule 3: "an intent with no attempt row provably issued nothing … never indeterminate." */
    @Test
    void anIntentWithNoAttemptRowIsRerunWhateverTheStepAndWhateverIsObserved() {
        for (Step step : Step.values()) {
            assertEquals(StepDecision.RERUN,
                    planner.decide(intent(step, hashFor(step)), Optional.empty(),
                            List.of(status(STRANGER, moved(), Liveness.RUNNING)), INTENDED),
                    step + " with no attempt row must be RERUN: rule 0 committed the intent before "
                            + "the action, so the absence of an attempt PROVES nothing was issued");
        }
    }

    // ---- MATERIALIZE -----------------------------------------------------------------------------

    @Test
    void aMaterializeWithNothingAtThePathIsRerun() {
        assertEquals(StepDecision.RERUN, decide(Step.MATERIALIZE, TARGET, openAttempt(null),
                List.of(status(null, moved(), Liveness.RUNNING))));
    }

    @Test
    void aMaterializeWhoseHashMatchesTheIntentIsContinue() {
        assertEquals(StepDecision.CONTINUE, decide(Step.MATERIALIZE, TARGET, openAttempt(null),
                List.of(status(TARGET, moved(), Liveness.RUNNING))));
    }

    @Test
    void aMaterializeWithAnUnexpectedHashIsIndeterminateAndNeverOverwritten() {
        assertEquals(StepDecision.INDETERMINATE, decide(Step.MATERIALIZE, TARGET, openAttempt(null),
                List.of(status(STRANGER, moved(), Liveness.RUNNING))),
                "bytes present with an unexpected hash are a competing writer or a partial write; "
                        + "B/D12 forbids silently overwriting either");
    }

    // ---- LIFECYCLE -------------------------------------------------------------------------------

    @Test
    void aLifecycleWhoseBaselineMovedIsContinue() {
        assertEquals(StepDecision.CONTINUE, decide(Step.LIFECYCLE, null, openAttempt(baseline(T0)),
                List.of(status(TARGET, moved(), Liveness.RUNNING))));
    }

    @Test
    void aLifecycleWhoseBaselineIsUnchangedIsReissued() {
        assertEquals(StepDecision.REISSUE, decide(Step.LIFECYCLE, null, openAttempt(baseline(T0)),
                List.of(status(TARGET, baseline(T0), Liveness.RUNNING))),
                "the restart never happened, and re-issuing it under the same key is idempotent");
    }

    /**
     * The single most consequential cell in the table: the difference between "we do not know" and
     * "restart it again". Re-issuing a restart the operator may not have authorized twice is the
     * exact guess {@code APPLY_INDETERMINATE} exists to refuse.
     */
    @Test
    void aLifecycleWithAnAbsentBaselineIsIndeterminateAndNeverReissued() {
        assertEquals(StepDecision.INDETERMINATE, decide(Step.LIFECYCLE, null,
                openAttempt(baseline(T0)), List.of(status(TARGET, null, Liveness.RUNNING))));
    }

    // ---- the read-only steps ----------------------------------------------------------------------

    @Test
    void aPreflightIsAlwaysRerunBecauseItCannotMutate() {
        assertEquals(StepDecision.RERUN, decide(Step.PREFLIGHT, null, openAttempt(null),
                List.of(status(STRANGER, null, Liveness.ABSENT))));
    }

    @Test
    void aRollbackAssessIsAlwaysRerunBecauseBeD22MadeItReadOnly() {
        assertEquals(StepDecision.RERUN, decide(Step.ROLLBACK_ASSESS, null, openAttempt(null),
                List.of(status(STRANGER, null, Liveness.ABSENT))));
    }

    @Test
    void aVerifyIsAlwaysRerun() {
        assertEquals(StepDecision.RERUN, decide(Step.VERIFY, TARGET, openAttempt(null),
                List.of(status(STRANGER, null, Liveness.ABSENT))));
    }

    /** The three read-only steps answer without an observation at all — a crash in the very first
     *  step leaves no MATERIALIZE intent to name a replica with, so there is nothing to observe. */
    @Test
    void theReadOnlyStepsAnswerEvenWithNothingObserved() {
        for (Step step : List.of(Step.PREFLIGHT, Step.ROLLBACK_ASSESS, Step.VERIFY)) {
            assertEquals(StepDecision.RERUN,
                    decide(step, hashFor(step), openAttempt(null), List.of()));
        }
    }

    // ---- STOP ---------------------------------------------------------------------------------------

    @Test
    void aStopWhoseReplicaIsStoppedIsContinue() {
        assertEquals(StepDecision.CONTINUE, decide(Step.STOP, null, openAttempt(null),
                List.of(status(TARGET, null, Liveness.STOPPED))));
    }

    @Test
    void aStopWhoseReplicaIsStillRunningIsReissued() {
        assertEquals(StepDecision.REISSUE, decide(Step.STOP, null, openAttempt(null),
                List.of(status(TARGET, moved(), Liveness.RUNNING))));
    }

    /** The class the table did not name. A replica that is gone is not running. */
    @Test
    void aStopWhoseReplicaIsAbsentIsContinue() {
        assertEquals(StepDecision.CONTINUE, decide(Step.STOP, null, openAttempt(null),
                List.of(status(null, null, Liveness.ABSENT))));
    }

    // ---- ROLLBACK — the three cells BE-D22's hash-bearing intent made decidable ----------------------

    @Test
    void aRollbackWhereEveryReplicaHoldsTheIntentsOwnHashIsContinue() {
        assertEquals(StepDecision.CONTINUE, decide(Step.ROLLBACK, TARGET, openAttempt(null),
                List.of(status(TARGET, null, Liveness.RUNNING),
                        status(TARGET, null, Liveness.RUNNING))),
                "the restore landed. The target is ON THE INTENT ROW, written before the "
                        + "destructive call, so this is a comparison and not a guess");
    }

    @Test
    void aRollbackShowingTheTargetAndTheIntendedHashTogetherIsReissued() {
        assertEquals(StepDecision.REISSUE, decide(Step.ROLLBACK, TARGET, openAttempt(null),
                List.of(status(TARGET, null, Liveness.RUNNING),
                        status(INTENDED, null, Liveness.RUNNING))),
                "both values are ones this apply wrote, so re-issuing is safe: restoring identical "
                        + "bytes twice is the same write");
    }

    @Test
    void aRollbackShowingAThirdValueIsIndeterminate() {
        assertEquals(StepDecision.INDETERMINATE, decide(Step.ROLLBACK, TARGET, openAttempt(null),
                List.of(status(TARGET, null, Liveness.RUNNING),
                        status(STRANGER, null, Liveness.RUNNING))),
                "somebody else wrote while the DP was down. The engine reports and stops; it does "
                        + "not overwrite (B/D12)");
    }

    @Test
    void aRollbackWithAnUnreadableReplicaIsIndeterminate() {
        assertEquals(StepDecision.INDETERMINATE, decide(Step.ROLLBACK, TARGET, openAttempt(null),
                List.of(status(TARGET, null, Liveness.RUNNING),
                        status(null, null, Liveness.ABSENT))));
    }

    /**
     * The cell that would exist without {@code BE-D22}: a {@code ROLLBACK} intent carrying no
     * target. Unreachable through the database — {@code hash_on_config_steps} refuses it — and
     * answered here anyway, because a decision table with a hole is not one.
     */
    @Test
    void aRollbackIntentWithNoTargetHashIsIndeterminateRatherThanAGuess() {
        assertEquals(StepDecision.INDETERMINATE, decide(Step.ROLLBACK, null, openAttempt(null),
                List.of(status(TARGET, null, Liveness.RUNNING))));
    }

    // ---- totality --------------------------------------------------------------------------------------

    /**
     * Every {@code (Step × attempt-presence × observation-class)} the enums admit returns a
     * decision. A {@code switch} with a permissive {@code default} is how a decision table stops
     * being one, so the second half pins the ANSWER for every step under one canonical observation:
     * a branch deleted in favour of a default changes one of these, and nothing else would notice.
     */
    @Test
    void theTableIsTotalAndNoStepFallsThroughToADefault() {
        List<String> hashes = Arrays.asList(null, TARGET, INTENDED, STRANGER);
        List<PreBaseline> baselines = Arrays.asList(null, baseline(T0), moved());
        int combinations = 0;
        for (Step step : Step.values()) {
            for (Optional<AttemptRow> open : List.of(Optional.<AttemptRow>empty(),
                    Optional.of(openAttempt(baseline(T0))))) {
                for (String hash : hashes) {
                    for (PreBaseline seen : baselines) {
                        for (Liveness liveness : Liveness.values()) {
                            List<RuntimeStatus> observed =
                                    List.of(status(hash, seen, liveness));
                            assertNotNull(planner.decide(intent(step, hashFor(step)), open,
                                    observed, INTENDED),
                                    step + " / open=" + open.isPresent() + " / hash=" + hash
                                            + " / baseline=" + seen + " / " + liveness);
                            combinations++;
                        }
                    }
                }
            }
        }
        assertEquals(Step.values().length * 2 * 4 * 3 * 3, combinations);

        // The canonical observation: nothing at the path, the baseline unchanged, still running.
        RuntimeStatus canonical = status(null, baseline(T0), Liveness.RUNNING);
        record Cell(Step step, StepDecision expected) {
        }
        List<Cell> table = List.of(
                new Cell(Step.PREFLIGHT, StepDecision.RERUN),
                new Cell(Step.MATERIALIZE, StepDecision.RERUN),
                new Cell(Step.LIFECYCLE, StepDecision.REISSUE),
                new Cell(Step.VERIFY, StepDecision.RERUN),
                new Cell(Step.ROLLBACK, StepDecision.INDETERMINATE),
                new Cell(Step.ROLLBACK_ASSESS, StepDecision.RERUN),
                new Cell(Step.STOP, StepDecision.REISSUE));
        assertEquals(Step.values().length, table.size(),
                "a new Step must be given a row here, not swallowed by a default");
        List<String> actual = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (Cell cell : table) {
            actual.add(cell.step() + "=" + decide(cell.step(), hashFor(cell.step()),
                    openAttempt(baseline(T0)), List.of(canonical)));
            expected.add(cell.step() + "=" + cell.expected());
        }
        assertEquals(expected, actual);
    }

    // ---- fixtures ----------------------------------------------------------------------------------------

    private StepDecision decide(Step step, String configHash, AttemptRow open,
            List<RuntimeStatus> observed) {
        return planner.decide(intent(step, configHash), Optional.of(open), observed, INTENDED);
    }

    /** {@code hash_on_config_steps}: MATERIALIZE, VERIFY and ROLLBACK carry one; nothing else may. */
    private static String hashFor(Step step) {
        return step.carriesHash() ? TARGET : null;
    }

    private static StepIntentRow intent(Step step, String configHash) {
        ReplicaRef replica = step.placementScoped() ? ReplicaRef.PLACEMENT : new ReplicaRef("r1");
        return new StepIntentRow(Uuid7.next(), ApplyId.next(), new PlacementId(UUID.randomUUID()), step, replica,
                "key-" + step,
                configHash, T0);
    }

    private static AttemptRow openAttempt(PreBaseline preBaseline) {
        return new AttemptRow(Uuid7.next(), Uuid7.next(),
                preBaseline == null ? Step.MATERIALIZE : Step.LIFECYCLE, (short) 1, preBaseline,
                T0, null, T0.plus(Duration.ofMinutes(5)), null, null, null, null);
    }

    private static PreBaseline baseline(Instant at) {
        return new PreBaseline(at, "r1");
    }

    private static PreBaseline moved() {
        return new PreBaseline(T0.plusSeconds(60), "r1");
    }

    private static RuntimeStatus status(String hash, PreBaseline baseline, Liveness liveness) {
        return new RuntimeStatus(liveness, HealthSignal.HEALTHY, hash, baseline,
                RuntimeRef.of(RuntimeRef.Key.HOST, "fixture"));
    }
}
