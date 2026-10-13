package com.solace.sm.dp.metrics.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 011 P11-8.1 Step 2b: the host's CPU while a rollup runs, read from {@code vmstat} lines. */
class VmstatTest {

    private static final List<String> OUTPUT = List.of(
            "procs -----------memory---------- ---swap-- -----io---- -system-- ------cpu-----",
            " r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st",
            " 1  0      0 123456  12345 234567    0    0     1     2   10   20  5  3 90  1  1",
            " 2  0      0 123456  12345 234567    0    0     1     2   10   20 40 10 40  5  5",
            " 3  0      0 123456  12345 234567    0    0     1     2   10   20 60 20 10  5  5");

    @Test
    void theFirstLineIsTheAverageSinceBootAndIsNotASample() {
        Vmstat.Summary s = Vmstat.summarize(OUTPUT);
        assertEquals(2, s.samples(), "the since-boot line is dropped");
        assertEquals(65.0, s.meanBusyPercent(), 0.001, "(50 + 80) / 2 where busy is us + sy");
        assertEquals(80.0, s.maxBusyPercent(), 0.001);
        assertEquals(5.0, s.meanStealPercent(), 0.001);
    }

    @Test
    void aSingleLineIsKeptBecauseNothingElseWasSeen() {
        Vmstat.Summary s = Vmstat.summarize(OUTPUT.subList(0, 3));
        assertEquals(1, s.samples());
        assertEquals(8.0, s.meanBusyPercent(), 0.001);
    }

    @Test
    void noHeaderMeansNoSamplesNotAGuess() {
        Vmstat.Summary s = Vmstat.summarize(List.of("garbage", "1 2 3"));
        assertEquals(0, s.samples());
        assertTrue(Double.isNaN(s.meanBusyPercent()));
    }

    @Test
    void aRepeatedHeaderIsSkipped() {
        Vmstat.Summary s = Vmstat.summarize(List.of(OUTPUT.get(0), OUTPUT.get(1), OUTPUT.get(2), OUTPUT.get(3), OUTPUT.get(0),
                OUTPUT.get(1), OUTPUT.get(4)));
        assertEquals(2, s.samples());
    }
}
