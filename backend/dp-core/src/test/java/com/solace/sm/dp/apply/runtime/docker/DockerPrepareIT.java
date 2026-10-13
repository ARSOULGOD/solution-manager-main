package com.solace.sm.dp.apply.runtime.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.WriterAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@code SM-D21}, against a real daemon. */
@Testcontainers(disabledWithoutDocker = true)
class DockerPrepareIT {

    private DockerFixtures fixtures;
    private DockerEngineClient engine;

    @BeforeEach
    void setUp() {
        Path dir = DockerFixtures.newConfigDir();
        fixtures = new DockerFixtures();
        engine = DockerFixtures.client();
    }

    @AfterEach
    void tearDown() {
        Path dir = DockerFixtures.newConfigDir();
        if (engine != null) {
            engine.close();
        }
        if (fixtures != null) {
            fixtures.close();
        }
    }

    private DockerAdapter adapterFor(PlacementRef ref, String namePrefix, Path localDir) {
        DockerPlacement placement = DockerFixtures.placement(namePrefix, localDir);
        return new DockerAdapter(engine, DockerFixtures.catalogFor(ref, placement),
                DockerFixtures.store(), DockerFixtures.props());
    }

    /** Ready, or a failure message that NAMES the refusal — an anonymous type mismatch is useless. */
    private static PreflightResult.Ready ready(PreflightResult result) {
        if (result instanceof PreflightResult.Refused refused) {
            throw new AssertionError("expected Ready but the pre-flight refused: "
                    + refused.reason().token());
        }
        return (PreflightResult.Ready) result;
    }

