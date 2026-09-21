package com.solace.sm.dp.apply.runtime.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The {@code I6} <b>content</b> obligation plan 004 left open by name: the evidence types are SHAPE
 * controls, and a bare identifier-shaped token is measured accepted by them, so the content control
 * is each adapter's.
 *
 * <p>Six sentinels are planted in every input this adapter reads, proven PRESENT in what the daemon
 * hands it, and then proven ABSENT from every value it produces. The presence half is what stops the
 * absence half passing for free.
 */
@Testcontainers(disabledWithoutDocker = true)
class DockerEvidenceSecrecyIT {

    private static final String NAME = "SENTINELNAME";
    private static final String LABEL = "SENTINELLABEL";
    private static final String ENV = "SENTINELENV";
    private static final String MOUNT = "SENTINELMOUNT";
    private static final String CONTENT = "SENTINELCONTENT";
    private static final String ABSENT = "SENTINELABSENT";
    /**
     * SECRET-DENSE inputs. None of these may appear in ANY value the adapter produces, nor in any
     * log line it causes. These are the ones {@code I6} is about.
     */
    private static final List<String> SECRET_DENSE = List.of(LABEL, ENV, MOUNT, CONTENT);

    /**
     * IDENTIFIERS. A container name or id is the request path, so it necessarily reaches the HTTP
     * client's own DEBUG log — see {@link #aContainerIdentifierReachesTheHttpClientsDebugLogAndThatIsRecorded}.
     * They must still stay out of every EVIDENCE value except the ones that exist to hold them.
     */
    private static final List<String> IDENTIFIERS = List.of(NAME, ABSENT);

    private DockerEngineClient engine;
    private GenericContainer<?> container;
    private Path dir;
    private PlacementRef ref;
    private DockerAdapter adapter;
    private TargetReplica replica;

