package com.solace.sm.dp.metrics.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 011 P11-8.1: the gate's arithmetic, apart from any database. */
class GateMathTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void percentilesAreNearestRank() {
        List<Long> values = new ArrayList<>();
        for (long i = 20; i >= 1; i--) {
            values.add(i);                                     // unsorted on purpose
        }
        assertEquals(10, GateMath.percentile(values, 0.50));
        assertEquals(19, GateMath.percentile(values, 0.95));
        assertEquals(20, GateMath.percentile(values, 1.0));
        assertEquals(7, GateMath.percentile(List.of(7L), 0.95));
        assertThrows(IllegalArgumentException.class, () -> GateMath.percentile(Collections.emptyList(), 0.5));
    }

    @Test
    void aRankThatIsNotWholeRoundsUpNeverDown() {
        List<Long> seven = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertEquals(4, GateMath.percentile(seven, 0.50), "ceil(3.5) = 4th value");
        assertEquals(7, GateMath.percentile(seven, 0.95), "ceil(6.65) = 7th value");
    }

    @Test
    void thePercentileDoesNotReorderTheCallersList() {
        List<Long> values = new ArrayList<>(List.of(3L, 1L, 2L));
        GateMath.percentile(values, 0.5);
        assertEquals(List.of(3L, 1L, 2L), values);
    }

    @Test
    void theMonthProjectionIsTheDaysBytesTheWalAndTheFloor() {
        long day = 500_000_000L;
        long wal = 600_000_000L;
        assertEquals(30 * day + 30 * wal + 3 * GB, GateMath.monthProjection(day, wal, 30, 3 * GB));
        assertEquals(15 * day + 15 * wal + 3 * GB, GateMath.monthProjection(day, wal, 15, 3 * GB),
                "the scaled option: 15 days loaded");
    }

    @Test
    void theProjectionFitsExactlyWhenItDoesNotExceedTheFreeDisk() {
        assertTrue(GateMath.fits(10 * GB, 10 * GB));
        assertFalse(GateMath.fits(10 * GB + 1, 10 * GB));
    }

    @Test
    void theLargestFittingDaysIsWhatTheScaledOptionOffers() {
        long day = 500_000_000L;
        long wal = 500_000_000L;
        // free 18 GB, floor 3 GB: (18 - 3) GB / 1 GB per day = 16 whole days (15 GB = 16.1 days of 1e9 B)
        assertEquals(16, GateMath.largestFittingDays(day, wal, 18 * GB, 3 * GB));
        assertEquals(0, GateMath.largestFittingDays(day, wal, 2 * GB, 3 * GB), "nothing fits under the floor");
    }

    @Test
    void theStreamArithmeticIsRowsTimesBytesPlusWalPlusTheFloorAgainstTheFreeDiskAfterTheDrop() {
        GateMath.StreamDisk d = GateMath.streamDisk(105_000_000L, 140.0, 100.0, 3 * GB, 20 * GB);
        assertEquals(Math.round(105_000_000L * 140.0), d.rowBytes());
        assertEquals(Math.round(105_000_000L * 100.0), d.walBytes());
        assertEquals(d.rowBytes() + d.walBytes() + 3 * GB, d.requiredBytes());
        assertFalse(d.fits(), "14.7 GB + 10.5 GB + 3 GiB is about 28 GB, more than 20 GiB free: the expected answer");
        assertTrue(GateMath.streamDisk(105_000_000L, 140.0, 100.0, 3 * GB, 40 * GB).fits());
    }

    @Test
    void sixMonthsIsTheMonthTimesSixAndSaysItIsExtrapolated() {
        GateMath.Extrapolation e = GateMath.sixMonths(12_000_000_000L);
        assertEquals(72_000_000_000L, e.bytes());
        assertTrue(e.label().contains("EXTRAPOLATED"), e.label());
    }
}
