package com.solace.sm.dp.metrics.rollup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.health.DiskAlarmView;
import com.solace.sm.dp.api.metrics.health.DiskAlarmView.State;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Plan 011 P11-3.2 (OQ-16 (a)): the thresholds, the unconfigured case, and what leaves the process. */
class MetricsDiskAlarmTest {

    private static MetricsDiskAlarm alarm(Long budget, DiskRows rows) {
        return new MetricsDiskAlarm(rows, new DiskProperties(budget, Duration.ofHours(1)), new ScheduledThreadPoolExecutor(1));
    }

    private static DiskRows sizes(long database, long metrics) {
        return () -> new DiskRows.Sizes(database, metrics);
    }

    @Test
    void theDiskStateIsUnconfiguredWithoutABudgetNeverASilentOk() {
        for (Long none : new Long[] {null, 0L}) {
            MetricsDiskAlarm alarm = alarm(none, sizes(1, 1));
            alarm.refresh();
            assertEquals(State.UNCONFIGURED, alarm.view().orElseThrow().state(), "budget " + none);
        }
    }

    @Test
    void theThresholdsAreEightyAndNinetyFivePercentInclusive() {
        assertEquals(State.OK, MetricsDiskAlarm.classify(799, 1000));
        assertEquals(State.WARNING, MetricsDiskAlarm.classify(800, 1000));
        assertEquals(State.WARNING, MetricsDiskAlarm.classify(949, 1000));
        assertEquals(State.CRITICAL, MetricsDiskAlarm.classify(950, 1000));
        assertEquals(State.CRITICAL, MetricsDiskAlarm.classify(5000, 1000), "over budget is still critical");
    }

    @Test
    void theLargerOfTheTwoSizesIsComparedAndOnlyTheStateIsHeld() {
        MetricsDiskAlarm alarm = alarm(1000L, sizes(100, 900));
        alarm.refresh();
        assertEquals(State.WARNING, alarm.view().orElseThrow().state());
    }

    @Test
    void aFailedReadKeepsThePreviousStateAndNeverThrows() {
        AtomicReference<Boolean> fail = new AtomicReference<>(false);
        MetricsDiskAlarm alarm = alarm(1000L, () -> {
            if (fail.get()) {
                throw new IllegalStateException("catalog unreadable");
            }
            return new DiskRows.Sizes(990, 10);
        });
        assertTrue(alarm.view().isEmpty(), "no measurement yet");
        alarm.refresh();
        assertEquals(State.CRITICAL, alarm.view().orElseThrow().state());
        fail.set(true);
        alarm.refresh();
        assertEquals(State.CRITICAL, alarm.view().orElseThrow().state(), "the last good state stands");
    }

    @Test
    void theAlarmsPublicViewIsTheStateOnly() {
        List<String> components = Arrays.stream(DiskAlarmView.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertEquals(List.of("state"), components, "no byte figure may become a component without a ruling");
        assertEquals(List.of("ok", "warning", "critical", "unconfigured"),
                Arrays.stream(State.values()).map(State::wire).toList());
    }
}