    @BeforeEach
    void setUp() throws IOException {
        engine = DockerFixtures.client();
        // The mount path's LAST SEGMENT carries a sentinel: RuntimeRef.MOUNT_PATH is the one key
        // whose value is a filesystem path drawn from the deployment, so it is the likeliest leak.
        dir = DockerFixtures.newConfigDir().resolve(MOUNT);
        Files.createDirectories(dir);

        String prefix = "sm-plan005-" + NAME + "-" + UUID.randomUUID().toString().substring(0, 8);
        container = new GenericContainer<>(DockerImageName.parse("busybox:1.36"))
                .withCreateContainerCmdModifier(cmd -> cmd.withName(prefix + "-1"))
                .withLabel("com.solace.sm.note", LABEL)
                .withEnv("SOLACE_PASSWORD", ENV)
                .withCommand("sh", "-c", "while true; do sleep 3600; done",
                        "--spring.config.additional-location=" + DockerFixtures.CONFIG_PATH + "/")
                .withFileSystemBind(dir.toAbsolutePath().toString(), DockerFixtures.CONFIG_PATH,
                        BindMode.READ_WRITE);
        container.start();

        ref = DockerFixtures.newPlacement();
        adapter = new DockerAdapter(engine,
                DockerFixtures.catalogFor(ref, DockerFixtures.placement(prefix, dir)),
                DockerFixtures.store(), DockerFixtures.props());

        PreflightResult result = adapter.prepare(ref, 1);
        if (result instanceof PreflightResult.Refused refused) {
            throw new AssertionError("pre-flight refused: " + refused.reason().token());
        }
        replica = ((PreflightResult.Ready) result).replicas().get(0);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    /**
     * The scan below is only worth its runtime if the sentinels were REACHABLE. A capture that saw
     * nothing would pass for free — the vacuous-negative shape plan 004's own review caught twice.
     */
    @Test
    void everySentinelIsActuallyPresentInWhatTheDaemonHandsTheAdapter() throws IOException {
        String raw = engine.inspect(replica.ref().value()).toString();
        assertTrue(raw.contains(NAME), "container name sentinel missing from inspect");
        assertTrue(raw.contains(LABEL), "label sentinel missing from inspect");
        assertTrue(raw.contains(ENV), "environment sentinel missing from inspect");
        assertTrue(raw.contains(MOUNT), "host mount-path sentinel missing from inspect");

        Files.writeString(dir.resolve(DockerFixtures.FILE_NAME), "secret: " + CONTENT + "\n");
        assertTrue(Files.readString(dir.resolve(DockerFixtures.FILE_NAME)).contains(CONTENT));
    }

    @Test
    void noSentinelReachesAnyEvidenceValueOrAnyLogLine() {
        var captured = RootLogCapture.during(() -> {
            List<String> evidence = new ArrayList<>();

            adapter.materializeConfig(replica, 4,
                    RenderedConfig.of(("secret: " + CONTENT + "\n").getBytes(StandardCharsets.UTF_8)));
            RuntimeStatus status = adapter.status(replica);
            evidence.addAll(status.runtimeRef().fields().values());
            evidence.add(status.startBaseline().map(PreBaseline::processRef).orElse(""));
            evidence.add(String.valueOf(status.observedHash()));

            adapter.startOrUpdate(replica, 4);
            adapter.stop(replica);

            // …and one deliberate refusal, so the daemon's own error text has a chance to escape.
            RuntimeAdapterException refusal = assertThrows(RuntimeAdapterException.class,
                    () -> engine.inspect("sm-" + ABSENT + "-no-such-container"));
            evidence.add(refusal.errorClass().token());
            evidence.add(refusal.getMessage());
            evidence.add(String.valueOf(refusal.getCause()));
            return evidence;
        });

        List<String> checked = new ArrayList<>(captured.value());
        checked.add(captured.text());
        for (String sentinel : SECRET_DENSE) {
            for (String value : checked) {
                assertFalse(value.contains(sentinel),
                        "a value the adapter produced carries " + sentinel + ": " + value);
            }
        }
    }

    /**
     * MEASURED, and recorded rather than papered over: {@code docker-java-transport-zerodep} shades
     * an Apache HttpClient that logs the request URI at DEBUG, and the URI is where a container
     * name or id lives. So an identifier DOES reach the log whenever debug logging is on for that
     * shaded logger.
     *
     * <p>This is the {@code JdbcUrls} residual in a second place, and it is bounded the same way:
     * {@code I6} is about secret VALUES, and a container identifier is the same class of thing
     * {@code apply_attempt.runtime_ref} deliberately stores. What must not leak — the environment,
     * the config bytes, a label, the host mount path — does not, which the test above proves.
     *
     * <p><b>The residual an operator must know:</b> if container NAMES are made to carry tenant or
     * customer identifiers, those names are visible to anyone who can enable DEBUG on
     * {@code com.github.dockerjava.zerodep.shaded}. Naming is a deployment choice, so this is a
     * runbook note, not something the adapter can fix: it cannot issue the request without the name.
     */
    @Test
    void aContainerIdentifierReachesTheHttpClientsDebugLogAndThatIsRecorded() {
        var captured = RootLogCapture.during(() -> {
            assertThrows(RuntimeAdapterException.class,
                    () -> engine.inspect("sm-" + ABSENT + "-no-such-container"));
            return "";
        });
        assertTrue(captured.text().contains(ABSENT),
                "if this ever stops being true the residual is closed and this test should be "
                        + "replaced by a strict absence assertion — do not simply delete it");

        // …and the identifier still never reaches an EVIDENCE value.
        RuntimeStatus status = adapter.status(replica);
        for (String value : status.runtimeRef().fields().values()) {
            assertFalse(value.contains(ABSENT));
        }
    }

    /**
     * {@code imageDigest}: CONFIRMED, not dropped. Plan 004 asked the question and this is the
     * answer — it is the witness {@code startOrUpdateNeverChangesTheImageIdentity} rests on, and
     * without it the {@code I1} rule would be tested live and never recorded in the journal.
     */
    @Test
    void theImageIdentityIsCarriedAndIsNotSecretDense() {
        String image = adapter.status(replica).runtimeRef().fields()
                .get(RuntimeRef.Key.IMAGE_DIGEST);
        assertNotNull(image);
        assertTrue(image.startsWith("sha256:"));
        assertTrue(image.length() <= 128, "ValueGrammar.VALUE's bound; measured at 71 characters");
        assertFalse(image.contains("/"),
                "a local image ID carries no registry host, so it cannot carry registry credentials");
    }

    /** The container-side mount path is evidence; the HOST path — which carries the sentinel — is not. */
    @Test
    void theRuntimeRefCarriesTheContainerSidePathNotTheHostOne() {
        String mountPath = adapter.status(replica).runtimeRef().fields()
                .get(RuntimeRef.Key.MOUNT_PATH);
        assertEquals(DockerFixtures.CONFIG_PATH, mountPath);
        assertFalse(mountPath.contains(MOUNT));
    }
}
