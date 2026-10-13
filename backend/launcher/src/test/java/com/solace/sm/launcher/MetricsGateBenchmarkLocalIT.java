package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.metrics.bench.BenchSettings;
import com.solace.sm.dp.metrics.bench.Evidence;
import com.solace.sm.dp.metrics.bench.StorageBenchmark;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.utility.DockerImageName;

/**
 * Plan 011 P11-8.1: the whole gate, at a tiny scale, on a throwaway {@code postgres:14}, in the integrator's order: the data-plane
 * {@code prepare}, Steps 0b, 1 and 2b load and roll up a few instances, then the control-plane {@code prepare}, then Step 3 boots
 * the real application, seeds an Operator, logs in over HTTP and proves the Operator sees the environment the instances were
 * loaded into.
 *
 * <p>⚠️ What this cannot prove here: the query route {@code POST /api/console/v1/query/range} is plan 011 P11-7.2's, and is not in
 * the base this task was built on. Until it is merged, Step 3 stops with the message that says so, and that is what this test
 * asserts; once it is merged the same test asserts the timings instead. The harness's own timing, spacing, retry and refusal rules
 * are proven against a stub in {@link GateRunnerTest}.
 */
class MetricsGateBenchmarkLocalIT {

    @Test
    void theGateBootsAsAnOperatorAndMeasuresOrSaysTheRouteIsNotThere() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        String runtimePassword = "gate-runtime-" + UUID.randomUUID();
        String migratorPassword = "gate-migrator-" + UUID.randomUUID();
        String cpRuntimePassword = "gate-cp-runtime-" + UUID.randomUUID();
        String cpMigratorPassword = "gate-cp-migrator-" + UUID.randomUUID();
        Path out = Files.createTempDirectory("storage-gate-launcher-local");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:14"))) {
            postgres.start();
            Map<String, String> props = new HashMap<>();
            props.put("sm.bench.url", "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName());
            props.put("sm.bench.admin.user", postgres.getUsername());
            props.put("sm.bench.instances", "12");
            props.put("sm.bench.metrics", "5");
            props.put("sm.bench.interval-seconds", "60");
            props.put("sm.bench.days", "2");
            props.put("sm.bench.out", out.toString());
            props.put("sm.bench.gate.runs", "2");
            props.put("sm.bench.gate.spacing-ms", "0");
            props.put("sm.bench.gate.instance-counts", "1,10,200");
            Map<String, String> env = Map.of("SM_BENCH_ADMIN_PASSWORD", postgres.getPassword(), "SM_BENCH_RUNTIME_PASSWORD", runtimePassword,
                    "SM_BENCH_MIGRATOR_PASSWORD", migratorPassword, "SM_BENCH_CP_RUNTIME_PASSWORD", cpRuntimePassword,
                    "SM_BENCH_CP_MIGRATOR_PASSWORD", cpMigratorPassword);
            BenchSettings settings = BenchSettings.from(props::get, env::get);

            try (StorageBenchmark storage = new StorageBenchmark(settings, new PrintStream(new ByteArrayOutputStream()))) {
                storage.prepare();
                storage.step0b();
                storage.step1();
                storage.step2b();
            }

            ByteArrayOutputStream printed = new ByteArrayOutputStream();
            try (MetricsGateBenchmark gate = new MetricsGateBenchmark(settings, GateConfig.from(props::get), env::get, new PrintStream(printed, true))) {
                gate.prepare();
                gate.prepare();                      // a second prepare is harmless
                // the master key is derived from the database's own identity, read by a real query (a real PostgreSQL 14 answers it)
                assertEquals(gate.bootProperties().get(TwoSchemaTestDatabase.MASTER_KEY), gate.bootProperties().get(TwoSchemaTestDatabase.MASTER_KEY));
                String log;
                try {
                    gate.step3();
                } catch (IllegalStateException refused) {
                    assertTrue(refused.getMessage().contains("does not serve the monitoring query routes"), refused.getMessage());
                    assertEquals("true", gate.results().get("3.operatorSeesTheLoadedEnvironment").orElseThrow(),
                            "the Operator was seeded, logged in over HTTP, and sees the environment of the loaded instances");
                    log = Files.readString(out.resolve(Evidence.LOG));
                    assertFalse(log.contains(runtimePassword) || log.contains(cpRuntimePassword) || log.contains(cpMigratorPassword),
                            "no password reaches the evidence");
                    return;
                }
                // The integrator's live run died here: a second boot against the same benchmark database minted a new master key and
                // the first boot's canary row could not be opened. One key per benchmark database means a re-run just works.
                gate.step3();
                assertTrue(gate.results().get("3.24h-raw.1.p50Millis").isPresent(), "the route is served: its timings are recorded");
                assertTrue(gate.results().get("3.30d-hourly.10.p95Millis").isPresent());
                // W4-11: a timing is evidence only if the query matched data. The seed backdates the placements to before the loaded window.
                for (String shape : new String[] {"24h-raw.1", "7d-raw.1", "30d-hourly.10", "30d-forced-raw.10"}) {
                    assertTrue(Integer.parseInt(gate.results().get("3." + shape + ".series").orElseThrow()) > 0, shape + " answered with no series");
                    assertTrue(Long.parseLong(gate.results().get("3." + shape + ".points").orElseThrow()) > 0, shape + " answered with no points");
                }
            }
        }
    }
}
