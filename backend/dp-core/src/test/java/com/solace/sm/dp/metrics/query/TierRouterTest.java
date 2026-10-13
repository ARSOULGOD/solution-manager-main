package com.solace.sm.dp.metrics.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.query.RangeQuery.Resolution;
import com.solace.sm.dp.api.metrics.query.RangeResult.Tier;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Plan 011 P11-6.1: tier routing, on-the-fly raw bucketing, the provisional edge and retention. Pure. */
class TierRouterTest {

    private final TierRouter router = new TierRouter(QueryProperties.defaults(), Duration.ofHours(2));

    @Test
    void sevenDaysIsRawAndAnythingLongerIsHourlyUpToNinetyThenDaily() {
        assertEquals(Tier.RAW, router.tier(Duration.ofDays(7), Resolution.AUTO), "7 d is inclusive");
        assertEquals(Tier.HOURLY, router.tier(Duration.ofDays(7).plusSeconds(1), Resolution.AUTO));
        assertEquals(Tier.HOURLY, router.tier(Duration.ofDays(90), Resolution.AUTO), "90 d is inclusive");
        assertEquals(Tier.DAILY, router.tier(Duration.ofDays(90).plusSeconds(1), Resolution.AUTO));
    }

    @Test
    void aForcedRawRangeReadsRawHoweverLong() {
        assertEquals(Tier.RAW, router.tier(Duration.ofDays(400), Resolution.RAW));
        assertEquals(Tier.RAW, router.tier(Duration.ofDays(30), Resolution.RAW));
    }

    @Test
    void sevenDaysIsRawBucketedToAtMostOneThousandPointsHoweverTheRangeIsAligned() {
        Duration range = Duration.ofDays(7);
        int bucket = router.bucketSeconds(Tier.RAW, range, 30);
        // Epoch-aligned buckets can touch ceil(range / bucket) + 1 buckets when the range is not on a boundary.
        long worst = (range.toSeconds() + bucket - 1) / bucket + 1;
        assertTrue(worst <= 1000, "worst case " + worst + " points with bucket " + bucket);
        assertTrue(bucket > 30, "the range forces a coarser bucket than the interval");
    }

    @Test
    void theRawBucketIsNeverFinerThanTheSourcesInterval() {
        assertEquals(300, router.bucketSeconds(Tier.RAW, Duration.ofMinutes(10), 300),
                "a ten-minute range at a 300 s interval: one bucket per scrape, not per second");
        assertEquals(30, router.bucketSeconds(Tier.RAW, Duration.ofMinutes(10), 30));
        assertEquals(1, router.bucketSeconds(Tier.RAW, Duration.ofSeconds(10), 0), "no interval known (push): the range decides");
    }

    @Test
    void theRollupTiersHaveTheirOwnFixedBucketsWhileTheyStayUnderThePointCap() {
        assertEquals(3600, router.bucketSeconds(Tier.HOURLY, Duration.ofDays(30), 30));
        assertEquals(86400, router.bucketSeconds(Tier.DAILY, Duration.ofDays(400), 30));
    }

    @Test
    void aRollupRangeThatWouldExceedThePointCapIsWidenedToAMultipleOfTheTiersWidth() {
        // W3-08: 90 d is 2160 hourly buckets against the advertised 1000 per series
        int hourly = router.bucketSeconds(Tier.HOURLY, Duration.ofDays(90), 30);
        assertEquals(3 * 3600, hourly);
        assertTrue((Duration.ofDays(90).toSeconds() + hourly - 1) / hourly + 1 <= 1000);
        int daily = router.bucketSeconds(Tier.DAILY, Duration.ofDays(3000), 30);
        assertEquals(4 * 86400, daily);
        assertTrue((Duration.ofDays(3000).toSeconds() + daily - 1) / daily + 1 <= 1000);
        assertEquals(3600, router.bucketSeconds(Tier.HOURLY, Duration.ofHours(998), 30), "998 buckets + 1 still fits");
    }

    @Test
    void aWidenedBinIsAlignedToTheSameOriginTheDatabaseBinsFrom() {
        Instant origin = Instant.parse("2001-01-01T00:00:00Z");
        Instant ts = Instant.parse("2026-10-07T13:45:00Z");
        for (long width : new long[] {3600, 10800, 86400, 2 * 86400, 4 * 86400}) {
            Instant bin = router.bin(ts, width);
            assertEquals(0, Duration.between(origin, bin).toSeconds() % width, "width " + width);
            assertTrue(!bin.isAfter(ts) && ts.isBefore(bin.plusSeconds(width)), "width " + width);
        }
    }

    @Test
    void theProvisionalEdgeIsTheFirstBucketTheRollupMayStillRewrite() {
        // S-L: 14:40 with a 2 h late window. RollupService recomputes every bucket whose END is after now - late - 1 h
        // (11:40), so the first bucket it may still rewrite starts at 11:00.
        Instant now = Instant.parse("2026-10-06T14:40:00Z");
        assertEquals(Instant.parse("2026-10-06T11:00:00Z"), router.provisionalFrom(Tier.HOURLY, now));
        assertEquals(Instant.parse("2026-10-06T00:00:00Z"), router.provisionalFrom(Tier.DAILY, now),
                "the day those hours touch is recomputed whole");
        assertNull(router.provisionalFrom(Tier.RAW, now), "raw is the source: nothing to stitch");
    }

    @Test
    void theProvisionalEdgeOnAnExactHourBoundary() {
        // 14:00 exactly: lowerEnd = 11:00; a bucket [10:00, 11:00) ends AT lowerEnd, so it is final; 11:00 is the first open one.
        assertEquals(Instant.parse("2026-10-06T11:00:00Z"), router.provisionalFrom(Tier.HOURLY, Instant.parse("2026-10-06T14:00:00Z")));
    }

    @Test
    void floorsAreUtcAligned() {
        assertEquals(Instant.parse("2026-10-06T14:00:00Z"), router.floor(Tier.HOURLY, Instant.parse("2026-10-06T14:59:59Z")));
        assertEquals(Instant.parse("2026-10-06T00:00:00Z"), router.floor(Tier.DAILY, Instant.parse("2026-10-06T23:59:59Z")));
    }

    @Test
    void aRangeOlderThanTheTierRetentionIsTruncatedNotInvented() {
        Instant now = Instant.parse("2026-10-06T14:40:00Z");
        Instant tooOld = now.minus(Duration.ofDays(200));
        assertEquals(now.minus(Duration.ofDays(183)), router.servedFrom(Tier.RAW, tooOld, now));
        assertEquals(now.minus(Duration.ofDays(2)), router.servedFrom(Tier.RAW, now.minus(Duration.ofDays(2)), now));
        assertEquals(now.minus(Duration.ofDays(730)), router.servedFrom(Tier.HOURLY, now.minus(Duration.ofDays(900)), now));
        assertEquals(now.minus(Duration.ofDays(5000)), router.servedFrom(Tier.DAILY, now.minus(Duration.ofDays(5000)), now),
                "daily is kept indefinitely");
    }
}
