package com.solace.sm.dp.metrics.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.query.RangeQuery.Across;
import com.solace.sm.dp.api.metrics.query.RangeResult.MetricType;
import com.solace.sm.dp.api.metrics.query.SeriesView;
import com.solace.sm.dp.metrics.query.Aggregator.Cell;
import com.solace.sm.dp.metrics.query.MetricsQueryRows.BucketRow;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Plan 011 P11-6.1: type-aware aggregation (DM-D8) and counter rates on actual timestamp deltas (M-Q24 (8)). Pure. */
class AggregatorTest {

    private static final Instant T0 = Instant.parse("2026-10-06T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-06T11:00:00Z");

    private static BucketRow gauge(Instant at, long n, double sum, double min, double max) {
        return new BucketRow(1, at, n, 0, sum, min, max, null, null);
    }

    private static BucketRow counter(Instant at, long n, Double delta, Double covered) {
        return new BucketRow(1, at, n, 0, null, null, null, delta, covered);
    }

    private static TreeMap<Instant, Cell> one(MetricType type, BucketRow row) {
        return Aggregator.cells(type, List.of(row), 60);
    }

    @Test
    void aGaugeBucketIsSumOverCount() {
        Cell cell = one(MetricType.GAUGE, gauge(T0, 4, 12.0, 1.0, 9.0)).get(T0);
        assertEquals(3.0, cell.value(), 1e-9);
        assertEquals(4, cell.samples());
    }

    @Test
    void aGaugeAcrossInstancesIsSumOverCountNotAverageOfAverages() {
        // Instance A: one sample of 10 (mean 10). Instance B: three samples summing to 6 (mean 2).
        // avg(avg) = 6. sum(sum) / sum(count) = 16 / 4 = 4.
        var a = one(MetricType.GAUGE, gauge(T0, 1, 10.0, 10.0, 10.0));
        var b = one(MetricType.GAUGE, gauge(T0, 3, 6.0, 1.0, 3.0));
        assertEquals(4.0, Aggregator.acrossInstances(MetricType.GAUGE, Across.AVG, List.of(a, b)).get(T0).value(), 1e-9);
    }

    @Test
    void aGaugeAcrossInstancesSumIsTheSumOfTheInstancesMeansAndMinMaxAreExtremes() {
        var a = one(MetricType.GAUGE, gauge(T0, 1, 10.0, 10.0, 10.0));
        var b = one(MetricType.GAUGE, gauge(T0, 3, 6.0, 1.0, 3.0));
        assertEquals(12.0, Aggregator.acrossInstances(MetricType.GAUGE, Across.SUM, List.of(a, b)).get(T0).value(), 1e-9);
        assertEquals(1.0, Aggregator.acrossInstances(MetricType.GAUGE, Across.MIN, List.of(a, b)).get(T0).value(), 1e-9);
        assertEquals(10.0, Aggregator.acrossInstances(MetricType.GAUGE, Across.MAX, List.of(a, b)).get(T0).value(), 1e-9);
    }

    @Test
    void aCounterRateIsDeltaOverTheCoveredSecondsNeverTheNominalInterval() {
        // 27, 33, 30 and 29 s gaps with increments 27, 33, 30 and 29: exactly 1.0 per second whatever the jitter,
        // where a nominal 30 s interval would give 119 / 120 = 0.9917.
        Cell cell = one(MetricType.COUNTER, counter(T0, 4, 119.0, 119.0)).get(T0);
        assertEquals(60.0, cell.value(), 1e-9, "1.0 per second rendered per 60 s");
    }

    @Test
    void anHourlyCounterRateIsDeltaOverCoveredSecondsNotOverTheHour() {
        // An hour with a 20-minute gap: 20 increments over the 40 covered minutes (2400 s).
        Cell cell = one(MetricType.COUNTER, counter(T0, 80, 20.0, 2400.0)).get(T0);
        assertEquals(20.0 / 2400.0 * 60.0, cell.value(), 1e-9);
        assertTrue(Math.abs(cell.value() - 20.0 / 3600.0 * 60.0) > 0.1, "dividing by 3600 would understate it");
    }

    @Test
    void aNullDeltaOrNoCoveredSecondsGivesNoPointNeverZero() {
        assertTrue(one(MetricType.COUNTER, counter(T0, 1, null, null)).isEmpty());
        assertTrue(one(MetricType.COUNTER, counter(T0, 1, 5.0, 0.0)).isEmpty(), "no covered span, no rate");
    }

    @Test
    void countersSumAcrossPlacementsAsTheSumOfTheirRatesNotTheRatioOfTheSums() {
        // Placement A: 30 over 30 s = 1/s. Placement B: 60 over 120 s = 0.5/s. The instance rate is 1.5/s (90 per minute);
        // sum(delta) / sum(covered) = 90 / 150 = 0.6/s would average them.
        var a = one(MetricType.COUNTER, counter(T0, 1, 30.0, 30.0));
        var b = one(MetricType.COUNTER, counter(T0, 1, 60.0, 120.0));
        assertEquals(90.0, Aggregator.combinePlacements(MetricType.COUNTER, List.of(a, b)).get(T0).value(), 1e-9);
    }

