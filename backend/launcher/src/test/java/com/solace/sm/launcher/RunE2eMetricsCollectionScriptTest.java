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
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan 011 P11-4.4: {@code backend/launcher/scripts/run-e2e-metrics-collection.sh} reads the target from the environment
 * only, passes it as {@code -D} properties, runs ONLY the live collection IT, says it is READ-ONLY unless the operator sets
 * {@code SM_E2E_CONFIRM_DISRUPT=yes} exactly, and refuses a partial second Cache. Every case runs the script with
 * {@code --check}: it validates and prints the command, and runs nothing and contacts nothing.
 */
class RunE2eMetricsCollectionScriptTest {

    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);

    @TempDir
    Path tmp;

    private Path script;
    private Map<String, String> env;

    @BeforeEach
    void aCompleteEnvironment() throws IOException {
        script = Path.of("scripts/run-e2e-metrics-collection.sh").toAbsolutePath();
        assumeTrue(Files.isRegularFile(script), "the script is at backend/launcher/scripts (run from the module)");
        Path key = Files.writeString(tmp.resolve("id_e2e"), "not a key");
        env = new LinkedHashMap<>();
        env.put("SM_E2E_SSH_HOST", "e2e-host.example.test");
        env.put("SM_E2E_SSH_USER", "ec2-user");
        env.put("SM_E2E_SSH_FINGERPRINT", FINGERPRINT);
        env.put("SM_E2E_SSH_KEY_FILE", key.toString());
        env.put("SM_E2E_CONTAINER", "cache-engine");
        env.put("SM_E2E_CONFIG_DIR", "/srv/cache-engine/config");
    }

    private record Result(int exit, String out, String err) {
    }

    private Result run(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", script.toString()));
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

    @Test
    void aCompleteTargetPrintsTheCommandWithOnlyTheLiveCollectionItAndSaysItIsReadOnly() throws Exception {
        Result r = run("--check");

        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("-Dit.test=MetricsCollectionLiveIT"), r.out());
        assertFalse(r.out().contains("SkeletonWalkthroughIT") || r.out().contains("MeshResilienceIT"),
                "the T6.6 walkthroughs are not run from here: " + r.out());
        assertTrue(r.out().contains("-Dtest=NoSuchUnit"), "no unit test of the modules built with -am runs");
        for (String property : new String[] {"-Dsm.walkthrough.ssh.host=e2e-host.example.test",
                "-Dsm.walkthrough.ssh.container=cache-engine", "-Dsm.walkthrough.config-dir=/srv/cache-engine/config",
                "-Dsm.walkthrough.ssh.fingerprint=" + FINGERPRINT}) {
            assertTrue(r.out().contains(property), property + " in " + r.out());
        }
        assertTrue(r.out().contains("READ-ONLY"), "it says it only reads: " + r.out());
        assertFalse(r.out().contains("metrics.disruptive"), "the disruptive switch is not passed unless asked");
        assertFalse(r.out().contains("DISRUPTIVE"), r.out());
        assertTrue(r.out().contains("nothing was run"), "--check runs nothing");
        assertFalse(r.out().contains("CONFIRM_WRITE"), "it never asks to write: it does not");
    }

    @Test
    void theDisruptiveRunNeedsExactlyYesAndSaysSoLoudly() throws Exception {
        env.put("SM_E2E_CONFIRM_DISRUPT", "yes");
        Result yes = run("--check");
        assertEquals(0, yes.exit(), yes.err());
        assertTrue(yes.out().contains("-Dsm.walkthrough.metrics.disruptive=yes"), yes.out());
        assertTrue(yes.out().contains("DISRUPTIVE"), "it says loudly that it restarts and stops a container: " + yes.out());

        env.put("SM_E2E_CONFIRM_DISRUPT", "true");
        Result stray = run("--check");
        assertEquals(2, stray.exit(), "only the word yes");
        assertTrue(stray.err().contains("SM_E2E_CONFIRM_DISRUPT"), stray.err());
        assertEquals("", stray.out(), "no command was assembled");
    }

    @Test
    void missTrafficOnTheSecondCacheNeedsExactlyYesAndIsNeverPassedOtherwise() throws Exception {
        Result off = run("--check");
        assertEquals(0, off.exit(), off.err());
        assertFalse(off.out().contains("metrics.traffic"), "the traffic switch is not passed unless asked: " + off.out());

        env.put("SM_E2E_ATTRIBUTION_TRAFFIC", "yes");
        Result alone = run("--check");
        assertEquals(2, alone.exit(), "the traffic goes to the second Cache only, so one needs to exist");
        assertTrue(alone.err().contains("SM_E2E_ATTRIBUTION_TRAFFIC"), alone.err());
        env.put("SM_E2E_SECOND_CONTAINER", "cache-engine-2");
        env.put("SM_E2E_SECOND_CONFIG_DIR", "/srv/cache-engine/config/second");
        env.put("SM_E2E_SECOND_HOST_PORT", "28081");
        Result yes = run("--check");
        assertEquals(0, yes.exit(), yes.err());
        assertTrue(yes.out().contains("-Dsm.walkthrough.metrics.traffic=yes"), yes.out());
        assertTrue(yes.out().contains("GET /api/cache?key=sm-attribution"), "it says what it sends: " + yes.out());

        env.put("SM_E2E_ATTRIBUTION_TRAFFIC", "true");
        Result stray = run("--check");
        assertEquals(2, stray.exit(), "only the word yes");
        assertTrue(stray.err().contains("SM_E2E_ATTRIBUTION_TRAFFIC"), stray.err());
        assertEquals("", stray.out(), "no command was assembled");
    }

    @Test
    void aSecondCacheIsAllThreeOrNoneAndItsPropertiesArePassedThrough() throws Exception {
        env.put("SM_E2E_SECOND_CONTAINER", "cache-engine-2");
        Result partial = run("--check");
        assertEquals(2, partial.exit());
        assertTrue(partial.err().contains("SM_E2E_SECOND_CONTAINER"), partial.err());
        assertTrue(partial.err().contains("together"), partial.err());

        env.put("SM_E2E_SECOND_CONFIG_DIR", "/srv/cache-engine/config/second");
        env.put("SM_E2E_SECOND_HOST_PORT", "28081");
        env.put("SM_E2E_METRICS_HOST_PORT", "18081");
        env.put("SM_E2E_SECOND_MODE", "direct-http");
        env.put("SM_E2E_METRICS_MINUTES", "3");
        Result whole = run("--check");

        assertEquals(0, whole.exit(), whole.err());
        for (String property : new String[] {"-Dsm.walkthrough.metrics.second.container=cache-engine-2",
                "-Dsm.walkthrough.metrics.second.config-dir=/srv/cache-engine/config/second",
                "-Dsm.walkthrough.metrics.second.host-port=28081", "-Dsm.walkthrough.metrics.second.mode=direct-http",
                "-Dsm.walkthrough.metrics.host-port=18081", "-Dsm.walkthrough.metrics.minutes=3"}) {
            assertTrue(whole.out().contains(property), property + " in " + whole.out());
        }
    }

    @Test
    void everyMissingVariableIsNamedAtOnceWithoutAnyValueAndNothingRuns() throws Exception {
        env.clear();

        Result r = run("--check");

        assertEquals(2, r.exit());
        for (String name : new String[] {"SM_E2E_SSH_HOST", "SM_E2E_SSH_USER", "SM_E2E_SSH_FINGERPRINT", "SM_E2E_SSH_KEY_FILE",
                "SM_E2E_CONTAINER", "SM_E2E_CONFIG_DIR"}) {
            assertTrue(r.err().contains(name), name + " named: " + r.err());
        }
        assertTrue(r.err().contains("nothing was started and nothing was contacted"));
        assertEquals("", r.out(), "no command was assembled");
    }

    @Test
    void aMalformedFingerprintOrAnUnreadableKeyIsRefusedNamingTheVariableNotTheValue() throws Exception {
        env.put("SM_E2E_SSH_FINGERPRINT", "not-a-fingerprint-secret");
        env.put("SM_E2E_SSH_KEY_FILE", "/no/such/key-secret-path");

        Result r = run("--check");

        assertEquals(2, r.exit());
        assertTrue(r.err().contains("SM_E2E_SSH_FINGERPRINT"), r.err());
        assertTrue(r.err().contains("SM_E2E_SSH_KEY_FILE"), r.err());
        assertFalse(r.err().contains("not-a-fingerprint-secret") || r.err().contains("key-secret-path"),
                "a refusal names the variable, never its value");
    }
}
