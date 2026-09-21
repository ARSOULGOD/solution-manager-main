package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.ApplyEngineProperties;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.AttemptRow;
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
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.docker.DockerAdapter;
import com.solace.sm.dp.apply.runtime.docker.DockerEngineClient;
import com.solace.sm.dp.apply.runtime.docker.DockerFixtures;
import com.solace.sm.dp.apply.runtime.docker.DockerPlacement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The narrated walkthrough: one <b>real Cache Engine</b> container, one real config change, one
 * real restart, and the evidence read back from the runtime rather than from a log line.
 *
 * <p><b>Not part of the completion gate.</b> It needs {@code solace-cache-engine:local} built from
 * a sibling repository and {@code backend/demo/docker-compose.yml} up, neither of which
 * {@code mvn verify} can guarantee. The mechanisms it exercises are proven against a <b>stand-in
 * container</b> by {@code DockerAdapterContractIT}, {@code DockerPrepareIT},
 * {@code DockerMaterializeAndStatusIT}, {@code DockerLifecycleIT} and
 * {@code ManualApplyDockerWalkthroughIT}, and against the in-memory adapter — no container — by
 * {@code ManualApplyIT} and {@code RollingApplyIT}. What this class adds is the <b>target</b>: the
 * Cache's own command-line additional-location, its own container-layer path and its own image.
 *
 * <p>It also adds one combination that exists nowhere else: {@code HealthGate} driven against a
 * live {@code State.Health}. The signal itself is SM's — the Cache's image declares no
 * {@code HEALTHCHECK}, so {@code backend/demo/docker-compose.yml} declares a TCP probe, which is
 * deployment configuration rather than an image change ({@code I1}).
 *
 * <p>This is not lesson {@code R10} being repeated. {@code R10}'s rule is about a conditional-skip
 * annotation on a suite the plan calls its completion gate — and plan 005 says the opposite, here
 * and in {@code backend/demo/README.md}. The mechanism is proven by the stand-in, unconditionally,
 * on every build. This run proves the target, on demand, and is reported as a walkthrough rather
 * than as a check.
 *
 * <p>Enabled by {@code -Dsm.demo.cache=true}.
 */
