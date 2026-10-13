package com.solace.sm.dp.metrics.bench;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Plan 011 P11-8.1: the data-plane storage gate, one test method per step, each selectable alone, by {@code sm.bench.steps}
 * (a comma list of {@link BenchSettings#STEP_ORDER}) or by a JUnit method filter ({@code -Dit.test='...#step0b'}). A step that
 * is not selected is SKIPPED with that reason, never silently passed. The concrete class says where the database is:
 * {@link MetricsStorageBenchmarkIT} (the real one, gated on {@code sm.bench.url}) and {@link MetricsStorageBenchmarkLocalIT}
 * (a throwaway container at a tiny scale, so the gate itself cannot rot).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class MetricsStorageBenchmarkBase {

    protected BenchSettings settings;
    protected StorageBenchmark bench;

    /** Where the database is and at what scale. */
    protected abstract BenchSettings settings();

    @BeforeAll
    void open() {
        settings = settings();
        bench = new StorageBenchmark(settings, System.out);
        bench.evidence().note("all", "settings: " + settings);
    }

    @AfterAll
    void close() {
        if (bench != null) {
            bench.close();
        }
    }

    private void selected(String step) {
        assumeTrue(settings.runs(step), "step " + step + " is not selected (sm.bench.steps=" + settings.steps() + ")");
    }

    @Test
    @Order(0)
    void prepare() {
        selected("prepare");
        bench.prepare();
    }

    @Test
    @Order(1)
    void step0bOneDayThenProjectTheMonth() {
        selected("0b");
        bench.step0b();
    }

    @Test
    @Order(2)
    void step1LoadTheMonthAndMeasureBytesWalAndVacuum() {
        selected("1");
        bench.step1();
    }

    @Test
    @Order(3)
    void step2InsertRate() {
        selected("2");
        bench.step2();
    }

    @Test
    @Order(4)
    void step2bRollupCost() {
        selected("2b");
        bench.step2b();
    }

    @Test
    @Order(5)
    void step5TheScaledFiveSecondPoint() {
        selected("5");
        bench.step5();
    }

    @Test
    @Order(6)
    void step6aDiskArithmeticForTheStream() {
        selected("6a");
        bench.step6a();
    }
}
