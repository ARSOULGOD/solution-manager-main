package com.solace.sm.dp.metrics.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.ingest.RestartDetection;
import com.solace.sm.dp.metrics.ingest.CounterSemantics.Decision;
import com.solace.sm.dp.metrics.ingest.CounterSemantics.Previous;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-3.1 step 4 (M-Q9 d'): the six counter rules as a pure function, so every ordering and boundary is
 * pinned without a database. The scenario numbers are Review Focus 2's S-D: a source declaring {@code intervalSeconds
 * 180} (so 300 s between samples is a delta and 600 s is a gap).
 */
class CounterSemanticsTest {

    private static final Instant T1200 = Instant.parse("2026-10-06T12:00:00Z");
    private static final Instant T1205 = Instant.parse("2026-10-06T12:05:00Z");
    private static final Instant T1215 = Instant.parse("2026-10-06T12:15:00Z");
    private static final int INTERVAL = 180;

    private static Decision counter(RestartDetection d, Previous prev, Instant ts, double v, String token) {
        return CounterSemantics.counter(d, prev, ts, v, token, INTERVAL);
    }

    @Test
    void theFirstSampleOfASeriesHasNoIncrement() {
        for (RestartDetection d : RestartDetection.values()) {
            Decision decision = counter(d, null, T1200, 10000, d == RestartDetection.INFERRED ? null : "T1");
            assertNull(decision.increment(), d.name());
            assertNull(decision.resetSignal(), d.name());
            assertNull(decision.counted(), d.name());
        }
    }

    @Test
    void rule1ATokenChangeIsAResetAtAnySpacingEvenAcrossAGap() {
        // S-D: 12:00 10000 (T1), 12:05 10050 (T1), then 12:15 300 (T2) after a 600 s outage.
        Decision delta = counter(RestartDetection.DOCKER, new Previous(T1200, 10000, "T1"), T1205, 10050, "T1");
        assertEquals(50.0, delta.increment());
        Decision reset = counter(RestartDetection.DOCKER, new Previous(T1205, 10050, "T1"), T1215, 300, "T2");
        assertEquals(300.0, reset.increment(), "counted from the restart");
        assertEquals("docker", reset.resetSignal());
        assertNull(reset.counted());
        assertEquals("T2", reset.tokenAfter());
        assertEquals("declared", counter(RestartDetection.DECLARED, new Previous(T1205, 10050, "T1"), T1215, 300, "T2")
                .resetSignal());
    }

    @Test
    void aTokenChangeIsAResetEvenWhenTheValueDidNotFall() {
        Decision d = counter(RestartDetection.DECLARED, new Previous(T1205, 10, "T1"), T1215, 99999, "T2");
        assertEquals(99999.0, d.increment());
        assertEquals("declared", d.resetSignal());
    }

    @Test
    void rule2ADecreaseUnderTheSameTokenIsCountedNeverAReset() {
        for (RestartDetection d : new RestartDetection[] {RestartDetection.DECLARED, RestartDetection.DOCKER}) {
            Decision decision = counter(d, new Previous(T1205, 10050, "T1"), T1215, 300, "T1");
            assertNull(decision.increment(), d.name());
            assertNull(decision.resetSignal(), d.name());
            assertEquals(CollectorReason.DECREASE_WITHOUT_RESTART, decision.counted(), d.name());
        }
    }

    @Test
    void rule2AlsoHoldsAtAnySpacing() {
        Decision decision = counter(RestartDetection.DOCKER, new Previous(T1200, 10050, "T1"), T1215, 300, "T1");
        assertEquals(CollectorReason.DECREASE_WITHOUT_RESTART, decision.counted());
        assertNull(decision.increment());
    }

    @Test
    void rule3ASignalThatFailedToFetchIsNeverAnInferredReset() {
        for (RestartDetection d : new RestartDetection[] {RestartDetection.DECLARED, RestartDetection.DOCKER}) {
            Decision decision = counter(d, new Previous(T1200, 10000, "T1"), Instant.parse("2026-10-06T12:00:30Z"),
                    9000, null);
            assertNull(decision.increment(), d.name());
            assertNull(decision.resetSignal(), "never an inferred reset: " + d);
            assertEquals(CollectorReason.SCRAPE_FAILED, decision.counted(), d.name());
            assertEquals("T1", decision.tokenAfter(), "the previous token is unchanged: " + d);
        }
    }

