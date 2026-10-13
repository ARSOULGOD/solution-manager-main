package com.solace.sm.launcher;

import com.solace.sm.dp.metrics.bench.BenchSettings;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Plan 011 P11-8.1 Step 3: the AS-AN-OPERATOR half of the storage gate. Skipped unless {@code sm.bench.gate.url} is set. It boots
 * {@code SolutionManagerApplication} against the benchmark database, logs in as a seeded Operator and times {@code POST
 * /query/range} over real HTTP for 24 h raw, 7 d raw, 30 d hourly and a 30-day forced raw over 1, 10 and 200 instances
 * ({@link GateRunner#planCases}). {@code sm.bench.gate.steps} selects {@code prepare} (sm_control and the Operator, once) and/or
 * {@code 3}; the default is {@code 3}. Use {@code backend/launcher/scripts/run-storage-gate.sh gate-prepare|gate}.
 *
 * <p>Steps 6b and 6c (the 105 M-row export stream and the default re-check) are NOT here: they need plan 011 P11-7.2's export
 * route and a 105 M-row load, and are the integrator's, after Step 6a.
 */
@EnabledIfSystemProperty(named = "sm.bench.gate.url", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MetricsGateBenchmarkIT {

    private static final java.util.function.Function<String, String> PROPS = name ->
            name.equals("sm.bench.url") ? System.getProperty("sm.bench.gate.url") : System.getProperty(name);

    private static boolean selected(String step) {
        String steps = System.getProperty("sm.bench.gate.steps", "3");
        return java.util.Arrays.stream(steps.split(",")).map(String::trim).anyMatch(step::equals);
    }

    @Test
    @Order(1)
    void prepareTheControlPlaneSchemaAndTheOperator() {
        assumeTrue(selected("prepare"), "step prepare is not selected (sm.bench.gate.steps)");
        try (MetricsGateBenchmark gate = new MetricsGateBenchmark(BenchSettings.from(PROPS, System::getenv), GateConfig.from(PROPS),
                System::getenv, System.out)) {
            gate.prepare();
        }
    }

    @Test
    @Order(2)
    void step3QueryTimingsAsAnOperatorOverHttp() throws Exception {
        assumeTrue(selected("3"), "step 3 is not selected (sm.bench.gate.steps)");
        try (MetricsGateBenchmark gate = new MetricsGateBenchmark(BenchSettings.from(PROPS, System::getenv), GateConfig.from(PROPS),
                System::getenv, System.out)) {
            gate.step3();
        }
    }
}
