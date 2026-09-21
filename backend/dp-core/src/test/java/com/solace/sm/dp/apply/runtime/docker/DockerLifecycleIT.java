package com.solace.sm.dp.apply.runtime.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DockerLifecycleIT {

    private DockerFixtures fixtures;
    private DockerEngineClient engine;
    private Path dir;
    private String prefix;
    private PlacementRef ref;
    private DockerAdapter adapter;

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

    private List<TargetReplica> replicas(int count) {
        for (int i = 1; i <= count; i++) {
            fixtures.start(prefix + "-" + i, dir, DockerFixtures.Shape.DECLARED_AND_MOUNTED, false);
        }
        PreflightResult result = adapter.prepare(ref, 1);
        if (result instanceof PreflightResult.Refused refused) {
            throw new AssertionError("pre-flight refused: " + refused.reason().token());
        }
        return ((PreflightResult.Ready) result).replicas();
    }

    private static RenderedConfig config(String yaml) {
        return RenderedConfig.of(yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void startOrUpdateRestartsOneReplicaAndMovesItsBaseline() {
        TargetReplica r = replicas(1).get(0);
        var before = adapter.status(r).startBaseline().orElseThrow();

        assertTrue(adapter.startOrUpdate(r, 4).issued());

        assertNotEquals(before.startedAt(), adapter.status(r).startBaseline().orElseThrow().startedAt());
    }

    /**
     * SM-D2 and I1, executable. "Never pulls, replaces or re-tags an artifact" is a claim about
     * identity, and the container's own Image field is the identity.
     */
    @Test
    void startOrUpdateNeverChangesTheImageIdentity() {
        TargetReplica r = replicas(1).get(0);
        String before = adapter.status(r).runtimeRef().fields().get(RuntimeRef.Key.IMAGE_DIGEST);

        adapter.startOrUpdate(r, 4);

        assertEquals(before, adapter.status(r).runtimeRef().fields().get(RuntimeRef.Key.IMAGE_DIGEST));
    }

    @Test
    void startOrUpdateActsOnOneReplicaOnly() {
        List<TargetReplica> rs = replicas(2);
        var siblingBefore = adapter.status(rs.get(1)).startBaseline().orElseThrow();

        adapter.startOrUpdate(rs.get(0), 4);

        assertEquals(siblingBefore, adapter.status(rs.get(1)).startBaseline().orElseThrow(),
                "rolling strategies are per-replica by construction");
    }

    @Test
    void stopReports204AsActedAnd304AsAlreadyThere() {
        TargetReplica r = replicas(1).get(0);
        assertTrue(adapter.stop(r).issued(), "204: the daemon acted");
        assertEquals(Liveness.STOPPED, adapter.status(r).liveness());
        assertFalse(adapter.stop(r).issued(), "304: already stopped, success without acting");
    }

    // ---- BE-D22 --------------------------------------------------------------------------------

    @Test
    void assessRollbackNamesTheTargetHashAndLeavesTheFileAlone() {
        TargetReplica r = replicas(1).get(0);
        RenderedConfig four = config("generation: 4\n");
        RenderedConfig five = config("generation: 5\n");
        adapter.materializeConfig(r, 4, four);
        adapter.materializeConfig(r, 5, five);

        RollbackAssessment.Restorable assessment = assertInstanceOf(RollbackAssessment.Restorable.class,
                adapter.assessRollback(ref, 5, five.hash()));
        assertEquals(four.hash(), assessment.targetHash());
        assertEquals(five.hash(), adapter.status(r).observedHash(),
                "the assessment restored nothing");
    }

    @Test
    void assessRollbackOfAGenerationNeverMaterializedIsNothingToRestore() {
        replicas(1);
        assertInstanceOf(RollbackAssessment.NothingToRestore.class,
                adapter.assessRollback(ref, 99, config("x: 1\n").hash()));
    }

    /** hash_on_config_steps cannot express "restore to absence", so the engine must be told. */
    @Test
    void assessRollbackOfAFirstApplyIsUnknowableBecauseAbsenceHasNoHash() {
        TargetReplica r = replicas(1).get(0);
        RenderedConfig first = config("first: true\n");
        adapter.materializeConfig(r, 1, first);

        RollbackAssessment.Unknowable unknowable = assertInstanceOf(RollbackAssessment.Unknowable.class,
                adapter.assessRollback(ref, 1, first.hash()));
        assertEquals("rollback.target.absent", unknowable.why().token());
    }

    /** BE-D22 correction A: a value this apply never wrote is never overwritten. */
    @Test
    void aValueThisApplyNeverWroteIsUnknowableAndTheFileSurvives() throws IOException {
        TargetReplica r = replicas(1).get(0);
        adapter.materializeConfig(r, 4, config("generation: 4\n"));
        adapter.materializeConfig(r, 5, config("generation: 5\n"));

        // A human edits the live file while the DP is between steps.
        Files.writeString(dir.resolve(DockerFixtures.FILE_NAME), "hand: edited\n");
        String humanHash = adapter.status(r).observedHash();

        RollbackAssessment.Unknowable unknowable = assertInstanceOf(RollbackAssessment.Unknowable.class,
                adapter.assessRollback(ref, 5, config("generation: 5\n").hash()));
        assertEquals("rollback.state.unknown", unknowable.why().token());
        assertEquals(humanHash, adapter.status(r).observedHash(),
                "B/D12: never silently auto-revert a human");
    }

    @Test
    void restorePutsThePreImageBackAndIsIdempotent() {
        TargetReplica r = replicas(1).get(0);
        RenderedConfig four = config("generation: 4\n");
        adapter.materializeConfig(r, 4, four);
        adapter.materializeConfig(r, 5, config("generation: 5\n"));
        var baselineBefore = adapter.status(r).startBaseline().orElseThrow();

        adapter.restore(ref, 5, four.hash());
        assertEquals(four.hash(), adapter.status(r).observedHash());

        adapter.restore(ref, 5, four.hash());
        assertEquals(four.hash(), adapter.status(r).observedHash());
        assertEquals(baselineBefore, adapter.status(r).startBaseline().orElseThrow(),
                "a restore writes config; SM-D15's config-write-is-not-a-restart holds here too");
    }

    /** BE-D22 correction B: the pre-image moved, so the restore writes nothing. */
    @Test
    void aPreImageAlteredBetweenAssessAndRestoreIsRefusedAndNothingIsWritten() throws IOException {
        TargetReplica r = replicas(1).get(0);
        RenderedConfig four = config("generation: 4\n");
        RenderedConfig five = config("generation: 5\n");
        adapter.materializeConfig(r, 4, four);
        adapter.materializeConfig(r, 5, five);
        assertInstanceOf(RollbackAssessment.Restorable.class,
                adapter.assessRollback(ref, 5, five.hash()));

        // Somebody edits the retained pre-image between assess and restore.
        Files.writeString(dir.resolve("." + DockerFixtures.FILE_NAME + ".sm-pre-5"), "tampered\n");

        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                () -> adapter.restore(ref, 5, four.hash()));
        assertEquals("rollback.preimage.altered", e.errorClass().token());
        assertEquals(five.hash(), adapter.status(r).observedHash(),
                "the live file is untouched: a restore that cannot prove its target writes nothing");
    }
}
