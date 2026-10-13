package com.solace.sm.dp.apply.persistence;

import static com.solace.sm.dp.apply.persistence.IngestFixture.base;
import static com.solace.sm.dp.apply.persistence.IngestFixture.batch;
import static com.solace.sm.dp.apply.persistence.IngestFixture.metric;
import static com.solace.sm.dp.apply.persistence.IngestFixture.sample;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.health.DiskAlarmView.State;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.metrics.ingest.IngestService;
import com.solace.sm.dp.metrics.rollup.DiskProperties;
import com.solace.sm.dp.metrics.rollup.DiskRows;
import com.solace.sm.dp.metrics.rollup.MetricsDiskAlarm;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-3.2 (OQ-16 (a)): the alarm's two catalog reads, MADE AS {@code sm_dp_runtime} (MF19: the privilege the
 * PostgreSQL manual states for {@code pg_database_size} is {@code CONNECT} or {@code pg_read_all_stats}; the relation size
 * functions state none, so they are measured, not assumed), and the alarm against a real database with a tiny budget.
 */
@Testcontainers(disabledWithoutDocker = true)
class MetricsDiskAlarmIT {

    private static MetricsDiskAlarm alarm(MetricsDb db, Long budget) {
        return new MetricsDiskAlarm(new JdbcMetricsDiskRepository(db.runtimeJdbc()),
                new DiskProperties(budget, Duration.ofHours(1)), new ScheduledThreadPoolExecutor(1));
    }

    @Test
    void theTwoSizeFunctionsWorkForTheRuntimeRole() {
        MetricsDb db = MetricsDb.fresh("disk_alarm_privileges");
        DiskRows.Sizes sizes = new JdbcMetricsDiskRepository(db.runtimeJdbc()).read();
        System.out.println("MEASURED (MF19): as sm_dp_runtime, pg_database_size = " + sizes.databaseBytes()
                + " bytes, sum(pg_total_relation_size) over sm_metrics tables = " + sizes.metricsBytes() + " bytes");
        assertTrue(sizes.databaseBytes() > 0, "pg_database_size answers for the runtime role");
        assertTrue(sizes.metricsBytes() > 0, "pg_total_relation_size answers for sm_metrics' tables, indexes included");
        assertTrue(sizes.databaseBytes() >= sizes.metricsBytes());
    }

    @Test
    void theDiskStateIsUnconfiguredWithoutABudget() {
        MetricsDb db = MetricsDb.fresh("disk_alarm_unconfigured");
        MetricsDiskAlarm alarm = alarm(db, null);
        alarm.refresh();
        assertEquals(State.UNCONFIGURED, alarm.view().orElseThrow().state());
    }

    @Test
    void aUsageOverEightyPercentOfTheBudgetIsWarningAndOverNinetyFiveCritical() {
        MetricsDb db = MetricsDb.fresh("disk_alarm_thresholds");
        long used = new JdbcMetricsDiskRepository(db.runtimeJdbc()).read().databaseBytes();
        MetricsDiskAlarm warning = alarm(db, (long) (used / 0.85));       // the database is ~85 % of this budget
        warning.refresh();
        assertEquals(State.WARNING, warning.view().orElseThrow().state());
        MetricsDiskAlarm critical = alarm(db, (long) (used / 0.97));
        critical.refresh();
        assertEquals(State.CRITICAL, critical.view().orElseThrow().state());
        MetricsDiskAlarm ok = alarm(db, used * 4);
        ok.refresh();
        assertEquals(State.OK, ok.view().orElseThrow().state());
    }

    @Test
    void theDiskReadIsBoundAndTouchesNoFilesystem() throws Exception {
        List<String> sql = new ArrayList<>();
        for (String field : List.of("DATABASE", "METRICS")) {
            Field f = JdbcMetricsDiskRepository.class.getDeclaredField(field);
            f.setAccessible(true);
            sql.add((String) f.get(null));
        }
        for (String statement : sql) {
            String lower = statement.toLowerCase();
            assertTrue(lower.startsWith("select"), statement);
            assertTrue(!lower.contains("copy") && !lower.contains("pg_read_file") && !lower.contains("pg_ls_dir")
                    && !lower.contains("program"), "no filesystem or shell access: " + statement);
            assertTrue(!statement.contains("'sm_metrics'"), "the schema name is a bound parameter, not spliced: " + statement);
        }
        assertTrue(sql.get(0).contains("pg_database_size(current_database())"));
        assertTrue(sql.get(1).contains(":schema") && sql.get(1).contains("relkind = 'r'"));
    }

    @Test
    void aCriticalDiskStateNeverStopsIngest() {
        MetricsDb db = MetricsDb.fresh("disk_alarm_never_stops_ingest");
        MetricsDiskAlarm alarm = alarm(db, 1L);                           // one byte of budget: critical by any measure
        alarm.refresh();
        assertEquals(State.CRITICAL, alarm.view().orElseThrow().state());
        try (IngestFixture fx = IngestFixture.on(db)) {
            IngestService service = fx.service(IngestFixture.fast(20, 500));
            IngestFixture.Site site = fx.site("via-ssh", "2.4", List.of(metric("cache.size", "gauge", "entries", "cache", 30)), List.of());
            IngestResult result = service.ingest(batch(site, sample("cache.size", base(), 1, null)));
            assertInstanceOf(IngestResult.Committed.class, result, "the alarm only reports");
            assertEquals(0, fx.counted(site, "runway_degraded"));
        }
        assertEquals(State.CRITICAL, alarm.view().orElseThrow().state());
    }
}
