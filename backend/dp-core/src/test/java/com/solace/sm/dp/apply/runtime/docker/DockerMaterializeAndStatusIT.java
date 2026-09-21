package com.solace.sm.dp.apply.runtime.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DockerMaterializeAndStatusIT {

    private DockerFixtures fixtures;
    private DockerEngineClient engine;
    private Path dir;
    private String prefix;
    private PlacementRef ref;
    private DockerAdapter adapter;
    private GenericContainer<?> container;

    @BeforeEach
    void setUp() {
        fixtures = new DockerFixtures();
        engine = DockerFixtures.client();
        dir = DockerFixtures.newConfigDir();
        prefix = "sm-plan005-" + UUID.randomUUID().toString().substring(0, 8);
        ref = DockerFixtures.newPlacement();
        adapter = new DockerAdapter(engine,
                DockerFixtures.catalogFor(ref, DockerFixtures.placement(prefix, dir)),
                DockerFixtures.store(), DockerFixtures.props());
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
        if (fixtures != null) {
            fixtures.close();
        }
    }

    private TargetReplica onlyReplica(boolean withHealthcheck) {
        container = fixtures.start(prefix + "-1", dir,
                DockerFixtures.Shape.DECLARED_AND_MOUNTED, withHealthcheck);
        PreflightResult result = adapter.prepare(ref, 1);
        if (result instanceof PreflightResult.Refused refused) {
            throw new AssertionError("pre-flight refused: " + refused.reason().token());
        }
        return ((PreflightResult.Ready) result).replicas().get(0);
    }

    private static RenderedConfig config(String yaml) {
        return RenderedConfig.of(yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void materializeWritesThroughTheBindAndTheContainerSeesIt() {
        TargetReplica r = onlyReplica(false);
        RenderedConfig c = config("cache-engine:\n  max-size: 250000\n");

        assertEquals(c.hash(), adapter.materializeConfig(r, 4, c).writtenHash());

        // The same round-trip pre-flight uses, now on the real file: the CONTAINER's own view.
        var stat = engine.statInContainer(r.ref().value(),
                DockerFixtures.CONFIG_PATH + "/" + DockerFixtures.FILE_NAME).orElseThrow();
        assertEquals(DockerFixtures.FILE_NAME, stat.name());
        assertEquals(c.bytes().length, stat.size());
    }

    /** SM-D15 / AG-D3, measured on a real container: a config write restarts nothing. */
    @Test
    void materializeHasZeroLifecycleEffectOnARealContainer() {
        TargetReplica r = onlyReplica(false);
        PreBaseline before = adapter.status(r).startBaseline().orElseThrow();

        adapter.materializeConfig(r, 4, config("a: 1\n"));

        assertEquals(before, adapter.status(r).startBaseline().orElseThrow(),
                "State.StartedAt must be byte-identical across a config write");
    }

    @Test
    void statusReadsBackTheHashLivenessBaselineAndImageIdentity() {
        TargetReplica r = onlyReplica(false);
        RenderedConfig c = config("observed: true\n");
        adapter.materializeConfig(r, 4, c);

        RuntimeStatus status = adapter.status(r);
        assertEquals(c.hash(), status.observedHash());
        assertEquals(Liveness.RUNNING, status.liveness());
        assertNotNull(status.startBaseline().orElseThrow().startedAt());
        assertEquals(r.ref().value(),
                status.runtimeRef().fields().get(RuntimeRef.Key.CONTAINER_ID));
        assertTrue(status.runtimeRef().fields().get(RuntimeRef.Key.IMAGE_DIGEST)
                .startsWith("sha256:"));
        assertEquals(DockerFixtures.CONFIG_PATH,
                status.runtimeRef().fields().get(RuntimeRef.Key.MOUNT_PATH),
                "the CONTAINER-side path, never the host one (I6)");
    }

    /** MEASURED: State has no Health key at all without a declared healthcheck. */
    @Test
    void aRuntimeWithNoHealthcheckReportsNotDeclaredRatherThanHealthy() {
        TargetReplica r = onlyReplica(false);
        assertEquals(HealthSignal.NOT_DECLARED, adapter.status(r).health());
    }

    @Test
    void aRunSpecHealthcheckIsReportedAsARealSignal() {
        TargetReplica r = onlyReplica(true);
        assertTrue(adapter.status(r).health() == HealthSignal.HEALTHY
                        || adapter.status(r).health() == HealthSignal.STARTING,
                "a declared healthcheck yields a real signal, not NOT_DECLARED");
    }

    /** MEASURED: a stopped container's State.Health.Status goes STALE, not absent. */
    @Test
    void aStoppedReplicaIsNeverReportedHealthy() {
        TargetReplica r = onlyReplica(true);
        container.getDockerClient().stopContainerCmd(container.getContainerId()).exec();

        RuntimeStatus status = adapter.status(r);
        assertEquals(Liveness.STOPPED, status.liveness());
        assertEquals(HealthSignal.UNHEALTHY, status.health());
        assertTrue(status.startBaseline().isEmpty(), "a stopped replica offers no live baseline");
    }

    @Test
    void nothingAtThePathIsAnEmptyHashRatherThanAnException() {
        TargetReplica r = onlyReplica(false);
        assertNull(adapter.status(r).observedHash());
    }

    @Test
    void statusOfAnAbsentContainerIsAbsentWithNoBaseline() {
        TargetReplica r = onlyReplica(false);
        container.getDockerClient().removeContainerCmd(container.getContainerId())
                .withForce(true).exec();

        RuntimeStatus status = adapter.status(r);
        assertEquals(Liveness.ABSENT, status.liveness());
        assertTrue(status.startBaseline().isEmpty(),
                "§2.2 rule 3's indeterminate trigger, reported rather than thrown");
    }
}
