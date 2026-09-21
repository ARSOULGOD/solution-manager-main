package com.solace.sm.dp.apply.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link RequestState#validFor} against {@code state_fits_operation}, all 36 pairs.
 *
 * <p>The CHECK, from design §3.1, verbatim:
 * <pre>{@code
 * CONSTRAINT state_fits_operation CHECK (CASE operation
 *         WHEN 'stop'    THEN state IN ('REQUESTED','RUNNING','STOPPED','STOP_FAILED')
 *         WHEN 'restart' THEN state NOT IN ('STAGED','STOPPED','STOP_FAILED')
 *         ELSE                state NOT IN ('STOPPED','STOP_FAILED') END)
 * }</pre>
 *
 * <p>The expectation below is written out as three explicit SETS rather than by re-running the
 * predicate's own logic, because a table that computes the answer the same way the code does proves
 * nothing. Transcribing the CHECK by hand is the point: the two spellings are compared, and
 * {@code OutcomeTransitionsIT} then proves the database agrees with this transcription on live rows.
 */
class RequestStateTest {

    /** {@code WHEN 'stop' THEN state IN (…)} — an allow-list, transcribed. */
    private static final Set<RequestState> STOP = EnumSet.of(RequestState.REQUESTED,
            RequestState.RUNNING, RequestState.STOPPED, RequestState.STOP_FAILED);

    /** {@code WHEN 'restart' THEN state NOT IN (…)} — a deny-list, transcribed. */
    private static final Set<RequestState> RESTART_DENIED = EnumSet.of(RequestState.STAGED,
            RequestState.STOPPED, RequestState.STOP_FAILED);

    /** {@code ELSE state NOT IN (…)} — the apply branch, transcribed. */
    private static final Set<RequestState> APPLY_DENIED =
            EnumSet.of(RequestState.STOPPED, RequestState.STOP_FAILED);

    @Test
    void everyStateAndOperationPairAgreesWithTheCheckAsTranscribed() {
        for (RequestState state : RequestState.values()) {
            assertEquals(STOP.contains(state), state.validFor(Operation.STOP),
                    "stop / " + state);
            assertEquals(!RESTART_DENIED.contains(state), state.validFor(Operation.RESTART),
                    "restart / " + state);
            assertEquals(!APPLY_DENIED.contains(state), state.validFor(Operation.APPLY),
                    "apply / " + state);
        }
        assertEquals(12, RequestState.values().length,
                "design §3.2 has twelve states; a thirteenth needs a row in the table above and a "
                        + "migration to state_known and state_fits_operation");
    }

    @Test
    void theThreeOperationsDisagreeInExactlyThePlacesTheCheckDoes() {
        // The pairs worth naming, because they are the ones the engine gets wrong: a stop may not
        // hold any apply outcome, and a restart may not hold STAGED (it has no staging step).
        assertEquals(false, RequestState.APPLIED.validFor(Operation.STOP));
        assertEquals(false, RequestState.STAGED.validFor(Operation.STOP));
        assertEquals(false, RequestState.APPLY_INDETERMINATE.validFor(Operation.STOP));
        assertEquals(false, RequestState.STAGED.validFor(Operation.RESTART));
        assertEquals(true, RequestState.STAGED.validFor(Operation.APPLY));
        assertEquals(false, RequestState.STOPPED.validFor(Operation.APPLY));
        assertEquals(false, RequestState.STOP_FAILED.validFor(Operation.APPLY));
        assertEquals(true, RequestState.APPLIED.validFor(Operation.RESTART),
                "a restart that completes IS applied — the restart branch denies only the three");
    }

    @Test
    void aNullOperationIsACallerBugRatherThanASilentFalse() {
        assertThrows(NullPointerException.class, () -> RequestState.APPLIED.validFor(null));
    }
}