    @Test
    void aCounterAcrossInstancesSumsAveragesAndExtremesTheRates() {
        var a = one(MetricType.COUNTER, counter(T0, 1, 30.0, 30.0));   // 60 per minute
        var b = one(MetricType.COUNTER, counter(T0, 1, 15.0, 30.0));   // 30 per minute
        assertEquals(90.0, Aggregator.acrossInstances(MetricType.COUNTER, Across.SUM, List.of(a, b)).get(T0).value(), 1e-9);
        assertEquals(45.0, Aggregator.acrossInstances(MetricType.COUNTER, Across.AVG, List.of(a, b)).get(T0).value(), 1e-9);
        assertEquals(30.0, Aggregator.acrossInstances(MetricType.COUNTER, Across.MIN, List.of(a, b)).get(T0).value(), 1e-9);
        assertEquals(60.0, Aggregator.acrossInstances(MetricType.COUNTER, Across.MAX, List.of(a, b)).get(T0).value(), 1e-9);
    }

    @Test
    void anInstanceWithNoPointInABucketDoesNotDragTheAverageDown() {
        var a = one(MetricType.COUNTER, counter(T0, 1, 30.0, 30.0));
        var b = one(MetricType.COUNTER, counter(T1, 1, 30.0, 30.0));
        TreeMap<Instant, Cell> avg = Aggregator.acrossInstances(MetricType.COUNTER, Across.AVG, List.of(a, b));
        assertEquals(60.0, avg.get(T0).value(), 1e-9, "only A contributes at T0: n = 1, not 2");
        assertEquals(60.0, avg.get(T1).value(), 1e-9);
    }

    @Test
    void nonOverlappingGaugePlacementsMergeIntoOneContinuousLine() {
        var before = one(MetricType.GAUGE, gauge(T0, 2, 8.0, 3.0, 5.0));
        var after = one(MetricType.GAUGE, gauge(T1, 2, 20.0, 9.0, 11.0));
        TreeMap<Instant, Cell> line = Aggregator.combinePlacements(MetricType.GAUGE, List.of(before, after));
        assertEquals(2, line.size());
        assertEquals(4.0, line.get(T0).value(), 1e-9);
        assertEquals(10.0, line.get(T1).value(), 1e-9);
    }

    @Test
    void aBucketBothPlacementsTouchPoolsItsSamples() {
        var a = one(MetricType.GAUGE, gauge(T0, 1, 10.0, 10.0, 10.0));
        var b = one(MetricType.GAUGE, gauge(T0, 3, 6.0, 1.0, 3.0));
        Cell cell = Aggregator.combinePlacements(MetricType.GAUGE, List.of(a, b)).get(T0);
        assertEquals(4.0, cell.value(), 1e-9, "pooled: 16 / 4");
        assertEquals(1.0, cell.min(), 1e-9);
        assertEquals(10.0, cell.max(), 1e-9);
    }

    @Test
    void pointsCarryTheValueTheSampleCountAndTheExpectedCount() {
        BucketRow row = new BucketRow(1, T0, 59, 60, 59.0, 1.0, 1.0, null, null);
        List<SeriesView.Point> points = Aggregator.points(Aggregator.cells(MetricType.GAUGE, List.of(row), 60));
        assertEquals(List.of(new SeriesView.Point(T0, 1.0, 59, 60)), points);
    }

    // ---- W3-11: placements in sequence are one rate, placements side by side add ---------------------------------

    @Test
    void placementsInSequenceInOneBucketAreOneRateAndPlacementsSideBySideAdd() {
        var before = one(MetricType.COUNTER, counter(T0, 2, 20.0, 20.0));   // 1/s over the 20 s it was alive in the bucket
        var after = one(MetricType.COUNTER, counter(T0, 2, 12.0, 12.0));    // 1/s over the 12 s it was alive
        Cell sequential = Aggregator.combinePlacements(MetricType.COUNTER, List.of(before, after), List.of(0, 0), 60).get(T0);
        assertEquals(60.0, sequential.value(), 1e-9, "one continuous 1/s line: (20 + 12) / (20 + 12), per minute");
        assertEquals(32.0, sequential.delta(), 1e-9);
        assertEquals(32.0, sequential.covered(), 1e-9);
        assertEquals(4, sequential.samples());
        Cell sideBySide = Aggregator.combinePlacements(MetricType.COUNTER, List.of(before, after), List.of(0, 1), 60).get(T0);
        assertEquals(120.0, sideBySide.value(), 1e-9, "two replicas running at once: their rates add (M-Q20)");
    }

    @Test
    void aSequentialLaneThatHasNoCellInABucketContributesNothingThere() {
        var a = one(MetricType.COUNTER, counter(T0, 1, 30.0, 30.0));
        var b = one(MetricType.COUNTER, counter(T1, 1, 60.0, 30.0));
        TreeMap<Instant, Cell> line = Aggregator.combinePlacements(MetricType.COUNTER, List.of(a, b), List.of(0, 0), 60);
        assertEquals(60.0, line.get(T0).value(), 1e-9);
        assertEquals(120.0, line.get(T1).value(), 1e-9);
    }

