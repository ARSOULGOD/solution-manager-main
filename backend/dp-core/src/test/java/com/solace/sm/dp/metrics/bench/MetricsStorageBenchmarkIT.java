package com.solace.sm.dp.metrics.bench;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Plan 011 P11-8.1: the REAL storage gate. Skipped unless {@code sm.bench.url} is set, so an ordinary build never runs it.
 * It runs against the PostgreSQL 14 the operator started on the E2E host (reached through an SSH port-forward), at the
 * plan's real S-A month unless {@code sm.bench.instances}, {@code sm.bench.metrics}, {@code sm.bench.interval-seconds} or
 * {@code sm.bench.days} say otherwise. Use {@code backend/launcher/scripts/run-storage-gate.sh}: it reads the passwords from the
 * environment, runs one step at a time and stops after Step 0b.
 *
 * <p>The step methods, in the plan's order: {@code prepare} (not a plan step), {@code step0b…}, {@code step1…}, {@code step2…},
 * {@code step2b…}, {@code step5…}, {@code step6a…}. The as-an-Operator query timings (Step 3) are
 * {@code MetricsGateBenchmarkIT}'s, in the launcher.
 */
@EnabledIfSystemProperty(named = "sm.bench.url", matches = ".+")
class MetricsStorageBenchmarkIT extends MetricsStorageBenchmarkBase {

    @Override
    protected BenchSettings settings() {
        return BenchSettings.fromSystem();
    }
}