    private static String uniquePrefix() {
        return "sm-plan005-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * {@code BE-D25}'s observation contract, at the one place {@code replicas} and {@code prepare}
     * are meant to <b>disagree</b>: a placement with no replicas.
     *
     * <p>An apply cannot write to nothing, so {@code prepare} refuses it. An observation has
     * nothing to report, so {@code replicas} returns an empty list — which is also the answer
     * {@code ResumePlanner} gives an {@code ABSENT} replica, and the three must agree or a stop
     * over a placement whose containers are gone is a failure on one path and a success on
     * another.
     *
     * <p>Pinned here because a mutation restoring the throw turned nothing red: every other
     * fixture in this suite has replicas, so the empty case had no test at all.
     */
    @Test
    void aPlacementWithNoReplicasObservesAnEmptyListAndRefusesPreflight() {
        Path dir = DockerFixtures.newConfigDir();
        PlacementRef ref = DockerFixtures.newPlacement();
        DockerAdapter adapter = adapterFor(ref, uniquePrefix(), dir);   // nothing started

        assertEquals(List.of(), adapter.replicas(ref),
                "an observation of a placement that holds nothing is empty, never an exception");

        PreflightResult refused = adapter.prepare(ref, 1);
        assertEquals("docker.replica.absent",
                assertInstanceOf(PreflightResult.Refused.class, refused).reason().token(),
                "and the apply path still refuses it, because an apply cannot write to nothing");
    }

    @Test
    void aReadyPreflightNamesTheReplicaAndTheResolvedLocation() {
        Path dir = DockerFixtures.newConfigDir();
        String prefix = uniquePrefix();
        fixtures.start(prefix + "-1", dir, DockerFixtures.Shape.DECLARED_AND_MOUNTED, false);
        PlacementRef ref = DockerFixtures.newPlacement();

        PreflightResult.Ready ready = ready(adapterFor(ref, prefix, dir).prepare(ref, 1));

        assertEquals(1, ready.replicas().size());
        assertEquals(DockerFixtures.CONFIG_PATH, ready.location().containerPath());
        assertEquals(new WriterAccess.LocalFilesystem(dir), ready.location().writerAccess());
        assertFalse(ready.healthSignalDeclared(),
                "busybox declares no healthcheck and the run spec set none — MEASURED as the "
                        + "ABSENCE of the State.Health key, not as UNHEALTHY");
    }

    @Test
    void aContainerWithAComposeStyleHealthcheckReportsAHealthSignal() {
        Path dir = DockerFixtures.newConfigDir();
        String prefix = uniquePrefix();
        fixtures.start(prefix + "-1", dir, DockerFixtures.Shape.DECLARED_AND_MOUNTED, true);
        PlacementRef ref = DockerFixtures.newPlacement();

        PreflightResult.Ready ready = ready(adapterFor(ref, prefix, dir).prepare(ref, 1));
        assertTrue(ready.healthSignalDeclared(),
                "a healthcheck declared in the RUN SPEC, not the image, is what makes State.Health "
                        + "appear — deployment configuration, so I1 is untouched");
    }

    @Test
    void twoContainersSharingThePrefixAreTwoReplicas() {
        Path dir = DockerFixtures.newConfigDir();
        String prefix = uniquePrefix();
        fixtures.start(prefix + "-1", dir, DockerFixtures.Shape.DECLARED_AND_MOUNTED, false);
        fixtures.start(prefix + "-2", dir, DockerFixtures.Shape.DECLARED_AND_MOUNTED, false);
        PlacementRef ref = DockerFixtures.newPlacement();

        PreflightResult.Ready ready = ready(adapterFor(ref, prefix, dir).prepare(ref, 1));
        assertEquals(2, ready.replicas().size());
    }

    /** SM-D21's round-2 correction: the path lives in the container layer without a mount. */
    @Test
    void aDeclaredPathWithNoMountIsRefused() {
        Path dir = DockerFixtures.newConfigDir();
        String prefix = uniquePrefix();
        fixtures.start(prefix + "-1", dir, DockerFixtures.Shape.DECLARED_NOT_MOUNTED, false);
        PlacementRef ref = DockerFixtures.newPlacement();

        PreflightResult.Refused refused = assertInstanceOf(PreflightResult.Refused.class,
                adapterFor(ref, prefix, dir).prepare(ref, 1));
        assertEquals("docker.config.not.persistent", refused.reason().token());
    }

    @Test
    void aCompetingConfigSourceIsRefused() {
        Path dir = DockerFixtures.newConfigDir();
        String prefix = uniquePrefix();
        fixtures.start(prefix + "-1", dir, DockerFixtures.Shape.COMPETING_ENV, false);
        PlacementRef ref = DockerFixtures.newPlacement();

        PreflightResult.Refused refused = assertInstanceOf(PreflightResult.Refused.class,
                adapterFor(ref, prefix, dir).prepare(ref, 1));
        assertEquals("docker.config.competing.source", refused.reason().token());
    }

    /**
     * The probe round-trip, and the reason it exists: comparing Mounts[].Source to a configured path
     * would compare two strings SM was told. This container mounts a DIFFERENT host directory, so
     * the mount destination still looks right and only the round-trip catches it.
     */
    @Test
    void aMountThatIsNotTheDirectorySmWritesToIsRefused() {
        Path smWritesTo = DockerFixtures.newConfigDir();
        Path containerReads = DockerFixtures.newConfigDir();
        String prefix = uniquePrefix();
        fixtures.start(prefix + "-1", smWritesTo, containerReads,
                DockerFixtures.Shape.MOUNTED_ELSEWHERE, false);
        PlacementRef ref = DockerFixtures.newPlacement();

        PreflightResult.Refused refused = assertInstanceOf(PreflightResult.Refused.class,
                adapterFor(ref, prefix, smWritesTo).prepare(ref, 1));
        assertEquals("docker.config.mount.mismatch", refused.reason().token());
    }

    /**
     * PER-REPLICA checking, not per-placement. A prepare that inspected only replicas.get(0) passes
     * every single-replica test there is; only a violation on the SECOND replica can tell the two
     * loops apart.
     */
    @Test
    void aCompetingSourceOnTheSECONDReplicaIsStillRefused() {
        Path dir = DockerFixtures.newConfigDir();
        String prefix = uniquePrefix();
        fixtures.start(prefix + "-1", dir, DockerFixtures.Shape.DECLARED_AND_MOUNTED, false);
        fixtures.start(prefix + "-2", dir, DockerFixtures.Shape.COMPETING_ENV, false);
        PlacementRef ref = DockerFixtures.newPlacement();

        PreflightResult.Refused refused = assertInstanceOf(PreflightResult.Refused.class,
                adapterFor(ref, prefix, dir).prepare(ref, 1));
        assertEquals("docker.config.competing.source", refused.reason().token());
    }

    @Test
    void noReplicaAtAllIsRefused() {
        Path dir = DockerFixtures.newConfigDir();
        PlacementRef ref = DockerFixtures.newPlacement();
        PreflightResult.Refused refused = assertInstanceOf(PreflightResult.Refused.class,
                adapterFor(ref, uniquePrefix(), dir).prepare(ref, 1));
        assertEquals("docker.replica.absent", refused.reason().token());
    }

    /** prepare must leave no probe behind: a leaked dotfile is harmless but a leak is a leak. */
    @Test
    void theProbeIsCleanedUp() throws IOException {
        Path dir = DockerFixtures.newConfigDir();
        String prefix = uniquePrefix();
        fixtures.start(prefix + "-1", dir, DockerFixtures.Shape.DECLARED_AND_MOUNTED, false);
        PlacementRef ref = DockerFixtures.newPlacement();
        adapterFor(ref, prefix, dir).prepare(ref, 1);

        try (var s = Files.list(dir)) {
            assertEquals(0, s.filter(p -> p.getFileName().toString().startsWith(".sm-preflight-"))
                    .count());
        }
    }
}