    // ---- W3-08: rollup rows folded into a wider bin by the rules of raw -------------------------------------------

    private static final Instant T2 = Instant.parse("2026-10-06T12:00:00Z");

    @Test
    void hourlyGaugeRowsFoldIntoAWiderBinByPoolingSumCountMinAndMax() {
        // 3 h bins from the 2001-01-01 origin: 10:00 and 11:00 are in the 09:00 bin, 12:00 starts the next
        List<BucketRow> rows = List.of(new BucketRow(1, T0, 6, 6, 60.0, 5.0, 15.0, null, null),
                new BucketRow(1, T1, 6, 6, 120.0, 8.0, 30.0, null, null),
                new BucketRow(1, T2, 4, 6, 40.0, 10.0, 10.0, null, null));
        List<BucketRow> folded = Aggregator.fold(MetricType.GAUGE, rows, 10800);
        assertEquals(2, folded.size());
        BucketRow first = folded.get(0);
        assertEquals(Instant.parse("2026-10-06T09:00:00Z"), first.bucket());
        assertEquals(12, first.sampleCount());
        assertEquals(12, first.expectedCount());
        assertEquals(180.0, first.sum(), 1e-9);
        assertEquals(5.0, first.min(), 1e-9);
        assertEquals(30.0, first.max(), 1e-9);
        assertEquals(Instant.parse("2026-10-06T12:00:00Z"), folded.get(1).bucket());
        assertEquals(4, folded.get(1).sampleCount());
    }

    /** Fable MON-01: a widened bin, and a line pooled over series or placements, is gapped when any part of it is. */
    @Test
    void theSpacingJudgementSurvivesFoldingAndPooling() {
        List<BucketRow> rows = List.of(new BucketRow(1, T0, 6, 6, 60.0, 5.0, 15.0, null, null, false),
                new BucketRow(1, T1, 6, 6, 120.0, 8.0, 30.0, null, null, true),
                new BucketRow(1, T2, 4, 6, 40.0, 10.0, 10.0, null, null, false));
        List<BucketRow> folded = Aggregator.fold(MetricType.GAUGE, rows, 10800);
        assertTrue(folded.get(0).gapped());
        assertTrue(!folded.get(1).gapped());

        TreeMap<Instant, Cell> clean = Aggregator.cells(MetricType.GAUGE, List.of(rows.get(0)), 60);
        TreeMap<Instant, Cell> gapped = Aggregator.cells(MetricType.GAUGE, List.of(rows.get(1)), 60);
        assertEquals(java.util.Set.of(), Aggregator.gappedBuckets(clean));
        assertEquals(java.util.Set.of(T1), Aggregator.gappedBuckets(gapped));
        // two placements that both hold the hour: the pooled bucket is gapped when either is
        TreeMap<Instant, Cell> cleanSameHour = Aggregator.cells(MetricType.GAUGE,
                List.of(new BucketRow(2, T1, 6, 6, 60.0, 5.0, 15.0, null, null, false)), 60);
        TreeMap<Instant, Cell> pooled = Aggregator.combinePlacements(MetricType.GAUGE, List.of(cleanSameHour, gapped));
        assertEquals(java.util.Set.of(T1), Aggregator.gappedBuckets(pooled));
    }

    @Test
    void hourlyCounterRowsFoldAsSumDeltaOverSumCoveredAndANullDeltaStaysNull() {
        List<BucketRow> rows = List.of(new BucketRow(1, T0, 6, 6, null, null, null, 30.0, 3000.0),
                new BucketRow(1, T1, 6, 6, null, null, null, null, null),
                new BucketRow(1, T2, 6, 6, null, null, null, null, null));
        List<BucketRow> folded = Aggregator.fold(MetricType.COUNTER, rows, 10800);
        assertEquals(30.0, folded.get(0).delta(), 1e-9, "a null row adds nothing");
        assertEquals(3000.0, folded.get(0).coveredSeconds(), 1e-9);
        assertEquals(12, folded.get(0).sampleCount());
        assertNull(folded.get(1).delta(), "every row null: no attributable increment, never zero (M-Q9)");
        assertNull(folded.get(1).coveredSeconds());
    }

    @Test
    void foldingKeepsSeriesApartAndLeavesAWidthOfOneRowAlone() {
        List<BucketRow> rows = List.of(new BucketRow(1, T0, 6, 6, 60.0, 5.0, 15.0, null, null),
                new BucketRow(2, T0, 6, 6, 90.0, 6.0, 20.0, null, null));
        assertEquals(2, Aggregator.fold(MetricType.GAUGE, rows, 10800).size(), "two series, two bins");
        assertEquals(rows, Aggregator.fold(MetricType.GAUGE, rows, 3600), "the tier's own width folds nothing");
    }
}
