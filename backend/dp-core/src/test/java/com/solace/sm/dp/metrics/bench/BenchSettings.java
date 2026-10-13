package com.solace.sm.dp.metrics.bench;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * The storage gate's configuration (plan 011 P11-8.1). Everything comes from {@code sm.bench.*} system properties except the
 * passwords, which come from the ENVIRONMENT only ({@code SM_BENCH_RUNTIME_PASSWORD}, {@code SM_BENCH_ADMIN_PASSWORD},
 * {@code SM_BENCH_MIGRATOR_PASSWORD}) so they are never on a command line, in a Maven property or in a report, and are
 * never printed by {@link #toString()}.
 *
 * <p>The defaults are the plan's real S-A month: 200 instances x 5 metrics at 30 s for 30 days = 86.4 M raw rows. A smaller
 * run is explicit counts ({@code instances}, {@code metrics}, {@code interval-seconds}, {@code days}), so a tiny proof
 * can never be mistaken for the real one: the evidence prints the scale it ran at.
 */
public final class BenchSettings {

    /** Every step, in the order the plan runs them. {@code prepare} is not a plan step and is never part of the default. */
    public static final List<String> STEP_ORDER = List.of("prepare", "0b", "1", "2", "2b", "5", "6a");

    static final long GIB = 1024L * 1024 * 1024;

    private final Function<String, String> props;
    private final Function<String, String> env;
    private final String url;
    private final List<String> steps;
    private final Instant windowEnd;

    private BenchSettings(Function<String, String> props, Function<String, String> env, Clock clock) {
        this.props = props;
        this.env = env;
        String u = props.apply("sm.bench.url");
        if (u == null || u.isBlank()) {
            throw new IllegalStateException("sm.bench.url is not set (jdbc:postgresql://host:port/database, no credentials)");
        }
        String lower = u.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("jdbc:postgresql://") || lower.contains("password") || lower.contains("user=")
                || lower.contains("@")) {
            throw new IllegalArgumentException("sm.bench.url must be jdbc:postgresql://host:port/database with no credentials in it"
                    + " (they come from SM_BENCH_* environment variables); the value is not echoed");
        }
        this.url = u;
        this.steps = parseSteps(props.apply("sm.bench.steps"));
        for (String name : new String[] {"sm.bench.interval-seconds", "sm.bench.scaled.interval-seconds"}) {
            int interval = integer(name, 30);
            if (3600 % interval != 0) {
                throw new IllegalArgumentException(name + " must divide an hour (the load runs one hour per statement), was " + interval);
            }
        }
        String end = props.apply("sm.bench.window-end");
        this.windowEnd = end == null || end.isBlank() ? clock.instant().truncatedTo(ChronoUnit.DAYS) : Instant.parse(end.trim());
        if (windowEnd.getEpochSecond() % 86_400 != 0) {
            throw new IllegalArgumentException("sm.bench.window-end must be a UTC midnight, was " + windowEnd);
        }
    }

    public static BenchSettings from(Function<String, String> props, Function<String, String> env) {
        return new BenchSettings(props, env, Clock.systemUTC());
    }

    public static BenchSettings fromSystem() {
        return from(System::getProperty, System::getenv);
    }

    private static List<String> parseSteps(String raw) {
        if (raw == null || raw.isBlank()) {
            return STEP_ORDER.stream().filter(s -> !s.equals("prepare")).toList();
        }
        List<String> wanted = new ArrayList<>();
        for (String s : Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            if (!STEP_ORDER.contains(s)) {
                throw new IllegalArgumentException("unknown step '" + s + "'; valid: " + STEP_ORDER);
            }
            wanted.add(s);
        }
        return STEP_ORDER.stream().filter(wanted::contains).toList();
    }

    private int integer(String name, int dflt) {
        String v = props.apply(name);
        if (v == null || v.isBlank()) {
            return dflt;
        }
        int n = Integer.parseInt(v.trim());
        if (n < 1) {
            throw new IllegalArgumentException(name + " must be at least 1, was " + n);
        }
        return n;
    }

    private OptionalLong optionalLong(String name) {
        String v = props.apply(name);
        return v == null || v.isBlank() ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(v.trim()));
    }

    private String secret(String variable) {
        String v = env.apply(variable);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException(variable + " is not set (the password is read from the environment only)");
        }
        return v;
    }

    // ---- connection ------------------------------------------------------------------------------------------------

    public String url() {
        return url;
    }

    public String runtimeUser() {
        return orDefault("sm.bench.runtime.user", "sm_dp_runtime");
    }

    public String runtimePassword() {
        return secret("SM_BENCH_RUNTIME_PASSWORD");
    }

    public String adminUser() {
        return orDefault("sm.bench.admin.user", "postgres");
    }

    public String adminPassword() {
        return secret("SM_BENCH_ADMIN_PASSWORD");
    }

    public String migratorPassword() {
        return secret("SM_BENCH_MIGRATOR_PASSWORD");
    }

    private String orDefault(String name, String dflt) {
        String v = props.apply(name);
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    // ---- scale -----------------------------------------------------------------------------------------------------

    public int instances() {
        return integer("sm.bench.instances", 200);
    }

    public int metrics() {
        return integer("sm.bench.metrics", 5);
    }

    public int intervalSeconds() {
        return integer("sm.bench.interval-seconds", 30);
    }

    public int days() {
        return integer("sm.bench.days", 30);
    }

    public long rowsPerDay() {
        return (long) instances() * metrics() * (86_400L / intervalSeconds());
    }

    public long rowsPerMonth() {
        return rowsPerDay() * days();
    }

    /** Step 5: the scaled 5 s point is one day for this many series at this interval. */
    public int scaledSeries() {
        return integer("sm.bench.scaled.series", 200);
    }

    public int scaledIntervalSeconds() {
        return integer("sm.bench.scaled.interval-seconds", 5);
    }

    public Instant windowEnd() {
        return windowEnd;
    }

    public Instant windowStart() {
        return windowEnd.minusSeconds(days() * 86_400L);
    }

    // ---- disk ------------------------------------------------------------------------------------------------------

    public long floorBytes() {
        return optionalLong("sm.bench.floor-bytes").orElse(3 * GIB);
    }

    public OptionalLong freeDiskBytes() {
        return optionalLong("sm.bench.free-disk-bytes");
    }

    public OptionalLong maxDbBytes() {
        return optionalLong("sm.bench.max-db-bytes");
    }

    /** Step 6a: the free disk once the benchmark month is dropped, when the operator measured it; else it is estimated. */
    public OptionalLong freeDiskAfterDropBytes() {
        return optionalLong("sm.bench.free-disk-after-drop-bytes");
    }

    /** A t4g.medium's burst baseline is 20 % per vCPU; the rollup's sustained load is compared with it (Step 2b). */
    public int cpuBaselinePercent() {
        return integer("sm.bench.cpu-baseline-percent", 20);
    }

    // ---- steps -----------------------------------------------------------------------------------------------------

    public List<String> steps() {
        return steps;
    }

    public boolean runs(String step) {
        return steps.contains(step);
    }

    // ---- the run ---------------------------------------------------------------------------------------------------

    public java.nio.file.Path outDir() {
        return java.nio.file.Path.of(orDefault("sm.bench.out", "target/bench-evidence"));
    }

    public Optional<java.util.UUID> tenant() {
        String v = props.apply("sm.bench.tenant");
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(java.util.UUID.fromString(v.trim()));
    }

    /** The environment the loaded instances sit in: the gate's Operator is bound there, so reach filtering finds them. */
    public Optional<java.util.UUID> environment() {
        String v = props.apply("sm.bench.environment");
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(java.util.UUID.fromString(v.trim()));
    }

    public int ingestTicks() {
        return integer("sm.bench.ingest.ticks", 20);
    }

    public int ingestSeconds() {
        return integer("sm.bench.ingest.seconds", 120);
    }

    public int rollupChunkHours() {
        return integer("sm.bench.rollup.chunk-hours", 24);
    }

    public int rollupRuns() {
        return integer("sm.bench.rollup.runs", 3);
    }

    /** A command that prints the host's {@code vmstat} lines (for example {@code ssh user@host vmstat 5}); none by default. */
    public List<String> cpuCommand() {
        String v = props.apply("sm.bench.cpu-command");
        return v == null || v.isBlank() ? List.of() : Arrays.asList(v.trim().split("\\s+"));
    }

    @Override
    public String toString() {
        return "BenchSettings[steps=" + steps + ", instances=" + instances() + ", metrics=" + metrics() + ", intervalSeconds="
                + intervalSeconds() + ", days=" + days() + ", rowsPerMonth=" + rowsPerMonth() + ", windowEnd=" + windowEnd
                + ", runtimeUser=" + runtimeUser() + ", adminUser=" + adminUser() + ", passwords=<environment only>]";
    }
}
