package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Plan 011 P11-2.1: the second sanctioned I5 case, refused before any connection exists (no Docker needed). */
class MetricsPartitionArgumentTest {

    @Test
    void aWellFormedMonthParses() {
        assertEquals("2026_01", MetricsPartitionArgument.parse("2026_01").yyyyMm());
        assertEquals("2026_12", MetricsPartitionArgument.parse("2026_12").yyyyMm());
        assertEquals("metrics_raw_2026_01", MetricsPartitionArgument.parse("2026_01").partitionName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026_13", "2026_00", "2026-01", "2026_01; DROP TABLE x", "2026_01\n", "2026_01 ", " 2026_01",
            "../x", "", "26_01", "20260_01", "2026_1", "2026_001", "metrics_raw_2026_01", "2026_01\u0000", "２０２６_０１"})
    void aMalformedOrInjectedMonthIsRefused(String argument) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MetricsPartitionArgument.parse(argument));
        assertFalse(e.getMessage().contains("DROP"), "the refusal does not echo the argument: " + e.getMessage());
    }

    @Test
    void nullIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> MetricsPartitionArgument.parse(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"freeze", "detach", "archive", "drop"})
    void aMalformedOrInjectedMonthArgumentIsRefusedBeforeAnySql(String subcommand) {
        AtomicInteger opened = new AtomicInteger();
        MetricsPartitionMain.DataSourceFactory spy = (url, user, password) -> {
            opened.incrementAndGet();
            throw new AssertionError("a connection must not be requested for a malformed month");
        };
        for (String bad : List.of("2026_13", "2026_01; DROP TABLE x", "2026_01\n", "../x", "")) {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            String[] args = subcommand.equals("archive") || subcommand.equals("drop")
                    ? new String[] {subcommand, bad, "/tmp/x"} : new String[] {subcommand, bad};
            int code = MetricsPartitionMain.exitCode(args, env(),
                    new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true, StandardCharsets.UTF_8), spy);
            assertEquals(MetricsPartitionMain.EXIT_USAGE, code, subcommand + " " + bad);
            assertEquals(true, err.toString(StandardCharsets.UTF_8).contains("yyyy_mm"),
                    "refused as a MONTH, not as a usage slip: " + err);
        }
        assertEquals(0, opened.get(), "no DataSource was asked for: the refusal precedes every connection");
    }

    @Test
    void anUnknownSubcommandOrMissingArgumentsIsAUsageErrorWithNoConnection() {
        AtomicInteger opened = new AtomicInteger();
        MetricsPartitionMain.DataSourceFactory spy = (u, n, p) -> {
            opened.incrementAndGet();
            throw new AssertionError("no connection");
        };
        for (String[] args : new String[][] {{}, {"explode"}, {"freeze"}, {"archive", "2026_01"}, {"drop", "2026_01"},
                {"status", "extra"}, {"freeze", "2026_01", "extra"}}) {
            int code = MetricsPartitionMain.exitCode(args, env(), new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(new ByteArrayOutputStream()), spy);
            assertEquals(MetricsPartitionMain.EXIT_USAGE, code, String.join(" ", args));
        }
        assertEquals(0, opened.get());
    }

    private static Map<String, String> env() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmDataMigrationMain.URL_VARIABLE, "jdbc:postgresql://localhost:5432/sm");
        env.put(SmDataMigrationMain.USERNAME_VARIABLE, "sm_dp_migrator");
        env.put(SmDataMigrationMain.PASSWORD_VARIABLE, "pw");
        return env;
    }
}
