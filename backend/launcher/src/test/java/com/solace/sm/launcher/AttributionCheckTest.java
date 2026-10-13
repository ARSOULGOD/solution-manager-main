package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-4.4: the live attribution step compares EVERY declared counter of each Cache with a fresh read of its OWN
 * port, and names the counters whose values differ between the Caches. The live run of 2026-10-06 read hit_count 0.0 on
 * both Caches, which cannot tell a mix-up from none; these are the pure rules the walkthrough applies. No Docker, no network.
 */
class AttributionCheckTest {

    @Test
    void theCountersAreTheCachePackagesOwnStatsCounters() {
        assertEquals(Map.of("cache.hit_count", "/hitCount", "cache.miss_count", "/missCount",
                "cache.eviction_count", "/evictionCount"), AttributionCheck.COUNTERS);
    }

    @Test
    void aStoredValueEqualToItsOwnFreshReadIsAttributed() {
        assertTrue(AttributionCheck.violation("c1", "cache.miss_count", 7, 7, 0).isEmpty());
    }

    @Test
    void aStoredValueAfterTheFreshReadIsAMixUpBecauseACounterOnlyGrows() {
        String message = AttributionCheck.violation("c1", "cache.miss_count", 9, 7, 5).orElseThrow();
        assertTrue(message.contains("c1") && message.contains("cache.miss_count"), message);
    }

    @Test
    void aStoredValueFarBelowItsFreshReadIsAMixUpToo() {
        assertTrue(AttributionCheck.violation("c1", "cache.miss_count", 3, 40, 2).isPresent(),
                "the other Cache's smaller number is not this Cache's own");
        assertTrue(AttributionCheck.violation("c1", "cache.miss_count", 3, 5, 2).isEmpty(), "within the movement");
    }

    @Test
    void theSlackIsTwiceTheLargestStepOfTheStoredSeriesAndZeroForAFlatOne() {
        assertEquals(0.0, AttributionCheck.slack(List.of(0.0, 0.0, 0.0)));
        assertEquals(8.0, AttributionCheck.slack(List.of(10.0, 11.0, 15.0, 15.0)));
        assertEquals(0.0, AttributionCheck.slack(List.of(5.0)));
        assertEquals(0.0, AttributionCheck.slack(List.of()));
    }

    @Test
    void theCountersThatDifferBetweenTheCachesAreNamedAndNoneMeansNotDistinguishable() {
        Map<String, Map<String, Double>> last = Map.of(
                "c1", Map.of("cache.hit_count", 0.0, "cache.miss_count", 4.0),
                "c2", Map.of("cache.hit_count", 0.0, "cache.miss_count", 34.0));
        assertEquals(List.of("cache.miss_count"), AttributionCheck.differing(last));
        assertEquals(List.of(), AttributionCheck.differing(Map.of(
                "c1", Map.of("cache.hit_count", 0.0, "cache.miss_count", 4.0),
                "c2", Map.of("cache.hit_count", 0.0, "cache.miss_count", 4.0))));
        assertEquals(List.of(), AttributionCheck.differing(Map.of("c1", Map.of("cache.hit_count", 1.0))),
                "one Cache is never distinguishable from another");
    }

    @Test
    void theTrafficCommandIsTheClosedGrammarsCurlOfAnAbsentKeyOnTheSecondCachesLoopbackPort() {
        String command = AttributionCheck.trafficCommand(28081, 7, Duration.ofSeconds(5)).command();
        assertEquals("curl --silent --show-error --max-time 5 --output - --write-out '\\n%{http_code}' "
                + "'http://127.0.0.1:28081/api/cache?key=sm-attribution-7'", command);
    }

    @Test
    void theTrafficRequestCarriesOnlyTheFixedPathAndAnIndexedKey() {
        assertEquals("/api/cache", AttributionCheck.TRAFFIC_PATH);
        assertEquals(Map.of("key", "sm-attribution-12"), AttributionCheck.trafficParams(12));
        assertFalse(AttributionCheck.trafficParams(1).get("key").contains("/"));
        assertThrows(RuntimeAdapterException.class, () -> AttributionCheck.trafficCommand(0, 1, Duration.ofSeconds(5)));
    }
}
