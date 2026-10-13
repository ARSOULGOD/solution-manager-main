package com.solace.sm.dp.metrics.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-8.1: the benchmark's settings. The defaults are the plan's real S-A month; a tiny scale is explicit counts;
 * every step is individually selectable; and a password is read from the ENVIRONMENT only and never printed.
 */
class BenchSettingsTest {

    private static BenchSettings of(Map<String, String> props, Map<String, String> env) {
        return BenchSettings.from(props::get, env::get);
    }

    private static Map<String, String> minimal() {
        Map<String, String> props = new HashMap<>();
        props.put("sm.bench.url", "jdbc:postgresql://127.0.0.1:55432/sm");
        return props;
    }

    @Test
    void theDefaultScaleIsThePlansRealSAMonth() {
        BenchSettings s = of(minimal(), Map.of());
        assertEquals(200, s.instances());
        assertEquals(5, s.metrics());
        assertEquals(30, s.intervalSeconds());
        assertEquals(30, s.days());
        assertEquals(86_400_000L, s.rowsPerMonth(), "200 instances x 5 metrics x 30 s x 30 days = 86.4 M raw rows");
        assertEquals(2_880_000L, s.rowsPerDay(), "one day of S-A is 2.88 M rows (Step 0b)");
        assertEquals(3L * 1024 * 1024 * 1024, s.floorBytes(), "the 3 GB floor");
        assertEquals("sm_dp_runtime", s.runtimeUser());
    }

    @Test
    void aTinyScaleIsExplicitCounts() {
        Map<String, String> props = minimal();
        props.put("sm.bench.instances", "3");
        props.put("sm.bench.metrics", "2");
        props.put("sm.bench.interval-seconds", "60");
        props.put("sm.bench.days", "2");
        BenchSettings s = of(props, Map.of());
        assertEquals(3 * 2 * 1440L, s.rowsPerDay());
        assertEquals(3 * 2 * 1440L * 2, s.rowsPerMonth());
    }

    @Test
    void everyStepIsSelectableAloneAndTheDefaultIsEverythingButPrepare() {
        assertEquals(List.of("0b", "1", "2", "2b", "5", "6a"), of(minimal(), Map.of()).steps());
        Map<String, String> props = minimal();
        props.put("sm.bench.steps", "0b");
        BenchSettings only0b = of(props, Map.of());
        assertTrue(only0b.runs("0b"));
        assertFalse(only0b.runs("1"), "Step 0b alone stops there: the integrator reads the disk projection first");
        props.put("sm.bench.steps", " 2b , 6a ");
        BenchSettings two = of(props, Map.of());
        assertTrue(two.runs("2b") && two.runs("6a"));
        assertFalse(two.runs("0b"));
        props.put("sm.bench.steps", "prepare");
        assertTrue(of(props, Map.of()).runs("prepare"));
        assertFalse(of(minimal(), Map.of()).runs("prepare"), "preparing a database is never an accident of 'all'");
    }

    @Test
    void anUnknownStepIsRefusedNamingTheValidOnes() {
        Map<String, String> props = minimal();
        props.put("sm.bench.steps", "0b,7");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> of(props, Map.of()));
        assertTrue(e.getMessage().contains("7") && e.getMessage().contains("0b") && e.getMessage().contains("6a"), e.getMessage());
    }

    @Test
    void thePasswordsComeFromTheEnvironmentOnlyAndAreNeverPrinted() {
        Map<String, String> props = minimal();
        props.put("sm.bench.runtime.password", "from-a-property-must-be-ignored");
        BenchSettings s = of(props, Map.of("SM_BENCH_RUNTIME_PASSWORD", "env-secret-runtime-pw",
                "SM_BENCH_ADMIN_PASSWORD", "env-secret-admin-pw"));
        assertEquals("env-secret-runtime-pw", s.runtimePassword(), "the environment is the source");
        assertEquals("env-secret-admin-pw", s.adminPassword());
        String printed = s.toString();
        assertFalse(printed.contains("env-secret-runtime-pw") || printed.contains("env-secret-admin-pw"), printed);
        assertFalse(printed.contains("from-a-property-must-be-ignored"), printed);

        BenchSettings none = of(minimal(), Map.of());
        IllegalStateException e = assertThrows(IllegalStateException.class, none::runtimePassword);
        assertTrue(e.getMessage().contains("SM_BENCH_RUNTIME_PASSWORD"), "names the variable: " + e.getMessage());
    }

    @Test
    void theUrlIsRequiredAndItsCredentialsAreRefused() {
        assertThrows(IllegalStateException.class, () -> of(Map.of(), Map.of()));
        Map<String, String> props = new HashMap<>();
        props.put("sm.bench.url", "jdbc:postgresql://127.0.0.1:5432/sm?user=a&password=leaked");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> of(props, Map.of()));
        assertFalse(e.getMessage().contains("leaked"), "the refusal does not echo the URL: " + e.getMessage());
    }

    @Test
    void theFreeDiskAndTheDatabaseCeilingAreOptional() {
        BenchSettings none = of(minimal(), Map.of());
        assertTrue(none.freeDiskBytes().isEmpty());
        assertTrue(none.maxDbBytes().isEmpty());
        Map<String, String> props = minimal();
        props.put("sm.bench.free-disk-bytes", "18000000000");
        props.put("sm.bench.max-db-bytes", "15000000000");
        BenchSettings s = of(props, Map.of());
        assertEquals(18_000_000_000L, s.freeDiskBytes().orElseThrow());
        assertEquals(15_000_000_000L, s.maxDbBytes().orElseThrow());
    }

    @Test
    void anIntervalThatDoesNotDivideAnHourIsRefusedBecauseTheLoadRunsOneHourPerStatement() {
        Map<String, String> props = minimal();
        props.put("sm.bench.interval-seconds", "7");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> of(props, Map.of()));
        assertTrue(e.getMessage().contains("sm.bench.interval-seconds"), e.getMessage());
        props.put("sm.bench.interval-seconds", "5");
        assertEquals(5, of(props, Map.of()).intervalSeconds());
    }

    @Test
    void theWindowEndsAtAMidnightSoTheLastDayIsWhole() {
        BenchSettings s = of(minimal(), Map.of());
        assertEquals(0, s.windowEnd().getEpochSecond() % 86_400, "a UTC midnight");
        assertEquals(s.windowEnd().minusSeconds(30L * 86_400), s.windowStart());
    }
}
