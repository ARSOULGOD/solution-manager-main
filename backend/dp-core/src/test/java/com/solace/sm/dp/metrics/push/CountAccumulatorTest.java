package com.solace.sm.dp.metrics.push;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-5.1, the count extraction (OR-106): a counter the Data Plane synthesises. It starts at one for the first
 * matching event, is per placement and metric, and lives in memory only: a Data Plane restart starts it again, which the
 * ingest path reads as an INFERRED reset (M-Q9: a decrease with no restart signal).
 */
class CountAccumulatorTest {

    private final CountAccumulator accumulator = new CountAccumulator();
    private final UUID p1 = UUID.randomUUID();
    private final UUID p2 = UUID.randomUUID();

    @Test
    void theCumulativeValueStartsAtOneAndRisesByOnePerEventPerPlacementAndMetric() {
        assertEquals(1.0, accumulator.next(p1, "a"));
        assertEquals(2.0, accumulator.next(p1, "a"));
        assertEquals(1.0, accumulator.next(p1, "b"), "a metric of its own");
        assertEquals(1.0, accumulator.next(p2, "a"), "a placement of its own");
        assertEquals(3.0, accumulator.next(p1, "a"));
    }

    @Test
    void aNewAccumulatorIsTheRestartOfTheDataPlaneAndStartsOver() {
        accumulator.next(p1, "a");
        accumulator.next(p1, "a");

        assertEquals(1.0, new CountAccumulator().next(p1, "a"));
    }

    @Test
    void aPlacementThatLeftThePlanIsForgottenSoTheMapDoesNotGrowForever() {
        accumulator.next(p1, "a");
        accumulator.next(p2, "a");

        accumulator.retainPlacements(Set.of(p2));

        assertEquals(1, accumulator.size());
        assertEquals(1.0, accumulator.next(p1, "a"), "it starts over if it ever comes back");
        assertEquals(2.0, accumulator.next(p2, "a"));
    }
}
