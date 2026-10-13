package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.apply.engine.AttemptPolicy;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.persistence.agent.AckStartSettler;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RuntimeOperationContext;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.agent.SimulatedAgent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 W3 integration finding 3, {@code AGW-D28} item 2, {@code OR-139}: the container start an agent acknowledges a write
 * with is the attempt's {@code write_baseline} and the placement's start observation, recorded in the acknowledgement's own
 * transaction. The data plane NEVER hears a report here (the observed state is the simulated agent itself, and no report intake
 * runs), so everything asserted below came from the acknowledgement. A write acknowledged with no start has no baseline.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentAckBaselineIT {

    private static AgentEngineWorld w;

    @BeforeAll
    static void world() {
        SmDataPostgres.bootstrap();
        w = new AgentEngineWorld("agent_ack_baseline");
    }

    private record Rig(AgentEngineWorld.Agent agent, ScopeKey scope, SimulatedAgent sim, AgentEngineWorld.Renderer renderer,
            TargetReplica replica, ApplyId apply) {
    }

    private Rig rig() {
        AgentEngineWorld.Agent agent = w.enrol(UUID.randomUUID());
        ScopeKey scope = w.agentScope(agent);
        AgentSessionRef session = w.connect(agent);
        SimulatedAgent sim = new SimulatedAgent(w.agentSide, agent.agentId(), AgentEngineWorld.agentRef(scope), session);
        AgentEngineWorld.Renderer renderer = new AgentEngineWorld.Renderer(w.hasher);
        renderer.byGeneration.put(7L, "generation: 7\n".getBytes(StandardCharsets.UTF_8));
        PlacementRef ref = AgentEngineWorld.ref(scope);
        TargetReplica replica = new TargetReplica(ref, new ReplicaRef(scope.placement().value().toString()));
        ApplyId apply = w.runningApply(scope, new ApplyCommand(scope, com.solace.sm.dp.apply.model.Operation.APPLY, 7L,
                Strategy.MANUAL, null, "operator-1"));
        return new Rig(agent, scope, sim, renderer, replica, apply);
    }

    /** The MATERIALIZE write, as the engine issues it: a real intent and a real OPEN attempt the command row will carry. */
    private RuntimeStatus write(Rig rig, AgentEngineWorld.Path path) {
        RenderedConfig config = RenderedConfig.of(rig.scope().tenant(), rig.renderer().byGeneration.get(7L), w.hasher);
        String key = "ack-baseline:" + UUID.randomUUID();
        StepIntentRow intent = w.stepIntents().ensureIntent(rig.scope().tenant(), rig.apply(), rig.scope().placement(),
                Step.MATERIALIZE, rig.replica().ref(), key, config.hash());
        AttemptRow attempt = new AttemptService(w.tx, w.attempts, new AttemptPolicy(2), w.clock).open(rig.scope().tenant(),
                rig.apply(), intent, null, AgentEngineWorld.engineProperties().stepBudget());
        RuntimeOperationContext ctx = new RuntimeOperationContext(rig.apply(), intent.intentId(), key, attempt.attemptId(),
                Step.MATERIALIZE);
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            path.adapter.materializeConfig(rig.replica(), 7L, config, ctx);
        }
        lastAttempt = attempt.attemptId();
        lastIntent = intent.intentId();
        return path.adapter.status(rig.replica());
    }

    private UUID lastAttempt;
    private UUID lastIntent;

    private String baseline(UUID tenant) {
        return w.runtime.queryForObject("SELECT write_baseline::text FROM sm_data.apply_attempt WHERE tenant_id = :t"
                + " AND attempt_id = :a", new MapSqlParameterSource().addValue("t", tenant).addValue("a", lastAttempt), String.class);
    }

    private List<Instant> observation(Rig rig) {
        return w.runtime.query("SELECT started_at FROM sm_data.placement_start_observation WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", rig.scope().tenant().value()).addValue("p", rig.scope().placement().value()),
                (rs, n) -> rs.getTimestamp(1).toInstant());
    }

    @Test
    void anAckedWriteStartIsTheAttemptsBaselineAndThePlacementsObservationWithoutAnyReport() {
        Rig rig = rig();
        AtomicInteger settled = new AtomicInteger();
        w.ackSettler = (tenant, instance) -> settled.incrementAndGet();
        AgentEngineWorld.Path path;
        try {
            path = w.path(rig.renderer(), rig.sim());
        } finally {
            w.ackSettler = AckStartSettler.NONE;
        }
        Instant started = rig.sim().startedAt();

        RuntimeStatus status = write(rig, path);

        String stored = baseline(rig.scope().tenant().value());
        assertNotNull(stored, "the baseline is on the attempt before the engine closes it");
        assertTrue(stored.contains(started.toString()), "it is the acknowledged start: " + stored + " vs " + started);
        assertEquals(List.of(started.truncatedTo(java.time.temporal.ChronoUnit.MICROS)), observation(rig),
                "and the placement's start observation");
        assertEquals(1, settled.get(), "the observation moved, so the ladder was settled in the ack's transaction");
        assertEquals(started.truncatedTo(java.time.temporal.ChronoUnit.MICROS), status.baseline().startedAt(),
                "status reads the one source");
        assertEquals(started.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                path.adapter.writeBaseline(rig.replica().placement(), lastIntent, status).startedAt(),
                "the policy answers the start the ack recorded, nothing of the status's own");
    }

    @Test
    void aWriteAckedWithNoStartHasNoBaselineAndNothingElseStandsInForIt() {
        Rig rig = rig();
        rig.sim().omitStartOnAcks(true);
        AgentEngineWorld.Path path = w.path(rig.renderer(), rig.sim());

        RuntimeStatus status = write(rig, path);

        assertNull(baseline(rig.scope().tenant().value()), "fail closed: no acknowledged start, no baseline");
        assertEquals(List.of(), observation(rig));
        assertNull(status.baseline(), "and the status offers none, though the simulated agent's own report has a start");
        assertNull(path.adapter.writeBaseline(rig.replica().placement(), lastIntent, status));
    }

    @Test
    void aStartBeyondTheClockBoundIsNotBelievedButTheWriteIsStillAcknowledged() {
        Rig rig = rig();
        rig.sim().startedAtIs(Instant.now().plus(Duration.ofMinutes(30)));
        AgentEngineWorld.Path path = w.path(rig.renderer(), rig.sim());

        write(rig, path);

        assertNull(baseline(rig.scope().tenant().value()), "CLOCK_SUSPECT: no baseline");
        assertEquals(List.of(), observation(rig));
        assertEquals(1, rig.sim().writeCount(), "the write happened and was acknowledged");
    }

    // ---- through the real engine: the close must neither overwrite the ack's baseline nor supply one of its own -----------------

    private String materializeBaseline(Rig rig) {
        return w.runtime.queryForObject("SELECT a.write_baseline::text FROM sm_data.apply_attempt a WHERE a.tenant_id = :t"
                + " AND a.step = 'MATERIALIZE' AND a.outcome = 'SUCCEEDED' ORDER BY a.started_at DESC LIMIT 1",
                new MapSqlParameterSource().addValue("t", rig.scope().tenant().value()), String.class);
    }

    private Rig engineRig() {
        AgentEngineWorld.Agent agent = w.enrol(UUID.randomUUID());
        ScopeKey scope = w.agentScope(agent);
        AgentSessionRef session = w.connect(agent);
        SimulatedAgent sim = new SimulatedAgent(w.agentSide, agent.agentId(), AgentEngineWorld.agentRef(scope), session);
        AgentEngineWorld.Renderer renderer = new AgentEngineWorld.Renderer(w.hasher);
        renderer.byGeneration.put(7L, "generation: 7\n".getBytes(StandardCharsets.UTF_8));
        return new Rig(agent, scope, sim, renderer, null, null);
    }

    private void execute(Rig rig) {
        AgentEngineWorld.Path path = w.path(rig.renderer(), rig.sim());
        ApplyCommand manual = new ApplyCommand(rig.scope(), com.solace.sm.dp.apply.model.Operation.APPLY, 7L, Strategy.MANUAL, null,
                "operator-1");
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            w.executor(path.router(AgentEngineWorld.unreachable("an agent placement reached dp-remote")), rig.renderer())
                    .execute(manual);
        }
    }

    @Test
    void theEnginesCloseKeepsTheAcksBaselineOnTheMaterializeAttempt() {
        Rig rig = engineRig();
        Instant started = rig.sim().startedAt();
        execute(rig);
        String stored = materializeBaseline(rig);
        assertNotNull(stored, "the succeeded MATERIALIZE attempt carries the baseline");
        assertTrue(stored.contains(started.toString()), stored);
    }

    @Test
    void theEngineSuppliesNoBaselineOfItsOwnWhenTheAckCarriedNone() {
        Rig rig = engineRig();
        rig.sim().omitStartOnAcks(true);
        // a report HAS put a start on the placement (the observation is report-fed too): it must not become the write's baseline
        w.runtime.update("INSERT INTO sm_data.placement_start_observation (tenant_id, placement_id, started_at, process_ref,"
                + " observed_at) VALUES (:t, :p, :s, 'c0ffee01', now())", new MapSqlParameterSource()
                .addValue("t", rig.scope().tenant().value()).addValue("p", rig.scope().placement().value())
                .addValue("s", java.time.OffsetDateTime.now().minusDays(1)));
        execute(rig);
        assertNull(materializeBaseline(rig), "a start from a report does not stand in for the acknowledged one");
    }

    // ---- OR-139 clarified: the ack's start belongs to the attempt that CLOSES SUCCEEDED, after a retry or a resume ----------------

    /** The newest MATERIALIZE attempt of the tenant: what the ladder's readers look at. */
    private record Latest(String outcome, String baseline) {
    }

    private Latest latestMaterialize(Rig rig) {
        return w.runtime.query("SELECT a.outcome, a.write_baseline::text AS b FROM sm_data.apply_attempt a WHERE a.tenant_id = :t"
                + " AND a.step = 'MATERIALIZE' ORDER BY a.started_at DESC LIMIT 1",
                new MapSqlParameterSource().addValue("t", rig.scope().tenant().value()),
                (rs, n) -> new Latest(rs.getString("outcome"), rs.getString("b"))).get(0);
    }

    private long failedMaterializes(Rig rig) {
        return w.runtime.queryForObject("SELECT count(*) FROM sm_data.apply_attempt WHERE tenant_id = :t AND step = 'MATERIALIZE'"
                + " AND outcome = 'FAILED'", new MapSqlParameterSource().addValue("t", rig.scope().tenant().value()), Long.class);
    }

    @Test
    void aRetryThatReusesTheAckedCommandGivesTheSucceededAttemptTheAcksBaseline() {
        Rig rig = engineRig();
        Instant started = rig.sim().startedAt();
        // the first attempt's read-back finds nothing (no report yet): it fails after the ack; the retry gets the ACKED command back
        com.solace.sm.dp.api.agw.AgentObservedStatePort blindUntilAnAttemptFailed = ref ->
                failedMaterializes(rig) == 0 ? java.util.Optional.empty() : rig.sim().latest(ref);
        AgentEngineWorld.Path path = w.path(rig.renderer(), blindUntilAnAttemptFailed);
        ApplyCommand manual = new ApplyCommand(rig.scope(), com.solace.sm.dp.apply.model.Operation.APPLY, 7L, Strategy.MANUAL, null,
                "operator-1");
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            w.executor(path.router(AgentEngineWorld.unreachable("dp-remote")), rig.renderer()).execute(manual);
        }
        assertEquals(1, failedMaterializes(rig), "attempt 1 failed its read-back");
        Latest latest = latestMaterialize(rig);
        assertEquals("SUCCEEDED", latest.outcome());
        assertNotNull(latest.baseline(), "the retry's attempt carries the start the first attempt's ack recorded");
        assertTrue(latest.baseline().contains(started.toString()), latest.baseline());
        assertEquals(1, rig.sim().writeCount(), "one write, one ack");
    }

    private static final class SimulatedCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @Test
    void aResumeAfterACrashBetweenTheAckAndTheCloseGivesTheSucceededAttemptTheAcksBaseline() {
        Rig rig = engineRig();
        Instant started = rig.sim().startedAt();
        java.util.function.UnaryOperator<com.solace.sm.dp.api.agw.AgentCommandPort> crashAfterAck = delegate ->
                (com.solace.sm.dp.api.agw.AgentCommandPort) java.lang.reflect.Proxy.newProxyInstance(
                        com.solace.sm.dp.api.agw.AgentCommandPort.class.getClassLoader(),
                        new Class<?>[] {com.solace.sm.dp.api.agw.AgentCommandPort.class}, (proxy, method, args) -> {
                            Object result;
                            try {
                                result = method.invoke(delegate, args);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                            if (method.getName().equals("completion") && result instanceof java.util.Optional<?> o && o.isPresent()
                                    && ((com.solace.sm.dp.api.agw.CommandCompletion) o.get()).operation()
                                            == com.solace.sm.dp.api.agw.AgentOperation.APPLY && rig.sim().writeCount() >= 1) {
                                throw new SimulatedCrash();
                            }
                            return result;
                        });
        AgentEngineWorld.Path before = w.path(rig.renderer(), rig.sim(), crashAfterAck);
        ApplyCommand manual = new ApplyCommand(rig.scope(), com.solace.sm.dp.apply.model.Operation.APPLY, 7L, Strategy.MANUAL, null,
                "operator-1");
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            org.junit.jupiter.api.Assertions.assertThrows(SimulatedCrash.class,
                    () -> w.executor(before.router(AgentEngineWorld.unreachable("dp-remote")), rig.renderer()).execute(manual));
        }
        AgentEngineWorld.Path after = w.path(rig.renderer(), rig.sim());          // SM restarted: nothing in memory
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            w.executor(after.router(AgentEngineWorld.unreachable("dp-remote")), rig.renderer()).resumeRunning(rig.scope().tenant());
        }
        Latest latest = latestMaterialize(rig);
        assertEquals("SUCCEEDED", latest.outcome());
        assertNotNull(latest.baseline(), "the resumed attempt carries the start recorded in the first run's ack");
        assertTrue(latest.baseline().contains(started.toString()), latest.baseline());
        assertEquals(1, rig.sim().writeCount());
    }

    // ---- W4 note (d): the reconciler's re-write (REMATERIALIZE) closes its attempt with the same baseline the forward write does ----------

    private long failedRematerializes(Rig rig) {
        return failedMaterializes(rig);          // a re-drive's re-write is another attempt of the MATERIALIZE intent
    }

    /**
     * A re-drive's first attempt of a REMATERIALIZE carries the ack's start (the command was created under that attempt), but a
     * later re-drive reuses the acked command on a NEW attempt after a read-back miss. That attempt closes SUCCEEDED, and must carry
     * the ack's start like every other succeeded write on the agent path: red against {@code rematerialize} closing with no baseline.
     */
    @Test
    void aRedriveRetryThatReusesTheAckedRematerializeCommandGivesTheSucceededAttemptTheAcksBaseline() {
        Rig rig = engineRig();
        Instant started = rig.sim().startedAt();
        ApplyId apply = w.runningApply(rig.scope(), new ApplyCommand(rig.scope(), com.solace.sm.dp.apply.model.Operation.APPLY, 7L,
                Strategy.MANUAL, null, "operator-1"));
        TargetReplica replica = new TargetReplica(AgentEngineWorld.ref(rig.scope()),
                new ReplicaRef(rig.scope().placement().value().toString()));
        RenderedConfig config = RenderedConfig.of(rig.scope().tenant(), rig.renderer().byGeneration.get(7L), w.hasher);
        com.solace.sm.dp.apply.engine.RedrivePlan plan = new com.solace.sm.dp.apply.engine.RedrivePlan(7L, Strategy.MANUAL,
                config.hash(), List.of(new com.solace.sm.dp.apply.engine.RedrivePlan.Step(replica, true, null, false)));
        // the first attempt's read-back finds nothing (no report yet): it fails after the ack; the next re-drive gets the ACKED command
        com.solace.sm.dp.api.agw.AgentObservedStatePort blindUntilAnAttemptFailed = ref ->
                failedRematerializes(rig) == 0 ? java.util.Optional.empty() : rig.sim().latest(ref);
        AgentEngineWorld.Path path = w.path(rig.renderer(), blindUntilAnAttemptFailed);
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            com.solace.sm.dp.apply.engine.ApplyExecutor executor = w.executor(
                    path.router(AgentEngineWorld.unreachable("dp-remote")), rig.renderer());
            com.solace.sm.dp.apply.engine.RedriveResult first = executor.redrive(rig.scope().tenant(), apply, plan);
            assertEquals(com.solace.sm.dp.apply.model.RequestState.APPLY_INDETERMINATE, first.landed(), "attempt 1 was cut off again");
            assertEquals(1, failedRematerializes(rig), "attempt 1 failed its read-back");
            // the reconciler walks it to RUNNING for the second re-drive (legal in the DDL: BE-D34 step 0 (a))
            w.requests.transition(rig.scope().tenant(), apply, com.solace.sm.dp.apply.model.RequestState.APPLY_INDETERMINATE,
                    com.solace.sm.dp.apply.model.RequestState.RUNNING, null);
            com.solace.sm.dp.apply.engine.RedriveResult second = executor.redrive(rig.scope().tenant(), apply, plan);
            assertEquals(com.solace.sm.dp.apply.model.RequestState.STAGED, second.landed());
        }
        Latest latest = latestMaterialize(rig);
        assertEquals("SUCCEEDED", latest.outcome());
        assertNotNull(latest.baseline(), "the re-drive's retry carries the start the first re-drive attempt's ack recorded");
        assertTrue(latest.baseline().contains(started.toString()), latest.baseline());
        assertEquals(1, rig.sim().writeCount(), "one re-write, one ack");
    }
}
