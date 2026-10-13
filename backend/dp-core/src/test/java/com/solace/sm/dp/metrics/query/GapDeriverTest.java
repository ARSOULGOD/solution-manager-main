package com.solace.sm.dp.metrics.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solace.sm.dp.api.metrics.query.SeriesView.Gap;
import com.solace.sm.dp.api.metrics.query.SeriesView.Point;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Plan 011 P11-6.1: gaps are server-declared, per served tier (DMR-21; discovery default D-3). Pure. */
class GapDeriverTest {

    private static Instant at(String time) {
        return Instant.parse("2026-10-06T" + time + "Z");
    }

    private static Point p(String time, long samples, long expected) {
        return new Point(at(time), 1.0, samples, expected);
    }

    @Test
    void aRawGapIsSpacingBeyondTwiceTheInterval() {
        // 30 s source, threshold 60: 10:00:00 -> 10:00:60 is not a gap (one scrape missed), 10:01:30 is (two missed).
        List<Point> points = List.of(p("10:00:00", 1, 1), p("10:01:00", 1, 1), p("10:02:30", 1, 1));
        assertEquals(List.of(new Gap(at("10:01:30"), at("10:02:30"), "no-samples")), GapDeriver.raw(points, 30, 60));
    }

    @Test
    void aRawGapNeverStartsInsideTheLastPointsOwnBucket() {
        List<Point> points = List.of(p("10:00:00", 1, 1), p("10:10:00", 1, 1));
        assertEquals(List.of(new Gap(at("10:05:00"), at("10:10:00"), "no-samples")), GapDeriver.raw(points, 300, 600 - 1));
    }

    @Test
    void anAbsentHourlyBucketIsAGapEvenThoughTwoBucketWidthsIsTheReportedThreshold() {
        // One empty hour between points: the spacing is 7200 s, which is NOT > 2 x 3600, but an absent rollup bucket is a gap.
        List<Point> points = List.of(p("10:00:00", 120, 120), p("12:00:00", 120, 120));
        assertEquals(List.of(new Gap(at("11:00:00"), at("12:00:00"), "no-samples")),
                GapDeriver.rollup(points, 3600, null, Set.of()));
    }

    @Test
    void anHourWhoseSpacingWasOverTwiceTheIntervalIsAGapOverItsOwnHour() {
        List<Point> points = List.of(p("10:00:00", 120, 120), p("11:00:00", 90, 120), p("12:00:00", 120, 120));
        assertEquals(List.of(new Gap(at("11:00:00"), at("12:00:00"), "partial-samples")),
                GapDeriver.rollup(points, 3600, null, Set.of(at("11:00:00"))));
    }

    /** Fable MON-01: the count shortfall of a collector whose realised period is a little longer than declared is no gap. */
    @Test
    void aShortfallOfSamplesAloneIsNeverAGap() {
        List<Point> points = List.of(p("10:00:00", 118, 120), p("11:00:00", 50, 120), p("12:00:00", 118, 120));
        assertEquals(List.of(), GapDeriver.rollup(points, 3600, null, Set.of()));
    }

    @Test
    void aProvisionalBucketIsNeverFlaggedForBeingIncomplete() {
        List<Point> points = List.of(p("10:00:00", 120, 120), p("11:00:00", 20, 120));
        assertEquals(List.of(), GapDeriver.rollup(points, 3600, at("11:00:00"), Set.of(at("11:00:00"))));
    }

    /** The no-samples band already explains the spacing that ended in the bucket after an absent one. */
    @Test
    void aBucketThatFollowsAnAbsentOneIsNotFlaggedTwice() {
        List<Point> points = List.of(p("10:00:00", 120, 120), p("12:00:00", 120, 120));
        assertEquals(List.of(new Gap(at("11:00:00"), at("12:00:00"), "no-samples")),
                GapDeriver.rollup(points, 3600, null, Set.of(at("12:00:00"))));
    }

    @Test
    void oneOrNoPointHasNoGap() {
        assertEquals(List.of(), GapDeriver.raw(List.of(), 30, 60));
        assertEquals(List.of(), GapDeriver.rollup(List.of(p("10:00:00", 120, 120)), 3600, null, Set.of()));
    }
}
