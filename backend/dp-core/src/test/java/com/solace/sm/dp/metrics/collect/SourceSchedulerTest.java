package com.solace.sm.dp.metrics.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.metrics.collect.CollectionPlan.TargetKey;
import com.solace.sm.dp.metrics.collect.CollectionPlan.Unit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Plan 011 P11-4.3: the scheduling rules, pure (no clock, no thread, no I/O): next-due with jitter, deadline = interval,
 * the per-target cap, and per-host backoff. Time is a parameter, so every rule is a plain assertion.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class SourceSchedulerTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");

    private static Unit unit(UUID target, int interval) {
        return new Unit(new PlacementRef(TENANT, new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID())),
                target, AcquisitionMode.VIA_SSH, interval, List.of(), Map.of());
    }

    private static SourceScheduler scheduler(int cap) {
        return new SourceScheduler(cap, 0.1, 8, () -> 0.5);   // 0.5 = no jitter, exactly the interval
    }

    private static Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    @Test
    void aNewUnitIsDueAtOnceAndOnlyOnceUntilItsNextDueTime() {
        SourceScheduler s = scheduler(4);
        Unit u = unit(UUID.randomUUID(), 30);
        s.replace(TENANT, List.of(u), T0);

        assertEquals(List.of(u), s.due(T0).start());
        s.finished(u, at(1), false);

        assertTrue(s.due(at(2)).start().isEmpty(), "not due again before its interval");
        assertTrue(s.due(at(29)).start().isEmpty());
        assertEquals(List.of(u), s.due(at(30)).start());
    }

    @Test
    void nextDueCarriesJitterOfAtMostTenPercentEitherWay() {
        UUID target = UUID.randomUUID();
        Unit u = unit(target, 100);
        SourceScheduler early = new SourceScheduler(4, 0.1, 8, () -> 0.0);
        early.replace(TENANT, List.of(u), T0);
        early.due(T0);
        early.finished(u, T0, false);
        assertTrue(early.due(at(89)).start().isEmpty());
        assertEquals(1, early.due(at(90)).start().size(), "jitter -10 %: due at 90 s");

        SourceScheduler late = new SourceScheduler(4, 0.1, 8, () -> 1.0);
        late.replace(TENANT, List.of(u), T0);
        assertTrue(late.due(T0).start().isEmpty(), "a new plan's first read is spread by up to the same fraction");
        assertEquals(1, late.due(at(10)).start().size());
        late.finished(u, at(10), false);
        assertTrue(late.due(at(119)).start().isEmpty());
        assertEquals(1, late.due(at(120)).start().size(), "jitter +10 %: due 110 s after the start at 10 s");
    }

    @Test
    void aRunStillGoingAtItsNextDueTimeIsMissedAndNeverStartedASecondTime() {
        SourceScheduler s = scheduler(4);
        Unit u = unit(UUID.randomUUID(), 30);
        s.replace(TENANT, List.of(u), T0);
        s.due(T0);                                   // started, never finished

        SourceScheduler.Round round = s.due(at(30));

        assertEquals(List.of(u), round.missed(), "the deadline is the interval: a gap and DEADLINE_MISSED");
        assertTrue(round.start().isEmpty(), "never two runs of one unit at once");
        assertTrue(s.due(at(31)).missed().isEmpty(), "counted once per missed deadline, not once per dispatch tick");
        assertEquals(1, s.inFlight(u.target()));
    }

    @Test
    void thePerTargetCapIsNeverExceeded() {
        SourceScheduler s = scheduler(4);
        UUID target = UUID.randomUUID();
        List<Unit> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelve.add(unit(target, 30));
        }
        s.replace(TENANT, twelve, T0);

        List<Unit> first = s.due(T0).start();

        assertEquals(4, first.size());
        assertEquals(4, s.inFlight(new TargetKey(TENANT, target)));
        assertTrue(s.due(at(1)).start().isEmpty(), "every slot is taken; the other eight wait");
        s.finished(first.get(0), at(2), false);
        assertEquals(1, s.due(at(2)).start().size(), "a freed slot is the next unit's");
        assertEquals(4, s.inFlight(new TargetKey(TENANT, target)));
    }

    @Test
    void aUnitThatCouldNotGetASlotForAWholeIntervalIsMissedNotQueuedForever() {
        SourceScheduler s = scheduler(1);
        UUID target = UUID.randomUUID();
        Unit a = unit(target, 30);
        Unit b = unit(target, 30);
        s.replace(TENANT, List.of(a, b), T0);
        assertEquals(List.of(a), s.due(T0).start());

        SourceScheduler.Round later = s.due(at(31));

        assertTrue(later.missed().contains(b), "b waited a full interval for a slot: a gap and DEADLINE_MISSED");
    }

    @Test
    void theCapIsPerTargetNotGlobal() {
        SourceScheduler s = scheduler(1);
        Unit a = unit(UUID.randomUUID(), 30);
        Unit b = unit(UUID.randomUUID(), 30);
        s.replace(TENANT, List.of(a, b), T0);

        assertEquals(2, s.due(T0).start().size(), "two targets, one slot each");
    }

    @Test
    void aFailingHostBacksOffOneTwoFourEightEightTimesTheIntervalAndRecoversOnSuccess() {
        SourceScheduler s = scheduler(4);
        UUID target = UUID.randomUUID();
        Unit u = unit(target, 10);
        s.replace(TENANT, List.of(u), T0);
        int now = 0;
        int[] expectedGap = {10, 20, 40, 80, 80};
        for (int gap : expectedGap) {
            assertEquals(List.of(u), s.due(at(now)).start(), "due at t=" + now);
            s.finished(u, at(now), true);
            assertTrue(s.due(at(now + gap - 1)).start().isEmpty(), "backing off at t=" + (now + gap - 1));
            now += gap;
        }
        assertEquals(List.of(u), s.due(at(now)).start());
        s.finished(u, at(now), false);                        // the host answered

        assertEquals(List.of(u), s.due(at(now + 10)).start(), "a success resets the backoff to the plain interval");
    }

    @Test
    void aBackedOffHostDoesNotSlowAnotherHost() {
        SourceScheduler s = scheduler(4);
        Unit sick = unit(UUID.randomUUID(), 10);
        Unit well = unit(UUID.randomUUID(), 10);
        s.replace(TENANT, List.of(sick, well), T0);
        s.due(T0);
        s.finished(sick, T0, true);
        s.finished(well, T0, false);
        s.due(at(10));
        s.finished(sick, at(10), true);                       // second failure: 2x
        s.finished(well, at(10), false);

        List<Unit> due = s.due(at(20)).start();

        assertEquals(List.of(well), due, "the sick host waits 20 s, the well host keeps its cadence");
    }

    @Test
    void aReplacedPlanKeepsTheCadenceOfAUnitThatIsStillThereAndForgetsOneThatIsNot() {
        SourceScheduler s = scheduler(4);
        Unit stays = unit(UUID.randomUUID(), 30);
        Unit goes = unit(UUID.randomUUID(), 30);
        s.replace(TENANT, List.of(stays, goes), T0);
        s.due(T0);
        s.finished(stays, T0, false);
        s.finished(goes, T0, false);

        s.replace(TENANT, List.of(stays), at(10));

        assertEquals(1, s.knownUnits());
        assertTrue(s.due(at(10)).start().isEmpty(), "a refresh is not a restart of the cadence");
        assertEquals(List.of(stays), s.due(at(30)).start());
    }

    @Test
    void aTenantThatLeavesTheListIsForgottenWholesale() {
        SourceScheduler s = scheduler(4);
        s.replace(TENANT, List.of(unit(UUID.randomUUID(), 30)), T0);

        s.retainTenants(List.of());

        assertEquals(0, s.knownUnits());
        assertFalse(s.due(T0).start().iterator().hasNext());
    }

    @Test
    void theEffectiveCapLeavesHealthItsOwnProbeSlot() {
        CollectorProperties p = CollectorProperties.defaults();
        assertEquals(3, SourceScheduler.effectiveCap(p, 4), "the pool's default 4 probe slots per session, one kept for health");
        assertEquals(4, SourceScheduler.effectiveCap(p, 10), "a larger session limit lets the configured cap bind");
        assertEquals(1, SourceScheduler.effectiveCap(p, 1), "never below one: collection still runs");
        assertEquals(1, SourceScheduler.effectiveCap(new CollectorProperties(true, Duration.ofSeconds(10),
                Duration.ofSeconds(1), 16, 1, 1, 0.1, 8), 10));
    }
}
