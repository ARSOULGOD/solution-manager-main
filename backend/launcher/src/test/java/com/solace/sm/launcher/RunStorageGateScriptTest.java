package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-8.1: {@code backend/launcher/scripts/run-storage-gate.sh} reads the database and every password from the
 * environment, never prints a password, reaches only a loopback database (through the integrator's SSH port-forward), runs ONE
 * step per call, and says it deletes nothing. Every case runs the script with {@code --check}: it validates and prints the
 * command, and runs nothing and contacts nothing.
 */
class RunStorageGateScriptTest {

    private static final String ADMIN = "admin-pw-" + "A1b2C3";
    private static final String RUNTIME = "runtime-pw-" + "D4e5F6";
    private static final String MIGRATOR = "migrator-pw-" + "G7h8I9";
    private static final String CP_RUNTIME = "cp-runtime-pw-" + "J1k2L3";
    private static final String CP_MIGRATOR = "cp-migrator-pw-" + "M4n5O6";

    private Path script;
    private Map<String, String> env;

    @BeforeEach
    void aCompleteEnvironment() {
        script = Path.of("scripts/run-storage-gate.sh").toAbsolutePath();
        assumeTrue(Files.isRegularFile(script), "the script is at backend/launcher/scripts (run from the module)");
        env = new LinkedHashMap<>();
        env.put("SM_BENCH_URL", "jdbc:postgresql://127.0.0.1:55432/sm");
        env.put("SM_BENCH_ADMIN_PASSWORD", ADMIN);
        env.put("SM_BENCH_RUNTIME_PASSWORD", RUNTIME);
        env.put("SM_BENCH_MIGRATOR_PASSWORD", MIGRATOR);
        env.put("SM_BENCH_CP_RUNTIME_PASSWORD", CP_RUNTIME);
        env.put("SM_BENCH_CP_MIGRATOR_PASSWORD", CP_MIGRATOR);
    }

    private record Result(int exit, String out, String err) {
    }

    private Result run(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", script.toString(), "--check"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().put("PATH", System.getenv("PATH"));
        builder.environment().putAll(env);
        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertTrue(finished, "the script did not finish");
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void noPasswordAnywhere(Result r) {
        for (String secret : new String[] {ADMIN, RUNTIME, MIGRATOR, CP_RUNTIME, CP_MIGRATOR}) {
            assertFalse(r.out().contains(secret) || r.err().contains(secret), "a password was printed: " + secret.substring(0, 6) + "...");
        }
    }

    @Test
    void prepareRunsOnlyTheDataPlaneItWithTheStepPrepareAndPrintsNoPassword() throws Exception {
        Result r = run("prepare");
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("-pl dp-core"), r.out());
        assertTrue(r.out().contains("-Dit.test=MetricsStorageBenchmarkIT"), r.out());
        assertTrue(r.out().contains("-Dtest=NoSuchUnit"), "no unit test of the modules built with -am runs");
        assertTrue(r.out().contains("-Dsm.bench.steps=prepare"), r.out());
        assertTrue(r.out().contains("-Dsm.bench.url=jdbc:postgresql://127.0.0.1:55432/sm"), r.out());
        assertTrue(r.out().contains("nothing was run"), "--check runs nothing");
        assertTrue(r.out().contains("Nothing is deleted"), r.out());
        noPasswordAnywhere(r);
    }

    @Test
    void oneStepIsOneStepAndTheDefaultScaleIsTheRealMonth() throws Exception {
        Result r = run("step", "0b");
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("-Dsm.bench.steps=0b"), "Step 0b alone, so the integrator can stop on the projection: " + r.out());
        assertFalse(r.out().contains("SCALED-DOWN"), "no scale given: the plan's S-A month: " + r.out());
        assertTrue(r.out().contains("instances 200 x metrics 5 at 30 s for 30 days"), r.out());
        assertTrue(r.out().contains("YOU compare it with df"), "no free disk given: says so: " + r.out());
        assertFalse(r.out().contains("-Dsm.bench.instances"), "nothing is passed that was not asked for: " + r.out());
        Result all = run("step", "all");
        assertTrue(all.out().contains("-Dsm.bench.steps=0b,1,2,2b,5,6a"), all.out());
        noPasswordAnywhere(r);
    }

    @Test
    void aScaledRunSaysSoAndPassesTheCounts() throws Exception {
        env.put("SM_BENCH_INSTANCES", "5");
        env.put("SM_BENCH_DAYS", "2");
        Result r = run("step", "1");
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("SCALED-DOWN, NOT S-A"), r.out());
        assertTrue(r.out().contains("-Dsm.bench.instances=5") && r.out().contains("-Dsm.bench.days=2"), r.out());
    }

    @Test
    void theFreeDiskSetsTheCeilingAboveTheFloorAndAnExplicitCeilingWins() throws Exception {
        env.put("SM_BENCH_FREE_DISK_BYTES", "18000000000");
        Result r = run("step", "0b");
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("-Dsm.bench.free-disk-bytes=18000000000"), r.out());
        assertTrue(r.out().contains("-Dsm.bench.max-db-bytes=" + (18_000_000_000L - 3_221_225_472L)), "free minus the 3 GiB floor: " + r.out());
        env.put("SM_BENCH_MAX_DB_BYTES", "1000");
        assertTrue(run("step", "0b").out().contains("-Dsm.bench.max-db-bytes=1000"));
    }

    @Test
    void theGateRunsTheLauncherItAndNeedsTheControlPlanePasswords() throws Exception {
        Result r = run("gate");
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("-pl launcher"), r.out());
        assertTrue(r.out().contains("-Dit.test=MetricsGateBenchmarkIT"), r.out());
        assertTrue(r.out().contains("-Dsm.bench.gate.url=jdbc:postgresql://127.0.0.1:55432/sm"), r.out());
        assertTrue(r.out().contains("-Dsm.bench.gate.steps=3"), r.out());
        noPasswordAnywhere(r);
        assertTrue(run("gate-prepare").out().contains("-Dsm.bench.gate.steps=prepare"));

        env.remove("SM_BENCH_CP_RUNTIME_PASSWORD");
        Result missing = run("gate");
        assertEquals(2, missing.exit());
        assertTrue(missing.err().contains("SM_BENCH_CP_RUNTIME_PASSWORD"), missing.err());
        assertEquals("", missing.out(), "no command was assembled");
        noPasswordAnywhere(missing);
    }

    @Test
    void aMissingPasswordIsNamedAndNothingIsAssembled() throws Exception {
        env.remove("SM_BENCH_ADMIN_PASSWORD");
        env.remove("SM_BENCH_RUNTIME_PASSWORD");
        Result r = run("step", "2");
        assertEquals(2, r.exit());
        assertTrue(r.err().contains("SM_BENCH_ADMIN_PASSWORD") && r.err().contains("SM_BENCH_RUNTIME_PASSWORD"), "every problem at once: " + r.err());
        assertEquals("", r.out());
        noPasswordAnywhere(r);
    }

    @Test
    void onlyALoopbackDatabaseWithNoCredentialsInItIsAccepted() throws Exception {
        env.put("SM_BENCH_URL", "jdbc:postgresql://203.0.113.9:5432/sm");
        Result remote = run("step", "0b");
        assertEquals(2, remote.exit());
        assertTrue(remote.err().contains("loopback") && remote.err().contains("port-forward"), remote.err());
        assertFalse(remote.err().contains("203.0.113.9"), "the value is not echoed: " + remote.err());
        env.put("SM_BENCH_ALLOW_NON_LOOPBACK", "yes");
        assertEquals(0, run("step", "0b").exit(), "the override is explicit and exact");

        env.remove("SM_BENCH_ALLOW_NON_LOOPBACK");
        env.put("SM_BENCH_URL", "jdbc:postgresql://127.0.0.1:5432/sm?user=postgres&password=leaked-secret");
        Result creds = run("step", "0b");
        assertEquals(2, creds.exit());
        assertFalse(creds.err().contains("leaked-secret") || creds.out().contains("leaked-secret"), creds.err());
    }

    @Test
    void anUnknownCommandOrStepIsRefused() throws Exception {
        assertEquals(2, run("drop-everything").exit());
        Result badStep = run("step", "7");
        assertEquals(2, badStep.exit());
        assertTrue(badStep.err().contains("0b 1 2 2b 5 6a all"), badStep.err());
        assertEquals(2, run().exit(), "no command at all");
    }

    @Test
    void theHostsCpuAndFreeDiskAreReadOnlyOverSshAndOnlyWhenAsked() throws Exception {
        Result none = run("step", "2b");
        assertFalse(none.out().contains("ssh"), "no ssh unless asked: " + none.out());
        env.put("SM_BENCH_SSH_TARGET", "sm-test@e2e.example.test");
        Result cpu = run("step", "2b");
        assertEquals(0, cpu.exit(), cpu.err());
        assertTrue(cpu.out().contains("-Dsm.bench.cpu-command=ssh -o BatchMode=yes sm-test@e2e.example.test vmstat 5"), cpu.out());
        Result df = run("free-disk");
        assertEquals(0, df.exit(), df.err());
        assertTrue(df.out().contains("ssh -o BatchMode=yes sm-test@e2e.example.test df -B1 --output=avail /var/lib/docker"), df.out());
        assertTrue(df.out().contains("nothing was contacted"), df.out());
        env.put("SM_BENCH_CPU_COMMAND", "something else");
        assertEquals(2, run("step", "2b").exit(), "one source for the vmstat command");
    }

    @Test
    void theHeaderSaysWhatItWritesAndHowTheTunnelIsMade() throws IOException {
        String text = Files.readString(script);
        assertTrue(text.contains("ssh -N -L 127.0.0.1:55432:127.0.0.1:55432"), "the port-forward is documented");
        assertTrue(text.contains("-p 127.0.0.1:55432:5432") && text.contains("--memory 1536m") && text.contains("-v sm_bench_pgdata"),
                "the container line binds loopback only, names a volume and limits memory");
        assertTrue(text.contains("does NOT delete anything"), "Step 8 is the integrator's");
        assertTrue(text.contains("PASSWORDS ARE READ FROM THE ENVIRONMENT ONLY"));
    }
}
