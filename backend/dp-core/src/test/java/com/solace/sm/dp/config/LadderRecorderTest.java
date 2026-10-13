package com.solace.sm.dp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.config.LadderEvidence.PlacementEvidence;
import com.solace.sm.dp.config.LadderEvidence.RequestEvidence;
import com.solace.sm.dp.config.LadderEvidence.StepEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link LadderRecorder}: it writes the rung when the rung moved, and only then (plan 010 T6.5). */
class LadderRecorderTest {

    private static final String FILE = "hmacsha256v1:" + "f".repeat(64);
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final InstanceId instance = new InstanceId(UUID.randomUUID());

    private final List<String> writes = new ArrayList<>();
    private LadderEvidence evidence = LadderEvidence.none();
    private boolean casLost;

    private final LadderRows rows = new LadderRows() {
        @Override
        public LadderEvidence evidence(TenantId t, InstanceId i) {
            return evidence;
        }

        @Override
        public OptionalLong storeReachedRung(TenantId t, InstanceId i, LadderRung expected, LadderRung next) {
            writes.add(expected + "->" + next);
            return casLost ? OptionalLong.empty() : OptionalLong.of(77L);
        }
    };

    private LadderEvidence persistedAt(LadderRung stored) {
        RequestEvidence staged = new RequestEvidence(2, RequestState.STAGED, Strategy.MANUAL, null, List.of(),
                List.of(new StepEvidence(Step.MATERIALIZE, new ReplicaRef("replica-1"), FILE,
                        AttemptOutcome.SUCCEEDED, FILE)));
        return new LadderEvidence(2, stored, List.of(new PlacementEvidence(UUID.randomUUID(), staged)));
    }

    private LadderEvidence unappliedAt(LadderRung stored) {
        return new LadderEvidence(2, stored, List.of(new PlacementEvidence(UUID.randomUUID(), null)));
    }

    @Test
    void aMovementIsWrittenAndReportedWithThePreviousRungAndTheStamp() {
        evidence = persistedAt(null);

        LadderRecorder.Move move = new LadderRecorder(rows).settle(tenant, instance).orElseThrow();

        assertEquals(null, move.previous());
        assertEquals(LadderRung.PERSISTED, move.reached());
        assertEquals(77L, move.stateVersion());
        assertEquals(List.of("null->PERSISTED"), writes);
    }

    @Test
    void aRungThatDidNotMoveWritesNothingBecauseEveryWriteStampsTheInstance() {
        evidence = persistedAt(LadderRung.PERSISTED);
        assertTrue(new LadderRecorder(rows).settle(tenant, instance).isEmpty());

        evidence = unappliedAt(null);
        assertTrue(new LadderRecorder(rows).settle(tenant, instance).isEmpty(),
                "an unwritten column means the rung the stage left it at, so a failed first apply is not a movement");
        assertEquals(List.of(), writes);
    }

    @Test
    void aFallBackIsAMovementToo() {
        evidence = unappliedAt(LadderRung.RESTART_OBSERVED);

        LadderRecorder.Move move = new LadderRecorder(rows).settle(tenant, instance).orElseThrow();

        assertEquals(LadderRung.RESTART_OBSERVED, move.previous());
        assertEquals(LadderRung.RENDERED, move.reached());
    }

    @Test
    void aLostCompareAndSetIsALoudDefectNotASilentSkip() {
        evidence = persistedAt(null);
        casLost = true;

        assertThrows(IllegalStateException.class, () -> new LadderRecorder(rows).settle(tenant, instance));
    }

    @Test
    void theNoneRecorderReadsAndWritesNothing() {
        assertFalse(LadderRecorder.none().settle(tenant, instance).isPresent());
        assertEquals(List.of(), writes);
    }
}
