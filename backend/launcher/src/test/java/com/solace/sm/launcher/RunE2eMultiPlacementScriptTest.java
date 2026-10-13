package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Plan 008 S5 Step 2 (live gate L2): {@code backend/launcher/scripts/run-e2e-multi-placement.sh} reads the target from the
 * environment only, passes it as {@code -D} properties, runs ONLY the live multi-placement IT, requires the second Cache,
 * says it WRITES (both configuration files, restored afterwards) and refuses unless {@code SM_E2E_CONFIRM_WRITE=yes} exactly
 * (W4 review W4A-5, as {@code run-e2e-walkthrough.sh} does), says it is DISRUPTIVE only when the operator sets
 * {@code SM_E2E_CONFIRM_DISRUPT=yes} exactly, and says that only a disruptive pass satisfies L2 (W4A-6). Every case runs the script with {@code --check}: it validates and prints the
 * command, and runs nothing and contacts nothing.
 */
class RunE2eMultiPlacementScriptTest {

    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);

    @TempDir
    Path tmp;

    private Path script;
    private Map<String, String> env;

    @BeforeEach
    void aCompleteEnvironment() throws IOException {
        script = Path.of("scripts/run-e2e-multi-placement.sh").toAbsolutePath();
        assertTrue(Files.isRegularFile(script), "the script is at backend/launcher/scripts (run from the module): " + script);
        Path key = Files.writeString(tmp.resolve("id_e2e"), "not a key");
        env = new LinkedHashMap<>();
        env.put("SM_E2E_SSH_HOST", "e2e-host.example.test");
        env.put("SM_E2E_SSH_USER", "ec2-user");
        env.put("SM_E2E_SSH_FINGERPRINT", FINGERPRINT);
        env.put("SM_E2E_SSH_KEY_FILE", key.toString());
        env.put("SM_E2E_CONTAINER", "cache-engine");
        env.put("SM_E2E_CONFIG_DIR", "/srv/cache-engine/config");
        env.put("SM_E2E_SECOND_CONTAINER", "cache-engine-2");
        env.put("SM_E2E_SECOND_CONFIG_DIR", "/srv/cache-engine/config/second");
        env.put("SM_E2E_SECOND_HOST_PORT", "28081");
        env.put("SM_E2E_CONFIRM_WRITE", "yes");
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
    void aCompleteTargetPrintsTheCommandWithOnlyTheLiveMultiPlacementItAndSaysWhatItWrites() throws Exception {
        Result r = run("--check");

        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("-Dit.test=MultiPlacementLiveIT"), r.out());
        assertFalse(r.out().contains("SkeletonWalkthroughIT") || r.out().contains("MetricsCollectionLiveIT")
                || r.out().contains("MultiPlacementRehearsalIT"), "no other walkthrough is run from here: " + r.out());
        assertTrue(r.out().contains("-Dtest=NoSuchUnit"), "no unit test of the modules built with -am runs");
        for (String property : new String[] {"-Dsm.walkthrough.ssh.host=e2e-host.example.test",
                "-Dsm.walkthrough.ssh.container=cache-engine", "-Dsm.walkthrough.config-dir=/srv/cache-engine/config",
                "-Dsm.walkthrough.ssh.fingerprint=" + FINGERPRINT,
                "-Dsm.walkthrough.multi.second.container=cache-engine-2",
                "-Dsm.walkthrough.multi.second.config-dir=/srv/cache-engine/config/second",
                "-Dsm.walkthrough.multi.second.host-port=28081"}) {
            assertTrue(r.out().contains(property), property + " in " + r.out());
        }
        assertTrue(r.out().contains("WRITES"), "it says it writes the two configuration files: " + r.out());
        assertTrue(r.out().contains("restored"), "and that it restores them: " + r.out());
        assertFalse(r.out().contains("multi.disruptive"), "the disruptive switch is not passed unless asked");
        assertFalse(r.out().contains("DISRUPTIVE"), r.out());
        assertTrue(r.out().contains("nothing was run"), "--check runs nothing");
    }

    @Test
    void theRunRefusesUnlessWritingBothFilesIsConfirmedWithExactlyYes() throws Exception {
        env.remove("SM_E2E_CONFIRM_WRITE");
        Result unset = run("--check");
        assertEquals(2, unset.exit(), "the run overwrites both Caches' files: it must be confirmed");
        assertTrue(unset.err().contains("SM_E2E_CONFIRM_WRITE"), unset.err());
        assertEquals("", unset.out(), "no command was assembled");

        for (String stray : new String[] {"true", "1", "YES", ""}) {
            env.put("SM_E2E_CONFIRM_WRITE", stray);
            Result refused = run("--check");
            assertEquals(2, refused.exit(), "only the word yes: '" + stray + "'");
            assertTrue(refused.err().contains("SM_E2E_CONFIRM_WRITE"), refused.err());
            assertEquals("", refused.out(), "no command was assembled");
        }
    }

    @Test
    void aNonDisruptiveRunSaysItDoesNotCloseL2AndTheDisruptiveOneDoesNotSayThat() throws Exception {
        Result plain = run("--check");
        assertEquals(0, plain.exit(), plain.err());
        assertTrue(plain.out().contains("does NOT close L2"), "a partial pass must not read as L2: " + plain.out());

        env.put("SM_E2E_CONFIRM_DISRUPT", "yes");
        Result disruptive = run("--check");
        assertEquals(0, disruptive.exit(), disruptive.err());
        assertFalse(disruptive.out().contains("does NOT close L2"), disruptive.out());

        Result help = run("--help");
        assertEquals(0, help.exit(), help.err());
        assertTrue(help.out().contains("ONLY A DISRUPTIVE PASS SATISFIES L2"), "the header says so too");
        assertTrue(help.out().contains("OR-110"), "the authority for the disruptive run is cited");
        assertFalse(help.out().contains("never before the owner has said"), "no stale owner-permission gate");
    }

    @Test
    void theDisruptiveRunNeedsExactlyYesAndSaysSoLoudly() throws Exception {
        env.put("SM_E2E_CONFIRM_DISRUPT", "yes");
        Result yes = run("--check");
        assertEquals(0, yes.exit(), yes.err());
        assertTrue(yes.out().contains("-Dsm.walkthrough.multi.disruptive=yes"), yes.out());
        assertTrue(yes.out().contains("DISRUPTIVE"), "it says loudly that it stops and restarts containers: " + yes.out());

        for (String stray : new String[] {"true", "1", "YES"}) {
            env.put("SM_E2E_CONFIRM_DISRUPT", stray);
            Result refused = run("--check");
            assertEquals(2, refused.exit(), "only the word yes: " + stray);
            assertTrue(refused.err().contains("SM_E2E_CONFIRM_DISRUPT"), refused.err());
            assertEquals("", refused.out(), "no command was assembled");
        }
    }

    @Test
    void theSecondCacheIsRequiredAllThreeOfItAndNothingRunsWithout() throws Exception {
        for (String name : new String[] {"SM_E2E_SECOND_CONTAINER", "SM_E2E_SECOND_CONFIG_DIR", "SM_E2E_SECOND_HOST_PORT"}) {
            String value = env.remove(name);

            Result r = run("--check");

            assertEquals(2, r.exit(), name);
            assertTrue(r.err().contains(name), name + " named: " + r.err());
            assertEquals("", r.out(), "no command was assembled");
            env.put(name, value);
        }
    }

    @Test
    void everyMissingVariableIsNamedAtOnceWithoutAnyValueAndNothingRuns() throws Exception {
        env.clear();

        Result r = run("--check");

        assertEquals(2, r.exit());
        for (String name : new String[] {"SM_E2E_SSH_HOST", "SM_E2E_SSH_USER", "SM_E2E_SSH_FINGERPRINT", "SM_E2E_SSH_KEY_FILE",
                "SM_E2E_CONTAINER", "SM_E2E_CONFIG_DIR", "SM_E2E_SECOND_CONTAINER", "SM_E2E_SECOND_CONFIG_DIR",
                "SM_E2E_SECOND_HOST_PORT", "SM_E2E_CONFIRM_WRITE"}) {
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

    @Test
    void theOptionalWalkthroughVariablesArePassedThroughWhenSet() throws Exception {
        env.put("SM_E2E_ACQUISITION", "direct-http");
        env.put("SM_E2E_HEALTH_PORT", "18080");
        env.put("SM_E2E_SSH_PORT", "2222");

        Result r = run("--check");

        assertEquals(0, r.exit(), r.err());
        for (String property : new String[] {"-Dsm.walkthrough.acquisition=direct-http",
                "-Dsm.walkthrough.health-port=18080", "-Dsm.walkthrough.ssh.port=2222"}) {
            assertTrue(r.out().contains(property), property + " in " + r.out());
        }
    }
}