@EnabledIfSystemProperty(named = "sm.demo.cache", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CacheApplyWalkthroughIT {

    /** {@code container_name} in {@code backend/demo/docker-compose.yml}, minus its index. */
    private static final String CONTAINER_PREFIX = "sm-plan005-cache";

    /**
     * What SM writes. The whole external file, because that is what SM owns ({@code SM-D3}) — and
     * it must stay STARTABLE: a rendered config that drops the solace block leaves the Cache
     * unable to refresh its context, which is measured, not hypothetical, and would make every
     * restart in this walkthrough a failure for a reason that has nothing to do with SM.
     */
    private static String smOwned(int maxSize) {
        return "cache-engine:\n"
                + "  max-size: " + maxSize + "\n"
                + "  solace:\n"
                + "    enabled: true\n"
                + "    host: tcp://broker:55555\n"
                + "    vpn: default\n"
                + "    username: default\n"
                + "    password: default\n"
                + "    management:\n"
                + "      enabled: false\n";
    }

    private static final RenderedConfig SM_OWNED =
            RenderedConfig.of(smOwned(250000).getBytes(StandardCharsets.UTF_8));
    private static final RenderedConfig SM_OWNED_AGAIN =
            RenderedConfig.of(smOwned(300000).getBytes(StandardCharsets.UTF_8));

    private DockerEngineClient engine;
    private Path configDir;
    private TenantId tenant;
    private ScopeKey scope;
    private PlacementRef placementRef;
    private DockerAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        SmDataPostgres.bootstrap();
        engine = DockerFixtures.client();
        // The same directory compose binds, resolved the same way compose resolves it: relative to
        // the compose file. Surefire/Failsafe run with backend/dp-core as the working directory.
        configDir = Path.of("..", "demo", "config", "cache-1").toRealPath();

        tenant = new TenantId(UUID.randomUUID());
        InstanceId instance = new InstanceId(UUID.randomUUID());
        PlacementId placementId = new PlacementId(UUID.randomUUID());
        scope = new ScopeKey(tenant, instance, placementId);
        placementRef = new PlacementRef(tenant, instance, placementId);

        DockerPlacement placement = DockerFixtures.placement(CONTAINER_PREFIX, configDir);
        adapter = new DockerAdapter(engine, DockerFixtures.catalogFor(placementRef, placement),
                DockerFixtures.store(), DockerFixtures.props());
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
        // Nothing to stop: this class starts no container. The compose project is the operator's,
        // brought up and taken down by the commands in backend/demo/README.md.
    }

    @Test
    @Order(1)
    void aRealCacheContainerIsPreparedStagedRestartedAndObserved() {
        PreflightResult.Ready ready =
                (PreflightResult.Ready) adapter.prepare(placementRef, 1);
        TargetReplica replica = ready.replicas().get(0);
        System.out.println("MEASURED: cache container " + replica.ref().value().substring(0, 12)
                + " configDir=" + configDir);
        System.out.println("MEASURED: prepare -> Ready, replicas=" + ready.replicas().size()
                + " containerPath=" + ready.location().containerPath()
                + " mechanism=" + ready.location().mechanism()
                + " healthSignalDeclared=" + ready.healthSignalDeclared());
        assertTrue(ready.healthSignalDeclared(),
                "the compose healthcheck is what makes State.Health appear, and what a health-"
                        + "gated strategy needs");

        RuntimeStatus before = adapter.status(replica);
        assertTrue(before.startBaseline().isPresent(),
                "the demo Cache must be RUNNING before the walkthrough starts. Bring it up with "
                        + "the commands in backend/demo/README.md; liveness was "
                        + before.liveness());
        System.out.println("MEASURED: before apply  liveness=" + before.liveness()
                + " health=" + before.health() + " hash=" + before.observedHash()
                + " startedAt=" + before.startBaseline().orElseThrow().startedAt());

        // ---- the apply: SM writes the Cache's own external config file, manual strategy ---------
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) ApplyEngineHarness
                .executor(adapter, (p, g) -> SM_OWNED).execute(new ApplyCommand(scope,
                        Operation.APPLY, 1L, Strategy.MANUAL, null, "demo-operator"));
        assertEquals(RequestState.STAGED, landed.state());
        System.out.println("MEASURED: apply " + landed.applyId().value() + " -> " + landed.state());
        for (StepIntentRow intent : ApplyEngineHarness.intents()
                .forRequest(tenant, landed.applyId())) {
            AttemptRow attempt = ApplyEngineHarness.attempts()
                    .latest(tenant, intent.intentId()).orElseThrow();
            System.out.println("MEASURED: journal step=" + intent.step()
                    + " replica=" + shorten(intent.replica().value())
                    + " configHash=" + intent.configHash()
                    + " attempt=" + attempt.outcome()
                    + " observedHash=" + attempt.observedHash());
        }

        RuntimeStatus after = adapter.status(replica);
        assertEquals(SM_OWNED.hash(), after.observedHash(),
                "the hash is read BACK from the Cache's own path, not echoed from what SM wrote");
        var stat = engine.statInContainer(replica.ref().value(),
                DockerFixtures.CONFIG_PATH + "/" + DockerFixtures.FILE_NAME).orElseThrow();
        System.out.println("MEASURED: after apply   hash=" + after.observedHash()
                + " cacheSees=" + stat.name() + " size=" + stat.size());

        // ---- materialization moved nothing about the process -------------------------------------
        assertEquals(before.startBaseline().orElseThrow().startedAt(),
                after.startBaseline().orElseThrow().startedAt(),
                "SM-D15/AG-D3: writing configuration never restarts anything");
        System.out.println("MEASURED: startedAt unchanged by materialize -> "
                + after.startBaseline().orElseThrow().startedAt());
        String imageBefore = after.runtimeRef().fields().get(RuntimeRef.Key.IMAGE_DIGEST);

        // ---- the OPERATOR restarts; SM does not, and only observes -------------------------------
        engine.restart(replica.ref().value(), 30);
        RuntimeStatus restarted = awaitHealthy(replica);
        assertNotEquals(after.startBaseline().orElseThrow().startedAt(),
                restarted.startBaseline().orElseThrow().startedAt());
        assertEquals(SM_OWNED.hash(), restarted.observedHash(),
                "a restart reads config, it does not rewrite it — and the Cache came back on the "
                        + "generation SM staged");
        assertEquals(imageBefore, restarted.runtimeRef().fields().get(RuntimeRef.Key.IMAGE_DIGEST),
                "SM-D2/I1: the artifact is never pulled, replaced or re-tagged");
        assertTrue(ApplyEngineHarness.intents().forRequest(tenant, landed.applyId()).stream()
                        .noneMatch(i -> i.step() == Step.LIFECYCLE),
                "under manual the core never issues a lifecycle step — the operator did");
        System.out.println("MEASURED: after operator restart startedAt="
                + restarted.startBaseline().orElseThrow().startedAt()
                + " health=" + restarted.health() + " hash=" + restarted.observedHash()
                + " image=" + shorten(restarted.runtimeRef().fields().get(RuntimeRef.Key.IMAGE_DIGEST)));
        System.out.println("MEASURED: LIFECYCLE intents issued by SM = 0 (manual strategy)");
    }

    /**
     * The other half of the demo, and the one plan 005's Task 9 added: SM issues the restart and
     * gates on the container's live {@code State.Health} — produced by the compose healthcheck SM
     * declares, since the image ships none — before declaring the generation applied.
     */
    @Test
    @Order(2)
    void aRollingHealthGatedApplyRestartsTheCacheAndGatesOnItsRealHealthSignal() {
        PreflightResult.Ready ready = (PreflightResult.Ready) adapter.prepare(placementRef, 2);
        TargetReplica replica = ready.replicas().get(0);
        RuntimeStatus before = adapter.status(replica);
        System.out.println("MEASURED: gated apply, before  health=" + before.health()
                + " startedAt=" + before.startBaseline().orElseThrow().startedAt());

        // A real JVM's start-up, not the fake adapter's: the compose healthcheck declares a 20s
        // start period, so the gate is given three minutes and polls every two seconds.
        ApplyEngineProperties props = new ApplyEngineProperties(Duration.ofMinutes(5),
                Duration.ofMinutes(3), Duration.ofSeconds(2));

        Instant startedAt = Instant.now();
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) ApplyEngineHarness
                .executor(adapter, (p, g) -> SM_OWNED_AGAIN, props)
                .execute(new ApplyCommand(scope, Operation.APPLY, 2L,
                        Strategy.ROLLING_HEALTH_GATED, null, "demo-operator"));

        assertEquals(RequestState.APPLIED, landed.state(),
                "APPLIED, not STAGED: BE-D16 makes STAGED exclusively the manual strategy's");
        System.out.println("MEASURED: gated apply " + landed.applyId().value() + " -> "
                + landed.state() + " in " + Duration.between(startedAt, Instant.now()).toSeconds()
                + "s");

        StepIntentRow lifecycle = ApplyEngineHarness.intents().forRequest(tenant, landed.applyId())
                .stream().filter(i -> i.step() == Step.LIFECYCLE).findFirst().orElseThrow();
        AttemptRow attempt =
                ApplyEngineHarness.attempts().latest(tenant, lifecycle.intentId()).orElseThrow();
        System.out.println("MEASURED: LIFECYCLE attempt outcome=" + attempt.outcome()
                + " pre_baseline=" + attempt.preBaseline().startedAt()
                + " observedHash=" + attempt.observedHash());
        assertEquals(before.startBaseline().orElseThrow().startedAt(),
                attempt.preBaseline().startedAt(),
                "the LIFECYCLE attempt carries the baseline captured BEFORE the verb — the one "
                        + "restart-observed compares against");

        RuntimeStatus after = adapter.status(replica);
        assertNotEquals(before.startBaseline().orElseThrow().startedAt(),
                after.startBaseline().orElseThrow().startedAt(), "SM restarted it");
        assertEquals(HealthSignal.HEALTHY, after.health());
        assertEquals(SM_OWNED_AGAIN.hash(), after.observedHash());
        System.out.println("MEASURED: gated apply, after   health=" + after.health()
                + " startedAt=" + after.startBaseline().orElseThrow().startedAt()
                + " hash=" + after.observedHash());
    }

    private RuntimeStatus awaitHealthy(TargetReplica replica) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        RuntimeStatus last = adapter.status(replica);
        while (Instant.now().isBefore(deadline) && last.health() != HealthSignal.HEALTHY) {
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            last = adapter.status(replica);
        }
        return last;
    }

    private static String shorten(String s) {
        return s == null ? null : s.length() > 19 ? s.substring(0, 19) + "…" : s;
    }
}
