package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan 012 T5 (live gate L5): {@code backend/launcher/scripts/run-e2e-undeclare.sh}. Two halves.
 *
 * <p>With {@code --check} the script validates, prints the command and the host-side commands, and runs nothing and contacts
 * nothing: it reads the target from the environment only, runs ONLY the live undeclare IT, requires the second Cache (all
 * three), says it WRITES the second Cache's file (restored afterwards), says it stops nothing, requires
 * {@code SM_E2E_CONFIRM_WRITE=yes} exactly and {@code SM_E2E_CONFIRM_CLEANUP=yes} exactly, and refuses any value that could
 * not be a name or a path - because those values are sent to the host inside a command line.
 *
 * <p>Without it, the DECISIONS the script makes - the part a host cannot be asked to rehearse - are run against stub
 * {@code ssh}, {@code ssh-keyscan}, {@code ssh-keygen} and Maven programs on the {@code PATH}: a container whose start time
 * changed is a FAILED gate (exit 1) even if the Maven run passed; a passed run whose cleanup was not confirmed leaves L5 OPEN
 * (exit 3) and deletes nothing; a confirmed cleanup deletes exactly SM's hidden names and is listed afterwards (exit 0); a
 * host that does not offer the pinned key is never connected to.
 */
class RunE2eUndeclareScriptTest {

    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);

    @TempDir
    Path tmp;

    private Path script;
    private Map<String, String> env;

    @BeforeEach
    void aCompleteEnvironment() throws IOException {
        script = Path.of("scripts/run-e2e-undeclare.sh").toAbsolutePath();
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
        builder.environment().put("PATH", pathWithStubs == null ? System.getenv("PATH") : pathWithStubs);
        builder.environment().putAll(env);
        Process process = builder.start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        assertTrue(finished, "the script did not finish");
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------------ --check

    @Test
    void aCompleteTargetPrintsTheCommandWithOnlyTheLiveUndeclareItAndSaysWhatItWritesAndThatItStopsNothing() throws Exception {
        Result r = run("--check");

        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("-Dit.test=UndeclareLiveIT"), r.out());
        assertFalse(r.out().contains("SkeletonWalkthroughIT") || r.out().contains("MultiPlacementLiveIT")
                || r.out().contains("UndeclareRehearsalIT"), "no other walkthrough is run from here: " + r.out());
        assertTrue(r.out().contains("-Dtest=NoSuchUnit"), "no unit test of the modules built with -am runs");
        for (String property : new String[] {"-Dsm.walkthrough.ssh.host=e2e-host.example.test",
                "-Dsm.walkthrough.ssh.container=cache-engine", "-Dsm.walkthrough.config-dir=/srv/cache-engine/config",
                "-Dsm.walkthrough.ssh.fingerprint=" + FINGERPRINT,
                "-Dsm.walkthrough.undeclare.second.container=cache-engine-2",
                "-Dsm.walkthrough.undeclare.second.config-dir=/srv/cache-engine/config/second",
                "-Dsm.walkthrough.undeclare.second.host-port=28081"}) {
            assertTrue(r.out().contains(property), property + " in " + r.out());
        }
        assertTrue(r.out().contains("WRITES"), "it says it writes the second Cache's file: " + r.out());
        assertTrue(r.out().contains("ORIGINAL keyed hash"), "and that it restores it: " + r.out());
        assertTrue(r.out().contains("nothing. No container is stopped"), "and that it stops nothing: " + r.out());
        assertTrue(r.out().contains("docker inspect --format '{{.Id}} {{.State.StartedAt}} running={{.State.Running}}' -- cache-engine-2"),
                "the independent host-side check is shown: " + r.out());
        assertTrue(r.out().contains("cd -- /srv/cache-engine/config/second && find . -maxdepth 1 -type f"),
                "the cleanup is shown: " + r.out());
        assertTrue(r.out().contains("sm-(preimage-[0-9]+(\\.absent)?|fp-[0-9]+)$"),
                "and names exactly SM's two hidden file shapes, digits only (W5 review W5B-7): " + r.out());
        assertTrue(r.out().contains("^\\./\\.application\\.yml\\.sm-("), "anchored on the whole name: " + r.out());
        assertTrue(r.out().contains("L5 is NOT closed"), "an unconfirmed cleanup says L5 stays open: " + r.out());
        assertTrue(r.out().contains("nothing was run"), "--check runs nothing");
    }

    @Test
    void theRunRefusesUnlessWritingTheSecondCachesFileIsConfirmedWithExactlyYes() throws Exception {
        env.remove("SM_E2E_CONFIRM_WRITE");
        Result unset = run("--check");
        assertEquals(2, unset.exit(), "the run overwrites the second Cache's file: it must be confirmed");
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
    void theCleanupNeedsExactlyYesAndSaysSoWhenItWillRun() throws Exception {
        env.put("SM_E2E_CONFIRM_CLEANUP", "yes");
        Result yes = run("--check");
        assertEquals(0, yes.exit(), yes.err());
        assertTrue(yes.out().contains("it WILL be run"), yes.out());
        assertFalse(yes.out().contains("L5 is NOT closed"), yes.out());

        for (String stray : new String[] {"true", "1", "YES"}) {
            env.put("SM_E2E_CONFIRM_CLEANUP", stray);
            Result refused = run("--check");
            assertEquals(2, refused.exit(), "only the word yes: " + stray);
            assertTrue(refused.err().contains("SM_E2E_CONFIRM_CLEANUP"), refused.err());
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
    void aValueThatCouldInjectIntoTheHostsCommandLineIsRefusedNamingTheVariableNotTheValue() throws Exception {
        String[][] attacks = {
            {"SM_E2E_SECOND_CONTAINER", "cache-2; reboot"}, {"SM_E2E_SECOND_CONTAINER", "$(id)"},
            {"SM_E2E_SECOND_CONFIG_DIR", "/srv/x; rm -rf /"}, {"SM_E2E_SECOND_CONFIG_DIR", "/srv/cache-engine/config/../../etc"},
            {"SM_E2E_SECOND_CONFIG_DIR", "/srv/a b"}, {"SM_E2E_FILE_NAME", "a'; touch /tmp/x; '"},
            {"SM_E2E_SSH_HOST", "host; id"}, {"SM_E2E_SSH_USER", "root; id"}, {"SM_E2E_SSH_PORT", "22; id"}};
        for (String[] attack : attacks) {
            Map<String, String> saved = new LinkedHashMap<>(env);
            env.put(attack[0], attack[1]);

            Result r = run("--check");

            assertEquals(2, r.exit(), attack[0] + "=" + attack[1]);
            assertTrue(r.err().contains(attack[0]), attack[0] + " named: " + r.err());
            assertFalse(r.err().contains(attack[1]) || r.out().contains(attack[1]), "never the value: " + r.err() + r.out());
            assertEquals("", r.out(), "no command was assembled");
            env = saved;
        }
    }

    @Test
    void theOptionalWalkthroughVariablesArePassedThroughWhenSetAndTheFileNameShapesTheCleanup() throws Exception {
        env.put("SM_E2E_ACQUISITION", "direct-http");
        env.put("SM_E2E_HEALTH_PORT", "18080");
        env.put("SM_E2E_SSH_PORT", "2222");
        env.put("SM_E2E_FILE_NAME", "cache.yml");

        Result r = run("--check");

        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("-Dsm.walkthrough.acquisition=direct-http"), r.out());
        assertTrue(r.out().contains("-Dsm.walkthrough.health-port=18080"), r.out());
        assertTrue(r.out().contains("-Dsm.walkthrough.ssh.port=2222"), r.out());
        assertTrue(r.out().contains("-Dsm.walkthrough.file-name=cache.yml"), r.out());
        assertTrue(r.out().contains("^\\./\\.cache\\.yml\\.sm-("), "the hidden names follow the config file's name: " + r.out());
    }

    @Test
    void theHelpSaysTheGateIsFourStepsAndWhatEachExitMeans() throws Exception {
        Result help = run("--help");

        assertEquals(0, help.exit(), help.err());
        assertTrue(help.out().contains("MUST be identical to step 1"), "the container check is the gate: " + help.out());
        assertTrue(help.out().contains("L5 is NOT closed until the cleanup has been done"), help.out());
        assertTrue(help.out().contains("OR-110"), "the authority is cited");
        assertTrue(help.out().contains("3 the run passed but the cleanup was not"), "exit 3 is explained: " + help.out());
    }

    // ------------------------------------------------------------------------- the decisions, against stub programs

    private String pathWithStubs;
    private Path hostState;

    /** What SM itself writes beside the live file. */
    private static final List<String> SM_HIDDEN = List.of(".application.yml.sm-preimage-100",
            ".application.yml.sm-preimage-101.absent", ".application.yml.sm-fp-100");
    /** The live file, and names an operator could have made that merely START with SM's prefix: all must survive. */
    private static final List<String> KEPT = List.of("application.yml", ".application.yml.sm-preimage-3.bak",
            ".application.yml.sm-preimage-", ".application.yml.sm-preimage-x", ".application.yml.sm-fp-1.old",
            ".application.yml.sm-preimage-1.absent.bak", ".other.yml.sm-fp-1", ".applicationXyml.sm-fp-1");

    /**
     * Stubs {@code ssh-keyscan}, {@code ssh-keygen}, {@code ssh} and Maven on the PATH. The host is a directory of files:
     * {@code inspect.1} (before), {@code inspect.2} (after), {@code second} (a real config directory holding SM's hidden names,
     * the live file and near misses); {@code ssh} answers by what the one command string asks.
     */
    private void stubHost(String before, String after, int mavenExit, String offeredFingerprint) throws IOException {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        hostState = Files.createDirectories(tmp.resolve("host"));
        Files.writeString(hostState.resolve("inspect.1"), before + "\n");
        Files.writeString(hostState.resolve("inspect.2"), after + "\n");
        // The second Cache's config directory is REAL, so the script's own find/grep/rm run against it (W5 review W5B-7).
        Path second = Files.createDirectories(hostState.resolve("second"));
        for (String name : SM_HIDDEN) {
            Files.writeString(second.resolve(name), "x");
        }
        for (String name : KEPT) {
            Files.writeString(second.resolve(name), "x");
        }
        env.put("SM_E2E_CONFIG_DIR", hostState.toString());
        env.put("SM_E2E_SECOND_CONFIG_DIR", second.toString());
        Files.writeString(hostState.resolve("calls"), "");
        write(bin.resolve("ssh-keyscan"), "#!/bin/bash\necho \"e2e-host.example.test ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA\"\n");
        write(bin.resolve("ssh-keygen"), "#!/bin/bash\ncat >/dev/null\necho \"256 " + offeredFingerprint + " host (ED25519)\"\n");
        write(bin.resolve("mvn"), "#!/bin/bash\necho \"stub maven: $*\" >> \"" + hostState.resolve("calls") + "\"\nexit " + mavenExit + "\n");
        write(bin.resolve("ssh"), """
                #!/bin/bash
                cmd="${@: -1}"
                state="%s"
                echo "$cmd" >> "$state/calls"
                case "$cmd" in
                  "docker inspect"*)
                    n=$(cat "$state/n" 2>/dev/null || echo 0); n=$((n+1)); echo $n > "$state/n"
                    cat "$state/inspect.$n" ;;
                  "ls -la"*|"cd "*) bash -c "$cmd" ;;
                  *) echo "unexpected command: $cmd" >&2; exit 9 ;;
                esac
                """.formatted(hostState));
        pathWithStubs = bin + ":" + System.getenv("PATH");
        env.put("SM_E2E_MVN", bin.resolve("mvn").toString());
        env.put("SM_E2E_EVIDENCE_DIR", tmp.resolve("evidence").toString());
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private List<String> remainingInTheSecondDirectory() throws IOException {
        try (var names = Files.list(hostState.resolve("second"))) {
            return names.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    private String calls() throws IOException {
        return Files.readString(hostState.resolve("calls"));
    }

    private static final String RUNNING_SINCE_NOON = "sha256:abc 2026-10-08T12:00:00.123456789Z running=true";

    @Test
    void aPassedRunWithAnUntouchedContainerAndAConfirmedCleanupDeletesExactlySmsHiddenNamesAndPasses() throws Exception {
        stubHost(RUNNING_SINCE_NOON, RUNNING_SINCE_NOON, 0, FINGERPRINT);
        env.put("SM_E2E_CONFIRM_CLEANUP", "yes");

        Result r = run();

        assertEquals(0, r.exit(), r.out() + r.err());
        assertTrue(r.out().contains("UNTOUCHED"), r.out());
        assertTrue(r.out().contains("L5         : PASSED"), r.out());
        assertEquals(KEPT.stream().sorted().toList(), remainingInTheSecondDirectory(),
                "exactly SM's three hidden files are gone; the live file and every near miss survive");
        String calls = calls();
        assertTrue(calls.contains("rm -f"), calls);
        assertTrue(calls.contains("-maxdepth 1 -type f"), "regular files in that one directory only: " + calls);
        assertTrue(Files.isRegularFile(tmp.resolve("evidence/L5-host-before.txt")));
        assertTrue(Files.isRegularFile(tmp.resolve("evidence/L5-host-after.txt")));
        assertTrue(Files.isRegularFile(tmp.resolve("evidence/L5-host-after-cleanup.txt")));
        assertTrue(Files.readString(tmp.resolve("evidence/L5-host-after-cleanup.txt")).contains("sm-preimage-100"),
                "the removed names are in the evidence");
    }

    @Test
    void aPassedRunWhoseCleanupIsNotConfirmedLeavesL5OpenWithExit3AndDeletesNothing() throws Exception {
        stubHost(RUNNING_SINCE_NOON, RUNNING_SINCE_NOON, 0, FINGERPRINT);

        Result r = run();

        assertEquals(3, r.exit(), r.out() + r.err());
        assertTrue(r.out().contains("L5 is OPEN"), r.out());
        assertFalse(r.out().contains("PASSED"), "a gate with a dirty host does not read as passed: " + r.out());
        assertFalse(calls().contains("rm -f"), "nothing was deleted: " + calls());
        assertEquals(java.util.stream.Stream.concat(SM_HIDDEN.stream(), KEPT.stream()).sorted().toList(),
                remainingInTheSecondDirectory(), "nothing was deleted");
    }

    @Test
    void aContainerWhoseStartTimeChangedIsAFailedGateEvenIfTheMavenRunPassedAndNothingIsDeleted() throws Exception {
        stubHost(RUNNING_SINCE_NOON, "sha256:abc 2026-10-08T12:30:00.000000000Z running=true", 0, FINGERPRINT);
        env.put("SM_E2E_CONFIRM_CLEANUP", "yes");

        Result r = run();

        assertEquals(1, r.exit(), r.out() + r.err());
        assertTrue(r.err().contains("the container CHANGED"), r.err());
        assertFalse(r.out().contains("UNTOUCHED") || r.out().contains("PASSED"), r.out());
        assertFalse(calls().contains("rm -f"), "no cleanup after a failed gate: " + calls());
    }

    @Test
    void aFailedMavenRunIsAFailedGateEvenIfTheContainerIsUntouched() throws Exception {
        stubHost(RUNNING_SINCE_NOON, RUNNING_SINCE_NOON, 1, FINGERPRINT);
        env.put("SM_E2E_CONFIRM_CLEANUP", "yes");

        Result r = run();

        assertEquals(1, r.exit(), r.out() + r.err());
        assertTrue(r.err().contains("the run itself failed"), r.err());
        assertFalse(calls().contains("rm -f"), "no cleanup after a failed run: " + calls());
    }

    @Test
    void aContainerThatIsNotRunningBeforeTheRunIsNeverRunAgainst() throws Exception {
        stubHost("sha256:abc 2026-10-08T12:00:00Z running=false", RUNNING_SINCE_NOON, 0, FINGERPRINT);

        Result r = run();

        assertEquals(2, r.exit(), r.out() + r.err());
        assertFalse(calls().contains("stub maven"), "Maven was not started: " + calls());
    }

    @Test
    void aHostThatDoesNotOfferThePinnedKeyIsNeverConnectedTo() throws Exception {
        stubHost(RUNNING_SINCE_NOON, RUNNING_SINCE_NOON, 0, "SHA256:" + "B".repeat(43));

        Result r = run();

        assertEquals(2, r.exit(), r.out() + r.err());
        assertTrue(r.err().contains("did not offer a key"), r.err());
        assertEquals("", calls(), "no ssh command and no Maven run: " + calls());
    }
}
