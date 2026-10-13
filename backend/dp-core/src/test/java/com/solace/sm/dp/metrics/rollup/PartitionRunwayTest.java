package com.solace.sm.dp.metrics.rollup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.metrics.RunwayDegradation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Plan 011 P11-3.2: the runway keeper never fails the boot, keeps the runway daily, and reports a short runway. */
class PartitionRunwayTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);

    /** Records how the keeper schedules itself and runs the job inline, so no wall clock is waited on. */
    private static final class Capturing extends ScheduledThreadPoolExecutor {
        long initialDelayMillis = -1;
        long periodMillis = -1;
        final AtomicInteger runs = new AtomicInteger();

        Capturing() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            initialDelayMillis = unit.toMillis(initialDelay);
            periodMillis = unit.toMillis(delay);
            runs.incrementAndGet();
            command.run();
            return super.schedule((Callable<Object>) () -> null, 1, TimeUnit.DAYS);
        }
    }

    private static final class CountingDegradation implements RunwayDegradation {
        final AtomicInteger count = new AtomicInteger();

        @Override
        public void runwayCouldNotBeExtended() {
            count.incrementAndGet();
        }
    }

    @Test
    void theRunwayIsKeptDaily() {
        Capturing executor = new Capturing();
        AtomicInteger ensured = new AtomicInteger();
        PartitionRunway runway = new PartitionRunway(() -> {
            ensured.incrementAndGet();
            return LocalDate.of(2027, 1, 1);
        }, executor, RollupProperties.defaults(), NOW, new CountingDegradation());
        runway.start();
        assertEquals(0, executor.initialDelayMillis, "the boot run is immediate, off the starting thread");
        assertEquals(Duration.ofHours(24).toMillis(), executor.periodMillis, "and then daily");
        assertEquals(1, ensured.get(), "the boot run extended the runway");
        assertEquals(LocalDate.of(2027, 1, 1), runway.runwayEnd().orElseThrow());
        executor.shutdownNow();
    }

    @Test
    void aRunwayFailureNeverFailsBootAndIsCounted() {
        CountingDegradation degradation = new CountingDegradation();
        Capturing executor = new Capturing();
        PartitionRunway runway = new PartitionRunway(() -> {
            throw new IllegalStateException("lock_not_available");
        }, executor, RollupProperties.defaults(), NOW, degradation);
        runway.start();                       // runs the keep inline: it must not throw
        runway.keep();
        assertEquals(2, degradation.count.get(), "every failed keep tells ingest");
        assertTrue(runway.runwayEnd().isEmpty());
        assertTrue(runway.runwayShort(), "no confirmed runway reads as short");
        executor.shutdownNow();
    }

    @Test
    void aFailureInCountingNeverEscapesEither() {
        PartitionRunway runway = new PartitionRunway(() -> {
            throw new IllegalStateException("boom");
        }, new Capturing(), RollupProperties.defaults(), NOW, () -> {
            throw new IllegalStateException("counting failed");
        });
        runway.keep();
    }

    @Test
    void theShortRunwayFlagReflectsRunwayEndMinusNowUnderOneMonth() {
        PartitionRunway runway = new PartitionRunway(() -> LocalDate.of(2026, 11, 1), new Capturing(),
                RollupProperties.defaults(), NOW, new CountingDegradation());
        runway.keep();                        // 2026-10-06 + 1 month = 2026-11-06 > 2026-11-01
        assertTrue(runway.runwayShort());
        PartitionRunway healthy = new PartitionRunway(() -> LocalDate.of(2027, 1, 1), new Capturing(),
                RollupProperties.defaults(), NOW, new CountingDegradation());
        healthy.keep();
        assertFalse(healthy.runwayShort());
    }

    @Configuration(proxyBeanMethods = false)
    static class Failing {
        @Bean
        PartitionRunway.Source runwaySource() {
            return () -> {
                throw new IllegalStateException("the runway function is locked");
            };
        }

        @Bean
        DiskRows diskRows() {
            return () -> {
                throw new IllegalStateException("catalog unreadable");
            };
        }

        @Bean
        RollupRows rollupRows() {
            return new RollupRows() {
                @Override
                public int rollUpHours(java.util.UUID tenantId, Instant from, Instant to, Instant computedAt) {
                    return 0;
                }

                @Override
                public int rollUpDays(java.util.UUID tenantId, Instant from, Instant to, Instant computedAt) {
                    return 0;
                }
            };
        }

        @Bean
        RunwayDegradation degradation() {
            return () -> { };
        }

        @Bean
        java.util.concurrent.ScheduledExecutorService metricsExecutor() {
            return new ScheduledThreadPoolExecutor(2);
        }

        @Bean
        Clock smDataClock() {
            return NOW;
        }
    }

    @Test
    void aRealContextWhoseRunwayAndDiskReadsBothFailStillStarts() throws Exception {
        new ApplicationContextRunner().withUserConfiguration(Failing.class, MetricsRollupConfiguration.class)
                .withPropertyValues("sm.dp.metrics.disk.budget-bytes=1000000").run(context -> {
                    assertNull(context.getStartupFailure(), "boot must survive both background jobs failing");
                    assertNotNull(context.getBean(PartitionRunway.class));
                    assertNotNull(context.getBean(MetricsDiskAlarm.class));
                    assertNotNull(context.getBean(RollupService.class));
                    Thread.sleep(300);                 // the boot runs happen on the executor
                    assertTrue(context.getBean(PartitionRunway.class).runwayEnd().isEmpty());
                    assertTrue(context.getBean(MetricsDiskAlarm.class).view().isEmpty(),
                            "a budget with no successful measurement is not a silent ok");
                });
    }

    @Test
    void aDailyJobRidesTheLoopAfterTheKeepAndItsFailureIsNeitherCountedAsRunwayDegradationNorStopsTheNextJob() {
        Capturing executor = new Capturing();
        CountingDegradation degradation = new CountingDegradation();
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger ensured = new AtomicInteger();
        PartitionRunway runway = new PartitionRunway(() -> {
            ensured.incrementAndGet();
            return LocalDate.of(2027, 1, 1);
        }, executor, RollupProperties.defaults(), NOW, degradation, java.util.List.of(() -> {
            throw new IllegalStateException("a job failing");
        }, ran::incrementAndGet));
        runway.keep();
        assertEquals(1, ensured.get());
        assertEquals(1, ran.get(), "the second job ran although the first threw");
        assertEquals(0, degradation.count.get(), "a job's failure is not a runway degradation");
        assertTrue(runway.runwayEnd().isPresent());
    }

    @Test
    void aFailedRunwayKeepStillRunsTheDailyJobs() {
        Capturing executor = new Capturing();
        CountingDegradation degradation = new CountingDegradation();
        AtomicInteger ran = new AtomicInteger();
        PartitionRunway runway = new PartitionRunway(() -> {
            throw new IllegalStateException("lock timeout");
        }, executor, RollupProperties.defaults(), NOW, degradation, java.util.List.of(ran::incrementAndGet));
        runway.keep();
        assertEquals(1, degradation.count.get());
        assertEquals(1, ran.get(), "retention does not wait on the runway");
    }
}
