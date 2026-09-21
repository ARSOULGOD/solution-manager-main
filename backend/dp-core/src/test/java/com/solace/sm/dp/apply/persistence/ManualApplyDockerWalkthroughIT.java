package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.docker.DockerAdapter;
import com.solace.sm.dp.apply.runtime.docker.DockerEngineClient;
import com.solace.sm.dp.apply.runtime.docker.DockerFixtures;
import com.solace.sm.dp.apply.runtime.docker.DockerPlacement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The supported backend flow, end to end, against a real container and the real {@code sm_data}
 * journal: pre-flight → materialize through a mounted external config directory → read the hash and
 * status back from the runtime → terminate {@code STAGED} under the {@code manual} strategy.
 *
 * <p>Then the claim that makes {@code manual} honest: <b>materialization does not move the start
 * baseline, and an operator-issued restart does.</b> SM never issues the restart here — under
 * {@code manual} the core never calls {@code startOrUpdate} — so the restart is issued outside the
 * engine, exactly as an operator would, and SM only observes it.
 *
 * <p>Every observation prints with a {@code MEASURED:} prefix so the run's own output is the report.
 */
@Testcontainers(disabledWithoutDocker = true)
class ManualApplyDockerWalkthroughIT {

    private DockerFixtures fixtures;
    private DockerEngineClient engine;
    private Path configDir;
    private String prefix;
    private PlacementRef placementRef;
    private ScopeKey scope;
    private TenantId tenant;
    private DockerAdapter adapter;
    private ApplyExecutor executor;

    private static final RenderedConfig GENERATION_7 = RenderedConfig.of(
            ("cache-engine:\n  max-size: 250000\n  solace:\n    enabled: false\n")
                    .getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        fixtures = new DockerFixtures();
        engine = DockerFixtures.client();
        configDir = DockerFixtures.newConfigDir();
        // This run's own identity, so nothing here can be confused with another session's work.
        prefix = "sm-plan005-demo-" + UUID.randomUUID().toString().substring(0, 8);

        tenant = new TenantId(UUID.randomUUID());
        PlacementId placementId = new PlacementId(UUID.randomUUID());
        InstanceId instance = new InstanceId(UUID.randomUUID());
        scope = new ScopeKey(tenant, instance, placementId);
        placementRef = new PlacementRef(tenant, instance, placementId);

        DockerPlacement placement = DockerFixtures.placement(prefix, configDir);
        adapter = new DockerAdapter(engine, DockerFixtures.catalogFor(placementRef, placement),
                DockerFixtures.store(), DockerFixtures.props());
        executor = ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_7);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
        if (fixtures != null) {
            fixtures.close();   // stops only what THIS test started
        }
    }

    @Test
    void aManualApplyStagesRealConfigAndOnlyAnOperatorRestartMovesTheBaseline() {
        var container = fixtures.start(prefix + "-1", configDir,
                DockerFixtures.Shape.DECLARED_AND_MOUNTED, true);
        System.out.println("MEASURED: container " + container.getContainerId().substring(0, 12)
                + " image=" + container.getDockerImageName() + " configDir=" + configDir);

        // ---- 1. pre-flight resolves the declared location and proves the mount round-trips ------
        PreflightResult result = adapter.prepare(placementRef, 7);
        PreflightResult.Ready ready = (PreflightResult.Ready) result;
        TargetReplica replica = ready.replicas().get(0);
        System.out.println("MEASURED: prepare -> Ready, replicas=" + ready.replicas().size()
                + " containerPath=" + ready.location().containerPath()
                + " healthSignalDeclared=" + ready.healthSignalDeclared());

        RuntimeStatus beforeApply = adapter.status(replica);
        System.out.println("MEASURED: before apply  liveness=" + beforeApply.liveness()
                + " hash=" + beforeApply.observedHash()
                + " startedAt=" + beforeApply.startBaseline().orElseThrow().startedAt());

        // ---- 2. the apply: materialize through the mounted directory, terminate STAGED ----------
        ApplyOutcome outcome = executor.execute(new ApplyCommand(scope, Operation.APPLY, 7L,
                Strategy.MANUAL, null, "demo-operator"));
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) outcome;
        assertEquals(RequestState.STAGED,
                ApplyEngineHarness.requests().find(tenant, landed.applyId()).orElseThrow().state());
        System.out.println("MEASURED: apply " + landed.applyId().value() + " -> " + landed.state());

        // The journal, not a log line: one PREFLIGHT at @placement, one MATERIALIZE per replica.
        for (StepIntentRow intent : ApplyEngineHarness.intents().forRequest(tenant, landed.applyId())) {
            System.out.println("MEASURED: journal step=" + intent.step()
                    + " replica=" + intent.replica().value() + " configHash=" + intent.configHash());
        }

        // ---- 3. the runtime's own read-back, and the container's own view of the file -----------
        RuntimeStatus afterApply = adapter.status(replica);
        assertEquals(GENERATION_7.hash(), afterApply.observedHash());
        var stat = engine.statInContainer(replica.ref().value(),
                DockerFixtures.CONFIG_PATH + "/" + DockerFixtures.FILE_NAME).orElseThrow();
        System.out.println("MEASURED: after apply   hash=" + afterApply.observedHash()
                + " containerSees=" + stat.name() + " size=" + stat.size()
                + " health=" + afterApply.health());

        // ---- 4. materialization moved NOTHING about the process ---------------------------------
        assertEquals(beforeApply.startBaseline().orElseThrow().startedAt(),
                afterApply.startBaseline().orElseThrow().startedAt(),
                "SM-D15/AG-D3: writing configuration never restarts anything");
        System.out.println("MEASURED: startedAt unchanged by materialize -> "
                + afterApply.startBaseline().orElseThrow().startedAt());

        String imageBefore = afterApply.runtimeRef().fields().get(RuntimeRef.Key.IMAGE_DIGEST);

        // ---- 5. the OPERATOR restarts. SM does not: manual means SM observes -------------------
        engine.restart(replica.ref().value(), 10);
        RuntimeStatus afterRestart = adapter.status(replica);

        assertNotEquals(afterApply.startBaseline().orElseThrow().startedAt(),
                afterRestart.startBaseline().orElseThrow().startedAt(),
                "restart-observed is a baseline comparison, and this is the comparison");
        assertEquals(GENERATION_7.hash(), afterRestart.observedHash(),
                "a restart reads config, it does not rewrite it");
        assertEquals(imageBefore, afterRestart.runtimeRef().fields().get(RuntimeRef.Key.IMAGE_DIGEST),
                "SM-D2/I1: the artifact is never pulled, replaced or re-tagged");
        assertTrue(ApplyEngineHarness.intents().forRequest(tenant, landed.applyId()).stream()
                        .noneMatch(i -> i.step() == Step.LIFECYCLE),
                "under manual the core never issues a lifecycle step -- the operator did");

        System.out.println("MEASURED: after restart startedAt="
                + afterRestart.startBaseline().orElseThrow().startedAt()
                + " hash=" + afterRestart.observedHash()
                + " image=" + afterRestart.runtimeRef().fields().get(RuntimeRef.Key.IMAGE_DIGEST));
        System.out.println("MEASURED: LIFECYCLE intents issued by SM = 0 (manual strategy)");
    }
}