    @Test
    void rule3AlsoCountsWhenTheValueHasNotFallen() {
        Decision decision = counter(RestartDetection.DOCKER, new Previous(T1200, 10000, "T1"), T1205, 10050, null);
        assertNull(decision.increment(), "the delta is not attributable without the signal");
        assertEquals(CollectorReason.SCRAPE_FAILED, decision.counted());
    }

    @Test
    void rule4WithNoSignalADecreaseIsAnInferredReset() {
        Decision decision = counter(RestartDetection.INFERRED, new Previous(T1205, 10050, null), T1215, 300, null);
        assertEquals(300.0, decision.increment());
        assertEquals("inferred", decision.resetSignal());
        assertNull(decision.counted());
    }

    @Test
    void anInferredSourceIgnoresATokenItIsGiven() {
        Decision decision = counter(RestartDetection.INFERRED, new Previous(T1200, 10, "T1"), T1205, 20, "T2");
        assertEquals(10.0, decision.increment(), "no reset is read from a token under INFERRED");
        assertNull(decision.resetSignal());
    }

    @Test
    void rule5AnIncreaseWithinTwiceTheIntervalIsADelta() {
        Decision decision = counter(RestartDetection.DOCKER, new Previous(T1200, 100, "T1"), T1200.plusSeconds(360), 130, "T1");
        assertEquals(30.0, decision.increment(), "exactly 2 x interval is still a delta");
        assertEquals(0.0, counter(RestartDetection.DOCKER, new Previous(T1200, 100, "T1"), T1205, 100, "T1").increment(),
                "no change is a zero delta, not a gap");
    }

    @Test
    void rule6AnIncreaseAcrossAGapHasNoDeltaAndIsNeverZeroFilled() {
        Decision decision = counter(RestartDetection.DOCKER, new Previous(T1205, 10050, "T1"), T1215, 10100, "T1");
        assertNull(decision.increment(), "600 s > 2 x 180 s: no delta across a gap");
        assertNull(counter(RestartDetection.INFERRED, new Previous(T1205, 10050, null), T1215, 10100, null).increment());
        assertNull(CounterSemantics.counter(RestartDetection.INFERRED, new Previous(T1205, 1, null), T1215, 2, null, 180)
                .resetSignal());
    }

    @Test
    void aSpacingJustOverTwiceTheIntervalIsAGap() {
        Decision decision = counter(RestartDetection.DOCKER, new Previous(T1200, 100, "T1"), T1200.plusSeconds(361), 130, "T1");
        assertNull(decision.increment());
    }

    @Test
    void aPushedSourceWithNoIntervalHasNoSpacingBound() {
        Decision decision = CounterSemantics.counter(RestartDetection.INFERRED, new Previous(T1200, 100, null),
                T1200.plusSeconds(86_000), 130, null, null);
        assertEquals(30.0, decision.increment());
    }

    @Test
    void aGaugeNeverHasAnIncrementButItsTokenStillAdvances() {
        Decision decision = CounterSemantics.gauge(new Previous(T1200, 5, "T1"), "T2");
        assertNull(decision.increment());
        assertNull(decision.counted());
        assertEquals("T2", decision.tokenAfter());
        assertEquals("T1", CounterSemantics.gauge(new Previous(T1200, 5, "T1"), null).tokenAfter());
    }

    @Test
    void aSeriesWhoseOnlyPreviousSampleHadNoTokenCannotBeProvenToHaveRestarted() {
        // The previous sample was a scrape failure on a DOCKER source, so the series has no token to compare with.
        Decision decision = counter(RestartDetection.DOCKER, new Previous(T1200, 10000, null), T1205, 300, "T2");
        assertNull(decision.resetSignal());
        assertEquals(CollectorReason.DECREASE_WITHOUT_RESTART, decision.counted());
        assertEquals("T2", decision.tokenAfter());
    }
}
