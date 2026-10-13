package com.solace.sm.dp.apply.runtime.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.agent.AgentCommandPayloadSource;
import com.solace.sm.dp.api.agw.AgentCommandIntent;
import com.solace.sm.dp.api.agw.AgentHealthObservation;
import com.solace.sm.dp.api.agw.AgentObservation;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentOutcome;
import com.solace.sm.dp.api.agw.CommandId;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.OperationCorrelation;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.RuntimeOperationContext;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the agent adapter owes beyond the runtime-neutral kit (plan 015 T3.1): command identity on the intent key, render once, the
 * keyed-hash bridge, fail fast, the contracted refusals, and a coded failure for every way an agent can answer. Each case names the
 * regression it exists to catch.
 */
class AgentRuntimeAdapterTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static RuntimeOperationContext ctx(ApplyId apply, Step step, ReplicaRef replica) {
        String key = ApplyExecutor.idempotencyKey(apply, step, replica);
        return new RuntimeOperationContext(apply, UUID.randomUUID(), key, UUID.randomUUID(), step);
    }

    private static RenderedConfig config(AgentRuntimeAdapterContractTest.World w, String yaml) {
        return RenderedConfig.of(w.placement.tenant(), bytes(yaml), w.hasher);
    }

    private static String token(Runnable call) {
        return assertThrows(RuntimeAdapterException.class, call::run).errorClass().token();
    }

    // ---- no identity without the journal ----------------------------------------------------------------------------------

    @Test
    void everyContextlessOperationRefusesBecauseTheAgentPathHasNoIdentityWithoutTheJournal() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        TargetReplica r = w.replica();
        RenderedConfig c = config(w, "a: 1\n");
        assertEquals("agent.operation.context.missing", token(() -> w.adapter.prepare(w.placement, 1)));
        assertEquals("agent.operation.context.missing", token(() -> w.adapter.materializeConfig(r, 1, c)));
        assertEquals("agent.operation.context.missing", token(() -> w.adapter.rematerializeIfCurrent(r, 1, c, null)));
        assertEquals("agent.operation.context.missing", token(() -> w.adapter.startOrUpdate(r, 1)));
        assertEquals("agent.operation.context.missing", token(() -> w.adapter.stop(r)));
        assertEquals(0, w.ports.enqueueCalls(), "nothing was queued under an invented identity");
    }

    // ---- command identity (P15-3) -----------------------------------------------------------------------------------------

    /** The mutation this guards: the attempt id in the command key. A resumed apply would then restart the host twice. */
    @Test
    void aNewAttemptOfTheSameIntentIsTheSameCommandAndTheHostRestartsOnce() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        ApplyId apply = ApplyId.next();
        TargetReplica r = w.replica();
        String key = ApplyExecutor.idempotencyKey(apply, Step.LIFECYCLE, r.ref());
        UUID intent = UUID.randomUUID();

        w.adapter.startOrUpdate(r, 7, new RuntimeOperationContext(apply, intent, key, UUID.randomUUID(), Step.LIFECYCLE));
        w.adapter.startOrUpdate(r, 7, new RuntimeOperationContext(apply, intent, key, UUID.randomUUID(), Step.LIFECYCLE));

        assertEquals(1, w.agent.restartCount(), "the engine's retry or a resume must not restart the host again");
        assertEquals(1, w.ports.rows().size(), "one command row for the intent key");
    }

    @Test
    void aDifferentIntentKeyIsAnotherCommandAndSupersedesTheQueuedOneOfTheOld() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        ApplyId apply = ApplyId.next();
        TargetReplica r = w.replica();
        // a command of an earlier step, queued and never delivered (the agent is slow or away)
        w.ports.afterEnqueue(row -> { });
        w.ports.enqueue(new AgentCommandIntent(w.agentId, w.ref, "stale:key", AgentOperation.RESTART, null,
                Instant.now().plusSeconds(300), new OperationCorrelation(apply.value().toString(), UUID.randomUUID().toString(),
                        "stale:key", UUID.randomUUID().toString(), "LIFECYCLE"), null));
        w.ports.afterEnqueue(row -> w.agent.step());

        w.adapter.stop(r, ctx(apply, Step.STOP, r.ref()));

        assertEquals("SUPERSEDED", w.ports.rows().get(0).state, "the resumed apply supersedes what the old step left queued");
        assertEquals(0, w.agent.restartCount(), "a superseded command is never executed");
        assertEquals(1, w.agent.stopCount());
    }

    // ---- render once (P15-4) ------------------------------------------------------------------------------------------------

    @Test
    void oneMaterializeSendsTheEnginesOwnBytesAndRendersNothingItself() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        TargetReplica r = w.replica();
        RenderedConfig c = config(w, "server:\n  port: 8081\n");

        w.adapter.materializeConfig(r, 3, c, ctx(ApplyId.next(), Step.MATERIALIZE, r.ref()));

        assertEquals(0, w.renderer.calls, "the hand-off path never touches the renderer");
        assertEquals("server:\n  port: 8081\n", new String(w.agent.file(), StandardCharsets.UTF_8));
        assertEquals(0, w.handoff.size(), "the entry is released when the wait ends");
    }

    /** After an SM restart the hand-off is empty: a re-render is sent only if its KEYED hash equals the recorded one. */
    @Test
    void aRedeliveryAfterARestartIsReRenderedAndSentOnlyIfItsKeyedHashMatches() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        w.ports.afterEnqueue(row -> { });
        ApplyId apply = ApplyId.next();
        byte[] intended = bytes("intended: yes\n");
        w.generations.applyGeneration = OptionalLong.of(9);
        w.renderer.source = (p, g) -> intended;
        String keyed = w.hasher.hash(w.placement.tenant(), intended);
        String key = ApplyExecutor.idempotencyKey(apply, Step.MATERIALIZE, w.replica().ref());
        w.ports.enqueue(new AgentCommandIntent(w.agentId, w.ref, key, AgentOperation.APPLY, keyed,
                Instant.now().plusSeconds(300), new OperationCorrelation(apply.value().toString(), UUID.randomUUID().toString(), key,
                        UUID.randomUUID().toString(), "MATERIALIZE"), null));

        w.agent.step();

        assertEquals(1, w.renderer.calls, "a miss re-renders once");
        assertEquals("intended: yes\n", new String(w.agent.file(), StandardCharsets.UTF_8));
        assertEquals(1, w.agent.writeCount());
    }

    @Test
    void aReRenderWithAnotherKeyedHashIsRefusedAndTheCommandEndsRefused() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        w.ports.afterEnqueue(row -> { });
        ApplyId apply = ApplyId.next();
        String keyedAtTheTime = w.hasher.hash(w.placement.tenant(), bytes("what the journal promised\n"));
        w.generations.applyGeneration = OptionalLong.of(9);
        w.renderer.source = (p, g) -> bytes("what the generation renders to NOW\n");
        String key = ApplyExecutor.idempotencyKey(apply, Step.MATERIALIZE, w.replica().ref());
        var ref = w.ports.enqueue(new AgentCommandIntent(w.agentId, w.ref, key, AgentOperation.APPLY, keyedAtTheTime,
                Instant.now().plusSeconds(300), new OperationCorrelation(apply.value().toString(), UUID.randomUUID().toString(), key,
                        UUID.randomUUID().toString(), "MATERIALIZE"), null));

        w.agent.step();

        assertNull(w.agent.file(), "bytes the journal did not promise are never written");
        var row = w.ports.row(ref.commandId()).orElseThrow();
        assertEquals("REFUSED", row.state);
        assertEquals("agent.payload.render.changed", row.detailCode);
    }

    @Test
    void aRematerializeCannotBeRebuiltAfterAMissAndIsRefusedTheSameWay() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        var command = new AgentCommandPayloadSource.Command(new CommandId(UUID.randomUUID()), w.agentId, w.ref,
                AgentOperation.REMATERIALIZE, "k:MATERIALIZE:r", "hmacsha256v1:" + "a".repeat(64), 1);
        GatewayPortException e = assertThrows(GatewayPortException.class, () -> w.source.payloadFor(command));
        assertEquals(GatewayCode.AGENT_PAYLOAD_RENDER_CHANGED, e.code());
    }

    @Test
    void aNoticeCarriesNoDigestOnceTheHandoffIsGoneAndTheEmptyDigestForAnOperationWithoutBytes() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        var apply = new AgentCommandPayloadSource.Command(new CommandId(UUID.randomUUID()), w.agentId, w.ref, AgentOperation.APPLY,
                "k:MATERIALIZE:r", "hmacsha256v1:" + "a".repeat(64), 1);
        var restart = new AgentCommandPayloadSource.Command(new CommandId(UUID.randomUUID()), w.agentId, w.ref,
                AgentOperation.RESTART, "k:LIFECYCLE:r", null, 2);
        assertEquals(Optional.empty(), w.source.plainDigestFor(apply), "after a restart the agent compares at pull");
        assertEquals(Optional.of(AgentHashBridge.plainSha256(new byte[0])), w.source.plainDigestFor(restart));
        assertEquals(0, w.source.payloadFor(restart).bytes().length);
    }

    // ---- the keyed-hash bridge (OR-32) --------------------------------------------------------------------------------------

    /** Twenty polls after a restart render the candidate generations once between them, not once per poll. */
    @Test
    void twentyStatusPollsRenderAtMostTheCandidatesOnce() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        TargetReplica r = w.replica();
        RenderedConfig onHost = config(w, "on: host\n");
        w.adapter.materializeConfig(r, 3, onHost, ctx(ApplyId.next(), Step.MATERIALIZE, r.ref()));
        // SM restarts: the bridge forgets every pairing
        AgentHashBridge restarted = new AgentHashBridge(w.renderer, w.hasher, w.generations);
        AgentRuntimeAdapter after = new AgentRuntimeAdapter(
                p -> Optional.of(new AgentPlacementCatalog.AgentPlacement(w.agentId, w.ref, "cache-solution",
                        AgentRuntimeAdapterContractTest.LOCATION, "actuator-health")),
                w.ports, w.ports, w.agent, w.hasher, w.handoff, restarted, Clock.systemUTC(),
                AgentRuntimeAdapter.Settings.defaults(Duration.ZERO));
        w.generations.candidates = List.of(4L, 3L);
        w.renderer.source = (p, g) -> g == 3 ? bytes("on: host\n") : bytes("generation: " + g + "\n");

        RuntimeStatus last = null;
        for (int i = 0; i < 20; i++) {
            last = after.status(r);
        }

        assertEquals(2, w.renderer.calls, "intended and last-applied once each, never once per poll");
        assertEquals(onHost.hash(), last.observedHash(), "the reported plain digest resolves to the keyed hash of generation 3");
    }

    /** The case the pairing map cannot absorb: a file that matches NO candidate stays a miss on every poll. */
    @Test
    void aFileMatchingNoCandidateDoesNotRenderOnEveryPoll() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        w.generations.candidates = List.of(4L, 3L);
        w.renderer.source = (p, g) -> bytes("generation: " + g + "\n");
        w.agent.foreignWrite(bytes("somebody: else\n"));

        for (int i = 0; i < 20; i++) {
            assertTrue(w.adapter.status(w.replica()).hash().isPresent());
        }

        assertEquals(2, w.renderer.calls, "each candidate rendered once for the process, however many polls ask");
    }

    @Test
    void aFileMatchingNoKnownGenerationIsForeignContentNotAnAbsentFile() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        TargetReplica r = w.replica();
        assertTrue(w.adapter.status(r).hash().isEmpty(), "no file at all reads as no hash");

        // the host now holds bytes SM did not send
        w.agent.foreignWrite(bytes("somebody: else\n"));
        RuntimeStatus foreign = w.adapter.status(r);

        assertTrue(foreign.hash().isPresent(), "foreign content is distinguishable from an absent file");
        assertNotEquals(config(w, "somebody: else\n").hash(), foreign.observedHash(),
                "…but it equals no generation's keyed hash, so nothing treats it as ours");
    }

    // ---- fail fast (P15-6) --------------------------------------------------------------------------------------------------

    @Test
    void withoutALiveSessionTheStepFailsAtOnceAndNothingIsQueued() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        w.ports.live(false);
        TargetReplica r = w.replica();
        long started = System.nanoTime();

        String code = token(() -> w.adapter.startOrUpdate(r, 1, ctx(ApplyId.next(), Step.LIFECYCLE, r.ref())));

        assertEquals("agent.session.absent", code);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0, "no wait");
        assertEquals(0, w.ports.enqueueCalls(), "an offline agent's queue does not grow: that is the starvation guard");
    }

    @Test
    void aSessionThatEndsWhileWaitingEndsTheWaitAtOnce() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        TargetReplica r = w.replica();
        w.ports.afterEnqueue(row -> w.ports.live(false));      // the session closes right after the command is queued

        String code = token(() -> w.adapter.startOrUpdate(r, 1, ctx(ApplyId.next(), Step.LIFECYCLE, r.ref())));

        assertEquals("agent.session.lost", code);
        assertEquals("QUEUED", w.ports.rows().get(0).state, "the command stays for the engine's retry; nothing superseded it");
    }

    /** OR-146 (1): which adapter exceptions say "the verb was already dispatched" (the engine lands APPLY_INDETERMINATE for them). */
    @Test
    void onlyAFailureAfterTheCommandWasEnqueuedIsMarkedAfterDispatch() {
        // no session at all: nothing was sent, so a known outcome
        AgentRuntimeAdapterContractTest.World none = new AgentRuntimeAdapterContractTest.World();
        none.ports.live(false);
        TargetReplica rn = none.replica();
        assertFalse(assertThrows(RuntimeAdapterException.class,
                () -> none.adapter.startOrUpdate(rn, 1, ctx(ApplyId.next(), Step.LIFECYCLE, rn.ref()))).afterDispatch(),
                "agent.session.absent: nothing was dispatched");

        // the session ends while waiting: the command is out
        AgentRuntimeAdapterContractTest.World lost = new AgentRuntimeAdapterContractTest.World();
        TargetReplica rl = lost.replica();
        lost.ports.afterEnqueue(row -> lost.ports.live(false));
        RuntimeAdapterException sessionLost = assertThrows(RuntimeAdapterException.class,
                () -> lost.adapter.startOrUpdate(rl, 1, ctx(ApplyId.next(), Step.LIFECYCLE, rl.ref())));
        assertEquals("agent.session.lost", sessionLost.errorClass().token());
        assertTrue(sessionLost.afterDispatch(), "agent.session.lost: the command was enqueued and may have been delivered");

        // the ceiling passes with a live session and no answer: the command is out
        AgentRuntimeAdapterContractTest.World slow = new AgentRuntimeAdapterContractTest.World(Duration.ofMillis(1),
                Duration.ofMillis(40));
        slow.ports.afterEnqueue(row -> { });
        TargetReplica rs = slow.replica();
        RuntimeAdapterException timeout = assertThrows(RuntimeAdapterException.class,
                () -> slow.adapter.startOrUpdate(rs, 1, ctx(ApplyId.next(), Step.LIFECYCLE, rs.ref())));
        assertEquals("agent.command.timeout", timeout.errorClass().token());
        assertTrue(timeout.afterDispatch(), "agent.command.timeout: the command was enqueued");
    }

    @Test
    void aCommandThatOutlivesItsCeilingWhileTheSessionIsLiveTimesOutAndStaysForTheRetry() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World(Duration.ofMillis(1),
                Duration.ofMillis(40));
        w.ports.afterEnqueue(row -> { });                      // a live session whose agent never answers
        TargetReplica r = w.replica();
        RuntimeOperationContext c = ctx(ApplyId.next(), Step.LIFECYCLE, r.ref());

        assertEquals("agent.command.timeout", token(() -> w.adapter.startOrUpdate(r, 1, c)));
        assertEquals(1, w.ports.rows().size());

        w.ports.afterEnqueue(row -> w.agent.step());
        w.agent.step();                                        // the agent comes back and answers the SAME command
        w.adapter.startOrUpdate(r, 1, ctx(c.applyId(), Step.LIFECYCLE, r.ref()));
        assertEquals(1, w.ports.rows().size(), "the retry found the command; it did not mint a second");
        assertEquals(1, w.agent.restartCount());
    }

    /** AGW-D26 item 2: the bytes live until the command ends or its deadline, so a pull AFTER a timed-out wait does not re-render. */
    @Test
    void aPullAfterATimedOutWaitIsServedFromTheHandoffAndRendersNothing() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World(Duration.ofMillis(1),
                Duration.ofMillis(40));
        w.ports.afterEnqueue(row -> { });                      // the agent is slow
        TargetReplica r = w.replica();

        assertEquals("agent.command.timeout", token(() -> w.adapter.materializeConfig(r, 1, config(w, "late: pull\n"),
                ctx(ApplyId.next(), Step.MATERIALIZE, r.ref()))));
        assertEquals(1, w.handoff.size(), "the command is still live, so its bytes are still parked");

        w.agent.step();                                        // the agent finally pulls

        assertEquals("late: pull\n", new String(w.agent.file(), StandardCharsets.UTF_8));
        assertEquals(0, w.renderer.calls, "…and nothing was rendered for it");
    }

    @Test
    void theHandoffEntryEndsWithItsCommandNotWithTheWait() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        TargetReplica r = w.replica();
        w.adapter.materializeConfig(r, 1, config(w, "a: 1\n"), ctx(ApplyId.next(), Step.MATERIALIZE, r.ref()));
        assertEquals(0, w.handoff.size(), "acknowledged: released");
    }

    /** A candidate that failed to render once (a secret-store blip) is tried again after the back-off, not forgotten for good. */
    @Test
    void aCandidateThatFailedToRenderIsRetriedAfterTheBackoffButNotOnEveryPoll() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        AgentHashBridge bridge = new AgentHashBridge(w.renderer, w.hasher, w.generations, clock);
        w.generations.candidates = List.of(3L);
        byte[] onHost = bytes("on: host\n");
        String plain = AgentHashBridge.plainSha256(onHost);
        w.renderer.source = (p, g) -> { throw new IllegalStateException("secret store blip"); };

        for (int i = 0; i < 5; i++) {
            assertTrue(bridge.keyedFor(w.placement, plain).isEmpty());
        }
        assertEquals(1, w.renderer.calls, "a failure is not retried on every poll");

        w.renderer.source = (p, g) -> onHost;
        now.set(Instant.EPOCH.plus(AgentHashBridge.RETRY_AFTER).plusSeconds(1));

        assertTrue(bridge.keyedFor(w.placement, plain).isPresent(), "after the back-off the real file is recognised again");
        assertEquals(2, w.renderer.calls);
    }

    // ---- every way an agent can answer is a coded failure ---------------------------------------------------------------------

    private static void answerWith(AgentRuntimeAdapterContractTest.World w, AgentOutcome outcome, String detail) {
        w.ports.afterEnqueue(row -> {
            row.state = outcome == AgentOutcome.REFUSED ? "REFUSED" : "ACKED";
            row.outcome = outcome;
            row.detailCode = detail;
        });
    }

    @Test
    void aRestartThatDidNotHappenIsAFailureNeverAQuietNotIssued() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        answerWith(w, AgentOutcome.FAILED_NO_EFFECT, null);
        TargetReplica r = w.replica();
        assertEquals("agent.restart.failed.no.effect",
                token(() -> w.adapter.startOrUpdate(r, 1, ctx(ApplyId.next(), Step.LIFECYCLE, r.ref()))));
    }

    @Test
    void aDataPlaneAuthorizationRefusalCarriesItsCode() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        answerWith(w, AgentOutcome.REFUSED, "CAPABILITY_MISSING");
        TargetReplica r = w.replica();
        assertEquals("agent.command.refused.capability.missing",
                token(() -> w.adapter.startOrUpdate(r, 1, ctx(ApplyId.next(), Step.LIFECYCLE, r.ref()))));
    }

    @Test
    void aLostPayloadSurfacesAsExactlyTheRenderChangedCode() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        answerWith(w, AgentOutcome.REFUSED, "agent.payload.render.changed");
        TargetReplica r = w.replica();
        assertEquals("agent.payload.render.changed", token(() -> w.adapter.materializeConfig(r, 1, config(w, "a: 1\n"),
                ctx(ApplyId.next(), Step.MATERIALIZE, r.ref()))));
    }

    @Test
    void aSupersededCommandIsAFailureOfTheStep() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        w.ports.afterEnqueue(row -> row.state = "SUPERSEDED");
        TargetReplica r = w.replica();
        assertEquals("agent.stop.superseded", token(() -> w.adapter.stop(r, ctx(ApplyId.next(), Step.STOP, r.ref()))));
    }

    @Test
    void aPreflightTheAgentRefusesIsARefusalResultNotAnException() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        answerWith(w, AgentOutcome.PREFLIGHT_REFUSED, "CONFIG_ROOT_UNWRITABLE");
        PreflightResult result = w.adapter.prepare(w.placement, 1, ctx(ApplyId.next(), Step.PREFLIGHT, ReplicaRef.PLACEMENT));
        PreflightResult.Refused refused = assertInstanceOf(PreflightResult.Refused.class, result);
        assertEquals("agent.preflight.refused.config.root.unwritable", refused.reason().token());
    }

    /** The ack carries no digest, so the proof is the report: one that names another file fails the step. */
    @Test
    void aReportThatNamesAnotherFileThanTheOneWrittenFailsTheMaterialize() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        TargetReplica r = w.replica();
        AgentRuntimeAdapter lying = new AgentRuntimeAdapter(
                p -> Optional.of(new AgentPlacementCatalog.AgentPlacement(w.agentId, w.ref, "cache-solution",
                        AgentRuntimeAdapterContractTest.LOCATION, null)),
                w.ports, w.ports,
                ref -> Optional.of(new AgentObservation(true, null, "sha256:" + "0".repeat(64), 99L, Instant.EPOCH, Instant.EPOCH)),
                w.hasher, w.handoff, w.bridge, Clock.systemUTC(), AgentRuntimeAdapter.Settings.defaults(Duration.ofMillis(50)));

        assertEquals("agent.apply.hash.mismatch", assertThrows(RuntimeAdapterException.class, () -> lying.materializeConfig(r, 1,
                config(w, "a: 1\n"), ctx(ApplyId.next(), Step.MATERIALIZE, r.ref()))).errorClass().token());
    }

    // ---- observation ----------------------------------------------------------------------------------------------------------

    @Test
    void aPlacementNobodyHasReportedOnIsAbsent() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        AgentRuntimeAdapter silent = new AgentRuntimeAdapter(
                p -> Optional.of(new AgentPlacementCatalog.AgentPlacement(w.agentId, w.ref, "cache-solution",
                        AgentRuntimeAdapterContractTest.LOCATION, "actuator-health")),
                w.ports, w.ports, ref -> Optional.empty(), w.hasher, w.handoff, w.bridge, Clock.systemUTC(),
                AgentRuntimeAdapter.Settings.defaults(Duration.ZERO));
        RuntimeStatus status = silent.status(w.replica());
        assertEquals(Liveness.ABSENT, status.liveness());
        assertEquals(HealthSignal.NOT_DECLARED, status.health());
        assertTrue(status.hash().isEmpty());
    }

    private static HealthSignal healthOf(AgentRuntimeAdapterContractTest.World w, String kind, AgentHealthObservation facts) {
        AgentRuntimeAdapter a = new AgentRuntimeAdapter(
                p -> Optional.of(new AgentPlacementCatalog.AgentPlacement(w.agentId, w.ref, "cache-solution",
                        AgentRuntimeAdapterContractTest.LOCATION, kind)),
                w.ports, w.ports,
                ref -> Optional.of(new AgentObservation(true, facts, null, null, Instant.EPOCH, Instant.EPOCH)),
                w.hasher, w.handoff, w.bridge, Clock.systemUTC(), AgentRuntimeAdapter.Settings.defaults(Duration.ZERO));
        return a.status(w.replica()).health();
    }

    private static AgentHealthObservation facts(AgentHealthObservation.State state, int http, String actuator,
            AgentHealthObservation.Failure failure) {
        return new AgentHealthObservation(state, Instant.EPOCH, AgentHealthObservation.Strength.READINESS, http, actuator, failure);
    }

    /** The probe is the package-declared one and the mapping is the dp-remote adapter's: the gate means the same on both. */
    @Test
    void theHealthSignalIsTheDeclaredProbeReadTheWayDpRemoteReadsIt() {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        var none = AgentHealthObservation.Failure.NONE;
        assertEquals(HealthSignal.NOT_DECLARED, healthOf(w, null, facts(AgentHealthObservation.State.HEALTHY, 200, "UP", none)),
                "a package that declares no probe has NOT_DECLARED, whatever the agent reports (OR-101)");
        assertEquals(HealthSignal.HEALTHY, healthOf(w, "actuator-health", facts(AgentHealthObservation.State.UNSPECIFIED, 200, "UP", none)));
        assertEquals(HealthSignal.UNHEALTHY, healthOf(w, "actuator-health", facts(AgentHealthObservation.State.UNSPECIFIED, 200, "DOWN", none)));
        assertEquals(HealthSignal.UNHEALTHY, healthOf(w, "actuator-health", facts(AgentHealthObservation.State.HEALTHY, 503, "", none)),
                "Docker's own health state is a hint, never the gate");
        assertEquals(HealthSignal.HEALTHY, healthOf(w, "http-status", facts(AgentHealthObservation.State.UNSPECIFIED, 204, "", none)));
        assertEquals(HealthSignal.UNREADABLE, healthOf(w, "http-status",
                facts(AgentHealthObservation.State.UNSPECIFIED, 0, "", AgentHealthObservation.Failure.TIMEOUT)));
        assertEquals(HealthSignal.UNREADABLE, healthOf(w, "http-status", null), "declared, up, and nothing reported yet");
    }

    // ---- W3 integration re-review, major 2: a nanosecond start against the database's microsecond one ----------------------------

    private RuntimeStatus statusWith(Instant reportedStart, Instant recordedStart) {
        AgentRuntimeAdapterContractTest.World w = new AgentRuntimeAdapterContractTest.World();
        AgentHealthObservation up = new AgentHealthObservation(AgentHealthObservation.State.HEALTHY, Instant.EPOCH,
                AgentHealthObservation.Strength.READINESS, 200, "UP", AgentHealthObservation.Failure.NONE);
        com.solace.sm.dp.api.agw.AgentObservedStatePort observed = ref -> Optional
                .of(new AgentObservation(true, up, null, null, reportedStart, Instant.EPOCH));
        AgentPlacementCatalog catalog = new AgentPlacementCatalog() {
            @Override
            public Optional<AgentPlacement> resolve(com.solace.sm.dp.apply.runtime.PlacementRef p) {
                return Optional.of(new AgentPlacement(w.agentId, w.ref, "cache-solution", AgentRuntimeAdapterContractTest.LOCATION,
                        "actuator-health"));
            }

            @Override
            public Optional<Instant> observedStart(AgentPlacement p) {
                return Optional.of(recordedStart);
            }
        };
        AgentRuntimeAdapter adapter = new AgentRuntimeAdapter(catalog, w.ports, w.ports, observed, w.hasher, w.handoff, w.bridge,
                Clock.systemUTC(), AgentRuntimeAdapter.Settings.defaults(Duration.ZERO));
        return adapter.status(w.replica());
    }

    @Test
    void aSubMicrosecondDifferenceBetweenTheReportedAndTheRecordedStartKeepsTheHealth() {
        // Docker's nanoseconds, and PostgreSQL's half-up rounding of the same instant to microseconds (500 ns or more rounds up)
        Instant reported = Instant.parse("2026-10-11T10:00:00.000000600Z");
        Instant recorded = Instant.parse("2026-10-11T10:00:00.000001Z");
        assertTrue(recorded.isAfter(reported), "the premise: the stored value is LATER by under a microsecond");
        assertEquals(HealthSignal.HEALTHY, statusWith(reported, recorded).health(), "the same container: health is kept");
    }

    @Test
    void aRecordedStartMoreThanTheLadderToleranceLaterDropsTheHeldHealth() {
        Instant reported = Instant.parse("2026-10-11T10:00:00.000000600Z");
        assertEquals(HealthSignal.UNREADABLE, statusWith(reported, reported.plusSeconds(1)).health(),
                "a restart was acknowledged and no report has seen the new container");
    }
}
