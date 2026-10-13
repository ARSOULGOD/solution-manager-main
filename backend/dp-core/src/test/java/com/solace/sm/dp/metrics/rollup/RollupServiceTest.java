package com.solace.sm.dp.metrics.rollup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plan 011 P11-3.2: which hourly buckets a run recomputes, and how often a tenant's run actually happens. */
class RollupServiceTest {

    private static final class Recording implements RollupRows {
        final List<String> calls = new ArrayList<>();

        @Override
        public int rollUpHours(UUID tenantId, Instant from, Instant to, Instant computedAt) {
            calls.add("hours " + tenantId + " " + from + " " + to);
            return 0;
        }

        @Override
        public int rollUpDays(UUID tenantId, Instant from, Instant to, Instant computedAt) {
            calls.add("days " + tenantId + " " + from + " " + to);
            return 0;
        }
    }

    private static final Duration LATE = Duration.ofHours(2);

    @Test
    void theWindowIsTheBucketsWhoseEndLiesInNowMinusLateMinusOneHourToNow() {
        // now 12:30, late 2 h: ends in (09:30, 12:30] -> the buckets ending 10:00, 11:00, 12:00 -> starts 09:00, 10:00, 11:00
        RollupService.Window w = RollupService.window(Instant.parse("2026-10-06T12:30:00Z"), LATE);
        assertEquals(Instant.parse("2026-10-06T09:00:00Z"), w.from());
        assertEquals(Instant.parse("2026-10-06T12:00:00Z"), w.to(), "exclusive: the bucket starting 11:00 is the last");
    }

    @Test
    void onTheHourTheJustClosedBucketIsIncluded() {
        // now 12:00 exactly: ends in (09:00, 12:00] -> starts 09:00, 10:00, 11:00
        RollupService.Window w = RollupService.window(Instant.parse("2026-10-06T12:00:00Z"), LATE);
        assertEquals(Instant.parse("2026-10-06T09:00:00Z"), w.from());
        assertEquals(Instant.parse("2026-10-06T12:00:00Z"), w.to());
    }

    @Test
    void theCurrentOpenHourIsNeverRolledUp() {
        RollupService.Window w = RollupService.window(Instant.parse("2026-10-06T12:59:59Z"), LATE);
        assertEquals(Instant.parse("2026-10-06T12:00:00Z"), w.to(), "the 12:00 bucket is still open");
    }

    @Test
    void aZeroLateWindowStillRollsTheOneClosedHour() {
        RollupService.Window w = RollupService.window(Instant.parse("2026-10-06T12:30:00Z"), Duration.ZERO);
        assertEquals(Instant.parse("2026-10-06T11:00:00Z"), w.from(), "ends in (11:30, 12:30]: only the 11:00 bucket");
        assertEquals(Instant.parse("2026-10-06T12:00:00Z"), w.to());
    }

    @Test
    void rollupsRunOnTheirOwnCadenceNotEveryTick() {
        Recording rows = new Recording();
        RollupService service = new RollupService(rows, new RollupProperties(Duration.ofMinutes(5), LATE, Duration.ofHours(24)));
        TenantId a = new TenantId(UUID.randomUUID());
        TenantId b = new TenantId(UUID.randomUUID());
        Instant t0 = Instant.parse("2026-10-06T12:00:00Z");

        assertTrue(service.rollUp(a, t0));
        for (int tick = 1; tick <= 29; tick++) {               // every 10 s for under five minutes
            assertFalse(service.rollUp(a, t0.plusSeconds(10L * tick)), "tick " + tick);
        }
        assertEquals(2, rows.calls.size(), "one run: hours and days");
        assertTrue(service.rollUp(b, t0.plusSeconds(20)), "another tenant's cadence is its own");
        assertTrue(service.rollUp(a, t0.plusSeconds(300)), "five minutes later it runs again");
        assertEquals(6, rows.calls.size());
        assertTrue(rows.calls.stream().allMatch(c -> c.contains(a.value().toString()) || c.contains(b.value().toString())));
    }

    @Test
    void aRunNamesOnlyItsTenantAndRecomputesDaysAfterHours() {
        Recording rows = new Recording();
        RollupService service = new RollupService(rows, RollupProperties.defaults());
        TenantId a = new TenantId(UUID.randomUUID());
        service.rollUp(a, Instant.parse("2026-10-06T12:30:00Z"));
        assertTrue(rows.calls.get(0).startsWith("hours " + a.value()));
        assertTrue(rows.calls.get(1).startsWith("days " + a.value()));
    }
}
