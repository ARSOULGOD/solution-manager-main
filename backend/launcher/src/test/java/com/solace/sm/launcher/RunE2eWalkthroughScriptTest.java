package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan 010 T6.6: {@code backend/launcher/scripts/run-e2e-walkthrough.sh} reads the target from the environment only, passes
 * it as {@code -D} properties, never passes the broker password anywhere but the environment, runs only the two live ITs,
 * says where the evidence goes, and refuses to start without an explicit write confirmation. Every case here runs the script
 * with {@code --check}: it validates and prints the command, and runs nothing and contacts nothing.
 */
class RunE2eWalkthroughScriptTest {

    private static final String PASSWORD = "S3CRET-broker-password-0001";
    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);

    @TempDir
    Path tmp;

    private Path script;
    private Map<String, String> env;

    @BeforeEach
    void aCompleteEnvironment() throws IOException {
        script = Path.of("scripts/run-e2e-walkthrough.sh").toAbsolutePath();
        assumeTrue(Files.isRegularFile(script), "the script is at backend/launcher/scripts (run from the module)");
        Path key = Files.writeString(tmp.resolve("id_e2e"), "not a key");
        env = new LinkedHashMap<>();
        env.put("SM_E2E_SSH_HOST", "e2e-host.example.test");
        env.put("SM_E2E_SSH_USER", "ec2-user");
        env.put("SM_E2E_SSH_FINGERPRINT", FINGERPRINT);
        env.put("SM_E2E_SSH_KEY_FILE", key.toString());
        env.put("SM_E2E_CONTAINER", "cache-engine");
        env.put("SM_E2E_CONFIG_DIR", "/srv/cache-engine/config");
        env.put("SM_E2E_CONFIRM_WRITE", "yes");
    }

    private record Result(int exit, String out, String err) {
    }

    private Result run(String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("bash", script.toString()));
        command.addAll(java.util.List.of(args));
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
    void aCompleteTargetPrintsTheCommandWithOnlyTheTwoLiveItsAndTheTargetAsSystemProperties() throws Exception {
        Result r = run("--check");

        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("-Dit.test=SkeletonWalkthroughIT,MeshResilienceIT"), "only the two live ITs: " + r.out());
        assertTrue(r.out().contains("-Dtest=NoSuchUnit"), "no unit test of the modules built with -am runs");
        for (String property : new String[] {"-Dsm.walkthrough.ssh.host=e2e-host.example.test", "-Dsm.walkthrough.ssh.port=22",
                "-Dsm.walkthrough.ssh.user=ec2-user", "-Dsm.walkthrough.ssh.fingerprint=" + FINGERPRINT,
                "-Dsm.walkthrough.ssh.container=cache-engine", "-Dsm.walkthrough.config-dir=/srv/cache-engine/config"}) {
            assertTrue(r.out().contains(property), property + " in " + r.out());
        }
        assertTrue(r.out().contains("-Dsm.walkthrough.ssh.key-file="), "the key file is passed as a PATH");
        assertTrue(r.out().contains("evidence"), "it says where the evidence goes");
        assertTrue(r.out().contains("walkthrough-evidence"), r.out());
        assertTrue(r.out().contains("nothing was run"), "--check runs nothing");
    }

    @Test
    void theBrokerPasswordIsNeverPrintedNorPassedAsAPropertyOnlyTheEnvironmentCarriesIt() throws Exception {
        env.put("SM_E2E_BROKER_HOST", "tcp://cache-broker.example.test:55555");
        env.put("SM_E2E_BROKER_VPN", "cache-vpn");
        env.put("SM_E2E_BROKER_USERNAME", "cache-client");
        env.put("SM_E2E_BROKER_PASSWORD", PASSWORD);

        Result r = run("--check");

        assertEquals(0, r.exit(), r.err());
        assertFalse(r.out().contains(PASSWORD), "never printed");
        assertFalse(r.err().contains(PASSWORD), "never printed");
        assertFalse(r.out().contains("broker.password"), "there is no property for it: the walkthrough refuses one");
        assertTrue(r.out().contains("-Dsm.walkthrough.cache.broker.host=tcp://cache-broker.example.test:55555"), r.out());
        assertTrue(r.out().contains("SM_E2E_BROKER_PASSWORD"), "it says where the password comes from");
    }

    @Test
    void everyMissingVariableIsNamedAtOnceWithoutAnyValueAndNothingRuns() throws Exception {
        env.clear();

        Result r = run("--check");

        assertEquals(2, r.exit());
        for (String name : new String[] {"SM_E2E_SSH_HOST", "SM_E2E_SSH_USER", "SM_E2E_SSH_FINGERPRINT", "SM_E2E_SSH_KEY_FILE",
                "SM_E2E_CONTAINER", "SM_E2E_CONFIG_DIR", "SM_E2E_CONFIRM_WRITE"}) {
            assertTrue(r.err().contains(name), name + " named: " + r.err());
        }
        assertTrue(r.err().contains("nothing was started and nothing was contacted"));
        assertEquals("", r.out(), "no command was assembled");
    }

    @Test
    void theRunRefusesWithoutTheExplicitConfirmationThatItWritesToTheHost() throws Exception {
        env.remove("SM_E2E_CONFIRM_WRITE");

        Result r = run("--check");

        assertEquals(2, r.exit());
        assertTrue(r.err().contains("SM_E2E_CONFIRM_WRITE"), r.err());
        assertTrue(r.err().contains("/srv/cache-engine/config"), "it names what would be overwritten: " + r.err());
    }

    @Test
    void aPartialBrokerAPasswordWithoutABrokerAndAMalformedFingerprintAreRefused() throws Exception {
        env.put("SM_E2E_BROKER_HOST", "tcp://cache-broker.example.test:55555");
        Result partial = run("--check");
        assertEquals(2, partial.exit());
        assertTrue(partial.err().contains("together"), partial.err());

        env.remove("SM_E2E_BROKER_HOST");
        env.put("SM_E2E_BROKER_PASSWORD", PASSWORD);
        Result orphan = run("--check");
        assertEquals(2, orphan.exit());
        assertTrue(orphan.err().contains("SM_E2E_BROKER_PASSWORD"), orphan.err());
        assertFalse(orphan.err().contains(PASSWORD));

        env.remove("SM_E2E_BROKER_PASSWORD");
        env.put("SM_E2E_SSH_FINGERPRINT", "MD5:ab:cd");
        Result bad = run("--check");
        assertEquals(2, bad.exit());
        assertTrue(bad.err().contains("SM_E2E_SSH_FINGERPRINT"), bad.err());
        assertFalse(bad.err().contains("MD5:ab:cd"), "the value is not echoed");
    }

    @Test
    void anUnknownArgumentIsRefusedBecauseTheTargetComesFromTheEnvironmentOnly() throws Exception {
        Result r = run("--host", "elsewhere");

        assertEquals(2, r.exit());
        assertTrue(r.err().contains("unknown argument"), r.err());
    }
}
