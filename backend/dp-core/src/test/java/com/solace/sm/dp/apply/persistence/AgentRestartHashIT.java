package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentObservation;
import com.solace.sm.dp.api.agw.AgentObservedStatePort;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.CommandCompletion;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.agent.SimulatedAgent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 W3 integration finding 1: after an SM restart no report brings the plain digest back (the agent's periodic report
 * never carries {@code effective_hash}, and an op-finished one is ReportAck'd once), so a resumed apply could not converge
 * ({@code materialize.readback.unavailable}) and a post-restart verify read a false divergence.
 *
 * <p>The restart is simulated as the real thing leaves it: the database and the agent survive; the data plane's memory (hand-off,
 * bridge, observed state) is rebuilt, and the only observation the new process ever gets is a periodic report, that is one with the
 * agent's persisted generation and NO digest. The fix answers {@code status} with the keyed hash the acknowledged command row already
 * holds; nothing plain is stored.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentRestartHashIT {

    private static final class SimulatedCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static AgentEngineWorld w;

    @BeforeAll
    static void world() {
        SmDataPostgres.bootstrap();
        w = new AgentEngineWorld("agent_restart_hash");
    }

    private static RuntimeAdapter noSsh() {
        return AgentEngineWorld.unreachable("an agent placement reached the dp-remote adapter");
    }

    /** What a fresh data plane sees: the agent's report with its persisted generation, and no plain digest. */
    private static AgentObservedStatePort hashless(SimulatedAgent sim) {
        return ref -> sim.latest(ref).map(o -> new AgentObservation(o.live(), o.health(), null, o.persistedGeneration(),
                o.startedAt(), o.observedAt()));
    }

    /** Throws the simulated crash once {@code operation}'s command is acknowledged and the agent has run it. */
    private static UnaryOperator<AgentCommandPort> crashAfterAck(AgentOperation operation, BooleanSupplier ran) {
        return delegate -> (AgentCommandPort) Proxy.newProxyInstance(AgentCommandPort.class.getClassLoader(),
                new Class<?>[] {AgentCommandPort.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (method.getName().equals("completion") && result instanceof Optional<?> o && o.isPresent()
                            && ((CommandCompletion) o.get()).operation() == operation && ran.getAsBoolean()) {
                        throw new SimulatedCrash();
                    }
                    return result;
                });
    }

    private record Rig(AgentEngineWorld.Agent agent, ScopeKey scope, SimulatedAgent sim, AgentEngineWorld.Renderer renderer) {
    }

    private Rig rig() {
        AgentEngineWorld.Agent agent = w.enrol(UUID.randomUUID());
        ScopeKey scope = w.agentScope(agent);
        AgentSessionRef session = w.connect(agent);
        SimulatedAgent sim = new SimulatedAgent(w.agentSide, agent.agentId(), AgentEngineWorld.agentRef(scope), session);
        AgentEngineWorld.Renderer renderer = new AgentEngineWorld.Renderer(w.hasher);
        renderer.byGeneration.put(7L, "generation: 7\nserver:\n  port: 8081\n".getBytes(StandardCharsets.UTF_8));
        return new Rig(agent, scope, sim, renderer);
    }

    private ApplyId theOnlyOpenApply(Rig rig) {
        return w.requests.nonTerminal(rig.scope().tenant()).get(0).applyId();
    }

    @Test
    void aResumeAtMaterializeAfterARestartConvergesWithoutAReadBackFailure() {
        Rig rig = rig();
        AgentEngineWorld.Path before = w.path(rig.renderer(), rig.sim(),
                crashAfterAck(AgentOperation.APPLY, () -> rig.sim().writeCount() >= 1));
        ApplyCommand manual = new ApplyCommand(rig.scope(), com.solace.sm.dp.apply.model.Operation.APPLY, 7L, Strategy.MANUAL, null,
                "operator-1");
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            assertThrows(SimulatedCrash.class, () -> w.executor(before.router(noSsh()), rig.renderer()).execute(manual));
        }
        ApplyId id = theOnlyOpenApply(rig);
        assertEquals(1, rig.sim().writeCount());

        AgentEngineWorld.Path after = w.path(rig.renderer(), hashless(rig.sim()));
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            w.executor(after.router(noSsh()), rig.renderer()).resumeRunning(rig.scope().tenant());
        }

        assertEquals(RequestState.STAGED, w.stateOf(rig.scope().tenant(), id), "the resumed apply settled");
        assertFalse(w.attemptErrors(rig.scope().tenant(), id).contains("materialize.readback.unavailable"),
                "no read-back failure: " + w.attemptErrors(rig.scope().tenant(), id));
        assertEquals(1, rig.sim().writeCount(), "the file was written once, however many times SM asked");
        String baseline = w.runtime.queryForObject("SELECT a.write_baseline::text FROM sm_data.apply_attempt a WHERE a.tenant_id = :t"
                + " AND a.step = 'MATERIALIZE' AND a.outcome = 'SUCCEEDED' ORDER BY a.started_at DESC LIMIT 1",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t", rig.scope().tenant().value()), String.class);
        assertNotNull(baseline, "and the resumed attempt that succeeded carries the baseline the first run's ack recorded");
        assertTrue(baseline.contains(rig.sim().startedAt().toString()), baseline);
    }

    @Test
    void aVerifyAfterARestartReadsTheAppliedHashNotAFalseDivergence() {
        Rig rig = rig();
        AgentEngineWorld.Path before = w.path(rig.renderer(), rig.sim(),
                crashAfterAck(AgentOperation.RESTART, () -> rig.sim().restartCount() >= 1));
        ApplyCommand restartAll = new ApplyCommand(rig.scope(), com.solace.sm.dp.apply.model.Operation.APPLY, 7L,
                Strategy.RESTART_ALL, "operator-1", "operator-1");
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            assertThrows(SimulatedCrash.class, () -> w.executor(before.router(noSsh()), rig.renderer()).execute(restartAll));
        }
        ApplyId id = theOnlyOpenApply(rig);

        AgentEngineWorld.Path after = w.path(rig.renderer(), hashless(rig.sim()));
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            w.executor(after.router(noSsh()), rig.renderer()).resumeRunning(rig.scope().tenant());
        }

        assertEquals(RequestState.APPLIED, w.stateOf(rig.scope().tenant(), id),
                "verify found the applied hash: " + w.attemptErrors(rig.scope().tenant(), id));
        assertEquals(1, rig.sim().restartCount());
    }

    @Test
    void statusWithNoDigestHeldAnswersTheKeyedHashOfTheNewestAppliedGenerationAtOrBelowThePersistedOne() {
        Rig rig = rig();
        AgentEngineWorld.Path before = w.path(rig.renderer(), rig.sim());
        ApplyCommand manual = new ApplyCommand(rig.scope(), com.solace.sm.dp.apply.model.Operation.APPLY, 7L, Strategy.MANUAL, null,
                "operator-1");
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            w.executor(before.router(noSsh()), rig.renderer()).execute(manual);
        }
        String intended = w.hasher.hash(rig.scope().tenant(), rig.renderer().byGeneration.get(7L));
        PlacementRef ref = AgentEngineWorld.ref(rig.scope());
        TargetReplica replica = new TargetReplica(ref, new ReplicaRef(rig.scope().placement().value().toString()));
        rig.sim().report();

        AgentEngineWorld.Path after = w.path(rig.renderer(), hashless(rig.sim()));
        RuntimeStatus status = after.adapter.status(replica);
        assertNotNull(status.observedHash(), "the digest is gone, the applied command's keyed hash answers");
        assertEquals(intended, status.observedHash(), "keyed against keyed: the intent's hash, not a plain digest");
    }

    // ---- W3 integration re-review, minor 3: never name an older hash as definite when a later write's outcome is not known ---------

    private String[] twoAppliesAndTheirKeyedHashes(Rig rig) {
        rig.renderer().byGeneration.put(8L, "generation: 8\nserver:\n  port: 8082\n".getBytes(StandardCharsets.UTF_8));
        AgentEngineWorld.Path path = w.path(rig.renderer(), rig.sim());
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            for (long g : new long[] {7L, 8L}) {
                w.executor(path.router(noSsh()), rig.renderer()).execute(new ApplyCommand(rig.scope(),
                        com.solace.sm.dp.apply.model.Operation.APPLY, g, Strategy.MANUAL, null, "operator-1"));
            }
        }
        return new String[] {w.hasher.hash(rig.scope().tenant(), rig.renderer().byGeneration.get(7L)),
            w.hasher.hash(rig.scope().tenant(), rig.renderer().byGeneration.get(8L))};
    }

    private Optional<String> fallback(Rig rig) {
        var p = w.catalog.resolve(AgentEngineWorld.ref(rig.scope())).orElseThrow();
        return w.catalog.appliedConfigHash(p, 1_000);
    }

    private void newestCommandBecomes(Rig rig, String set) {
        int n = w.runtime.update("UPDATE sm_data.agent_command SET " + set + " WHERE tenant_id = :t AND placement_id = :p AND command_id"
                + " = (SELECT command_id FROM sm_data.agent_command WHERE tenant_id = :t AND placement_id = :p AND operation = 'APPLY'"
                + " ORDER BY wire_generation DESC LIMIT 1)", new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("t", rig.scope().tenant().value()).addValue("p", rig.scope().placement().value()));
        assertEquals(1, n);
    }

    @Test
    void aLateAppliedNewestWriteIsTheFileNotTheOlderGeneration() {
        Rig rig = rig();
        String[] h = twoAppliesAndTheirKeyedHashes(rig);
        assertEquals(Optional.of(h[1]), fallback(rig), "premise: the newest acked applied write");
        newestCommandBecomes(rig, "state = 'SUPERSEDED', outcome = NULL, detail_code = 'late.applied'");
        assertEquals(Optional.of(h[1]), fallback(rig), "a late APPLIED ack still means that file is on disk");
    }

    @Test
    void anIndeterminateNewestWriteMakesTheFallbackUnknownNotTheOlderHash() {
        Rig rig = rig();
        String[] h = twoAppliesAndTheirKeyedHashes(rig);
        newestCommandBecomes(rig, "outcome = 'APPLY_INDETERMINATE'");
        assertEquals(Optional.empty(), fallback(rig), "the file may be either: unknown, never " + "the older hash as definite");
        newestCommandBecomes(rig, "state = 'SUPERSEDED', outcome = NULL, detail_code = 'late.apply_indeterminate'");
        assertEquals(Optional.empty(), fallback(rig), "a late indeterminate ack is the same");
        assertNotNull(h[0]);
    }
}
