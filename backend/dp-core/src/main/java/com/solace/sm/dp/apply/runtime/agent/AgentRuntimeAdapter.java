package com.solace.sm.dp.apply.runtime.agent;

import com.solace.sm.dp.api.agw.AgentCommandIntent;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentCommandRef;
import com.solace.sm.dp.api.agw.AgentHealthObservation;
import com.solace.sm.dp.api.agw.AgentObservation;
import com.solace.sm.dp.api.agw.AgentObservedStatePort;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentOutcome;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.api.agw.CommandCompletion;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.OperationCorrelation;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.model.StartTolerance;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.RuntimeOperationContext;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.WriteBaselinePolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@link RuntimeAdapter} for a placement whose target is a host-local agent (plan 015 T3.1; {@code BE-D66}, {@code BE-D67},
 * {@code AGW-D21}..{@code AGW-D26}). Every operation is a durable command on {@link AgentCommandPort}; the adapter blocks until
 * the agent's terminal answer, and reads what the host holds through {@link AgentObservedStatePort}. It never reaches a host.
 *
 * <ul>
 *   <li><b>Command identity.</b> A command is keyed on the apply journal intent's attempt-free key
 *       ({@link RuntimeOperationContext#intentKey()}), never on the attempt: a resumed apply, or the engine's retry, finds the
 *       same command, so the agent's journal re-acks it under the same id and generation and the host operation runs once.
 *       Before every enqueue the adapter supersedes the placement's queued commands of OTHER keys.</li>
 *   <li><b>Render once.</b> {@link #materializeConfig} parks the engine's own {@link RenderedConfig} bytes in
 *       {@link AgentPayloadHandoff} BEFORE enqueuing (so the pull can never race the hand-off); the command row holds only the
 *       keyed hash. Nothing renders again unless SM restarted ({@link HandoffAgentCommandPayloadSource}).</li>
 *   <li><b>Hash.</b> The adapter reports back the hash its own {@link ConfigHasher} computes over the bytes it sent, never a
 *       digest the agent supplied ({@code OR-32}); {@link #status} translates the agent's plain digest through
 *       {@link AgentHashBridge}.</li>
 *   <li><b>Fail fast.</b> No live session holding the placement: {@code agent.session.absent} at once, before any enqueue. A
 *       session or lease that ends while waiting: {@code agent.session.lost} at once. The per-operation ceilings apply only
 *       while the session is live; a command that outlives its ceiling stays live and the engine's retry finds it.</li>
 *   <li><b>Refused, contracted.</b> {@code restore} is {@code agent.rollback.unsupported}; {@code readConfig} keeps the seam's
 *       {@code config.read.unsupported}; {@code assessRollback} is read-only and always {@link RollbackAssessment.Unknowable}.</li>
 * </ul>
 *
 * <p>There is no operation without the apply journal: the context-less overloads answer {@code agent.operation.context.missing}
 * rather than invent an identity. {@code I6}: no byte or digest reaches an exception, a log or a row from here. A failure is a
 * classifier built from a closed vocabulary plus, for a refusal, the agent's {@code detail_code}, which the data plane bounds to
 * {@code [A-Za-z][A-Za-z0-9._]{0,63}} when it stores it and which is clipped to the {@code ErrorClass} grammar here: an identifier,
 * never free text (the ack's free-text message is not stored or read).
 */
public final class AgentRuntimeAdapter implements RuntimeAdapter, WriteBaselinePolicy {

    static final ErrorClass NO_CONTEXT = new ErrorClass("agent.operation.context.missing");
    static final ErrorClass UNRESOLVED = new ErrorClass("agent.placement.unresolved");
    static final ErrorClass ROLLBACK_UNSUPPORTED = new ErrorClass("agent.rollback.unsupported");
    static final ErrorClass TIMEOUT = new ErrorClass("agent.command.timeout");
    static final ErrorClass INTERRUPTED = new ErrorClass("agent.command.interrupted");
    static final ErrorClass HASH_MISMATCH = new ErrorClass("agent.apply.hash.mismatch");
    static final ErrorClass TARGET_MOVED = new ErrorClass("reconcile.target.moved");

    /**
     * Every wait bound and the durable deadline of a command.
     *
     * @param commandDeadline how long a command stays deliverable. Must exceed every lease verb ceiling plus the admission
     *                        margin, or the lease would never admit it ({@code JdbcAgentCommandRepository#admitted})
     * @param readBackWait    after an APPLY/REMATERIALIZE is acknowledged, how long to wait for the report that proves it
     * @param pollInterval    how often the adapter looks at the command and the session while it waits
     */
    public record Settings(Duration commandDeadline, Duration preflightCeiling, Duration applyCeiling,
            Duration restartCeiling, Duration stopCeiling, Duration readBackWait, Duration pollInterval) {

        public Settings {
            for (Duration d : new Duration[] {commandDeadline, preflightCeiling, applyCeiling, restartCeiling, stopCeiling,
                pollInterval}) {
                if (d == null || d.isZero() || d.isNegative()) {
                    throw new IllegalArgumentException("every agent wait bound is a positive duration");
                }
            }
            if (readBackWait == null || readBackWait.isNegative()) {
                throw new IllegalArgumentException("the read-back wait is not negative");
            }
        }

        /** {@code BE-D66}: 30 s pre-flight, 60 s apply and stop, 120 s restart; 45 s read-back ({@code AGW-D21}). */
        public static Settings defaults(Duration readBackWait) {
            return new Settings(Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(60),
                    Duration.ofSeconds(120), Duration.ofSeconds(60), readBackWait, Duration.ofMillis(50));
        }

        Duration ceilingOf(AgentOperation operation) {
            return switch (operation) {
                case PREFLIGHT -> preflightCeiling;
                case APPLY, REMATERIALIZE -> applyCeiling;
                case RESTART -> restartCeiling;
                case STOP -> stopCeiling;
            };
        }
    }

    private final AgentPlacementCatalog catalog;
    private final AgentCommandPort commands;
    private final AgentSessionPort sessions;
    private final AgentObservedStatePort observed;
    private final ConfigHasher hasher;
    private final AgentPayloadHandoff handoff;
    private final AgentHashBridge bridge;
    private final Clock clock;
    private final Settings settings;

    public AgentRuntimeAdapter(AgentPlacementCatalog catalog, AgentCommandPort commands, AgentSessionPort sessions,
            AgentObservedStatePort observed, ConfigHasher hasher, AgentPayloadHandoff handoff, AgentHashBridge bridge,
            Clock clock, Settings settings) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.observed = Objects.requireNonNull(observed, "observed");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    // ---- replicas / prepare -----------------------------------------------------------------------------------------

    @Override
    public List<TargetReplica> replicas(PlacementRef placement) {
        resolve(placement);
        return List.of(replicaOf(placement));
    }

    /** One replica per placement ({@code AGW-D25}, Q4 (b)): the replica ref IS the placement id. */
    private static TargetReplica replicaOf(PlacementRef placement) {
        return new TargetReplica(placement, new ReplicaRef(placement.placement().value().toString()));
    }

    @Override
    public PreflightResult prepare(PlacementRef placement, long generation) {
        throw new RuntimeAdapterException(NO_CONTEXT);
    }

    @Override
    public PreflightResult prepare(PlacementRef placement, long generation, RuntimeOperationContext context) {
        AgentPlacementCatalog.AgentPlacement p = resolve(placement);
        CommandCompletion done = run(placement, p, AgentOperation.PREFLIGHT, require(context), null, null);
        if (done.outcome() == AgentOutcome.PREFLIGHT_REFUSED) {
            return new PreflightResult.Refused(new ErrorClass(detailToken("agent.preflight.refused", done)), ref(p));
        }
        if (done.outcome() != AgentOutcome.PREFLIGHT_OK) {
            throw failure(AgentOperation.PREFLIGHT, done);
        }
        return new PreflightResult.Ready(List.of(replicaOf(placement)), p.location(), p.healthSignalDeclared(), ref(p));
    }

    // ---- materialize -------------------------------------------------------------------------------------------------

    @Override
    public MaterializeResult materializeConfig(TargetReplica replica, long generation, RenderedConfig config) {
        throw new RuntimeAdapterException(NO_CONTEXT);
    }

    @Override
    public MaterializeResult materializeConfig(TargetReplica replica, long generation, RenderedConfig config,
            RuntimeOperationContext context) {
        PlacementRef placement = replica.placement();
        AgentPlacementCatalog.AgentPlacement p = resolve(placement);
        return write(placement, p, AgentOperation.APPLY, require(context), config, null);
    }

    @Override
    public MaterializeResult rematerializeIfCurrent(TargetReplica replica, long generation, RenderedConfig config,
            String expectedCurrentHash) {
        throw new RuntimeAdapterException(NO_CONTEXT);
    }

    @Override
    public MaterializeResult rematerializeIfCurrent(TargetReplica replica, long generation, RenderedConfig config,
            String expectedCurrentHash, RuntimeOperationContext context) {
        PlacementRef placement = replica.placement();
        AgentPlacementCatalog.AgentPlacement p = resolve(placement);
        RuntimeOperationContext ctx = require(context);
        // The agent compares the PLAIN digest of the file it holds. The expectation arrives as a KEYED hash, so it is translated
        // through the bridge (or one render of the candidate generations). A generation SM cannot place is a target that moved:
        // nothing is sent. A null expectation means "still absent", which the wire spells as the empty string.
        String expectedPlain = "";
        if (expectedCurrentHash != null) {
            expectedPlain = bridge.plainFor(placement, expectedCurrentHash).orElseThrow(() -> new RuntimeAdapterException(TARGET_MOVED));
        }
        return write(placement, p, AgentOperation.REMATERIALIZE, ctx, config, expectedPlain);
    }

    private MaterializeResult write(PlacementRef placement, AgentPlacementCatalog.AgentPlacement p, AgentOperation operation,
            RuntimeOperationContext ctx, RenderedConfig config, String expectedCurrentPlain) {
        byte[] bytes = config.bytes();
        try {
            String plain = AgentHashBridge.plainSha256(bytes);
            String keyed = hasher.hash(placement.tenant(), bytes);
            if (!keyed.equals(config.hash())) {
                throw new RuntimeAdapterException(HASH_MISMATCH);   // the engine's render and this hasher disagree: write nothing
            }
            bridge.record(placement, plain, keyed);
            CommandCompletion done = run(placement, p, operation, ctx, keyed, new Staged(bytes, plain, expectedCurrentPlain));
            if (operation == AgentOperation.REMATERIALIZE && done.outcome() == AgentOutcome.REFUSED
                    && "TARGET_MOVED".equals(done.detailCode())) {
                throw new RuntimeAdapterException(TARGET_MOVED);    // the agent found the file is not what was observed: nothing written
            }
            if (done.outcome() != AgentOutcome.APPLIED) {
                throw failure(operation, done);
            }
            awaitReadBack(placement, p, done, plain, keyed);
            return new MaterializeResult(keyed, ref(p));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * An APPLY's read-back is the agent's actual-state REPORT, which arrives after the terminal acknowledgement. Without this wait
     * the engine can ask before the report is committed, find nothing and enqueue a second APPLY: a duplicate host operation
     * caused by a race inside the DP. The ack carries no digest ({@code AgentAck}), so the proof is the report: one that carries
     * this command's generation or a later one and names a DIFFERENT file fails the step; none within the wait returns and lets the
     * engine's own read-back classify it ({@code materialize.readback.unavailable}).
     */
    private void awaitReadBack(PlacementRef placement, AgentPlacementCatalog.AgentPlacement p, CommandCompletion done,
            String plain, String keyed) {
        long deadline = System.nanoTime() + settings.readBackWait().toNanos();
        for (;;) {
            Optional<AgentObservation> seen = observed.latest(p.ref());
            if (seen.isPresent() && seen.get().persistedGeneration() != null
                    && seen.get().persistedGeneration() >= done.generation()) {
                if (seen.get().reportedSha256() != null) {
                    if (plain.equals(seen.get().reportedSha256())) {
                        return;
                    }
                    throw new RuntimeAdapterException(HASH_MISMATCH);
                }
                // No plain digest is held (SM restarted since the agent sent its op-finished report, and the agent's periodic
                // report carries none): the proof is the agent's acknowledged APPLIED on this generation plus a report that
                // persisted it, read as the keyed hash the command row holds. A different, newer applied hash is not a mismatch
                // of THIS write: keep waiting for the report that names the file.
                if (keyed.equals(catalog.appliedConfigHash(p, seen.get().persistedGeneration()).orElse(null))) {
                    return;
                }
            }
            if (System.nanoTime() - deadline >= 0) {
                return;
            }
            pause();
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------------------

    @Override
    public LifecycleResult startOrUpdate(TargetReplica replica, long generation) {
        throw new RuntimeAdapterException(NO_CONTEXT);
    }

    @Override
    public LifecycleResult startOrUpdate(TargetReplica replica, long generation, RuntimeOperationContext context) {
        return lifecycle(replica, AgentOperation.RESTART, context);
    }

    @Override
    public LifecycleResult stop(TargetReplica replica) {
        throw new RuntimeAdapterException(NO_CONTEXT);
    }

    @Override
    public LifecycleResult stop(TargetReplica replica, RuntimeOperationContext context) {
        return lifecycle(replica, AgentOperation.STOP, context);
    }

    private LifecycleResult lifecycle(TargetReplica replica, AgentOperation operation, RuntimeOperationContext context) {
        PlacementRef placement = replica.placement();
        AgentPlacementCatalog.AgentPlacement p = resolve(placement);
        CommandCompletion done = run(placement, p, operation, require(context), null, null);
        if (done.outcome() != AgentOutcome.APPLIED) {
            // never a quiet "not issued": a restart that failed or was refused must fail the step
            throw failure(operation, done);
        }
        return new LifecycleResult(true, ref(p));
    }

    // ---- status / rollback --------------------------------------------------------------------------------------------

    /**
     * The replica, from the last accepted report ({@link AgentObservedStatePort}). The container facts are the ones the report
     * declares: it is RUNNING when a start time is known and the agent did not report it unreachable ({@code decisions.md} AGW
     * health: "a container that is not running ... is UNREACHABLE"); no observation at all is ABSENT. The persisted hash is the
     * keyed hash of the generation the reported plain digest belongs to; a digest that matches no generation SM knows is a keyed
     * hash of its own that equals none of them (so "foreign content" stays distinguishable from "no file").
     */
    @Override
    public RuntimeStatus status(TargetReplica replica) {
        PlacementRef placement = replica.placement();
        AgentPlacementCatalog.AgentPlacement p = resolve(placement);
        Optional<AgentObservation> seen = observed.latest(p.ref());
        if (seen.isEmpty()) {
            return new RuntimeStatus(Liveness.ABSENT, HealthSignal.NOT_DECLARED, null, null, ref(p));
        }
        AgentObservation o = seen.get();
        boolean running = o.startedAt() != null
                && !(o.health() != null && o.health().state() == AgentHealthObservation.State.UNREACHABLE);
        String persisted = null;
        if (o.reportedSha256() != null) {
            persisted = bridge.keyedFor(placement, o.reportedSha256())
                    .orElseGet(() -> bridge.unknownContentHash(placement, o.reportedSha256()));
        } else if (o.persistedGeneration() != null) {
            // No plain digest is held: SM restarted since the op-finished report that carried it, and the agent's periodic
            // report never does. The agent says it persisted generation G, so the file is the newest one it acknowledged as
            // applied at or below G: that command's KEYED hash (already stored; nothing new is stored), which compares like with
            // like against every expectation the engine holds. Null stays "no file / unknown" when nothing was ever applied.
            persisted = catalog.appliedConfigHash(p, o.persistedGeneration()).orElse(null);
        }
        // The start baseline is placement_start_observation, the one source (AGW-D28 item 2, OR-139): fed by the acknowledgement
        // of an APPLY, REMATERIALIZE or RESTART and, forward only, by reports. The merged report's own start is not a baseline.
        Optional<Instant> recorded = catalog.observedStart(p);
        PreBaseline baseline = recorded.map(start -> new PreBaseline(start, p.agentId().toString())).orElse(null);
        // A health is only meaningful under the start it was observed under. When the recorded start is later than the one the
        // report carries (a RESTART was acknowledged and no report has seen the new container yet), the held health is the previous
        // container's: unread, so the health gate keeps polling instead of passing on it. The comparison allows the ladder's
        // tolerance: the stored start is rounded to microseconds, the report's is Docker's nanoseconds. Known limit: the
        // observation only moves forward, so a host clock that steps BACK across a restart leaves the recorded start ahead of every
        // later report; health then stays unread and the gate fails closed (gate.health.unreadable), acceptable for v1.
        AgentHealthObservation facts = o.health();
        if (recorded.isPresent() && o.startedAt() != null && recorded.get().isAfter(o.startedAt().plus(StartTolerance.VALUE))) {
            facts = null;
        }
        return new RuntimeStatus(running ? Liveness.RUNNING : Liveness.STOPPED, health(p, facts, running), persisted,
                baseline, ref(p));
    }

    /**
     * The start the agent's acknowledgement recorded for this intent ({@code JdbcAgentCommandRepository#ack}), found by intent so a
     * retry or a resume, which reuses the intent's command, still gives the SUCCEEDED attempt the ack's value. Still only ever the
     * ack's: a write acknowledged without a start has none (fail closed), and nothing report-derived stands in.
     */
    @Override
    public PreBaseline writeBaseline(PlacementRef placement, UUID intentId, RuntimeStatus afterWrite) {
        return catalog.resolve(placement).flatMap(p -> catalog.ackedWriteBaseline(p, intentId)).orElse(null);
    }

    /** The package-declared probe, as the agent ran it, read exactly as the dp-remote adapter reads its own ({@code T6.9}). */
    private static HealthSignal health(AgentPlacementCatalog.AgentPlacement p, AgentHealthObservation facts, boolean running) {
        if (!p.healthSignalDeclared()) {
            return HealthSignal.NOT_DECLARED;
        }
        if (!running) {
            return HealthSignal.UNHEALTHY;
        }
        if (facts == null || facts.failure() != AgentHealthObservation.Failure.NONE || facts.httpStatus() == 0) {
            return HealthSignal.UNREADABLE;
        }
        boolean healthy = switch (p.healthKind()) {
            case "actuator-health" -> facts.httpStatus() == 200 && "UP".equals(facts.actuatorStatus());
            case "http-status" -> facts.httpStatus() >= 200 && facts.httpStatus() < 300;
            default -> false;
        };
        return healthy ? HealthSignal.HEALTHY : HealthSignal.UNHEALTHY;
    }

    /** Read-only and contract-legal: the agent path retains no pre-image the data plane could restore from. */
    @Override
    public RollbackAssessment assessRollback(PlacementRef placement, long generation, String intendedHash) {
        resolve(placement);
        return new RollbackAssessment.Unknowable(ROLLBACK_UNSUPPORTED);
    }

    @Override
    public RestoreResult restore(PlacementRef placement, long generation, String targetHash) {
        throw new RuntimeAdapterException(ROLLBACK_UNSUPPORTED);
    }

    // ---- the command round trip ---------------------------------------------------------------------------------------

    /** The bytes to park for the pull. */
    private record Staged(byte[] bytes, String plainSha256, String expectedCurrentSha256) {
        /** Names nothing: the bytes and both plain digests are rendered configuration (global constraint 9). */
        @Override
        public String toString() {
            return "Staged[<redacted>]";
        }
    }

    private CommandCompletion run(PlacementRef placement, AgentPlacementCatalog.AgentPlacement p, AgentOperation operation,
            RuntimeOperationContext ctx, String keyedHash, Staged staged) {
        // Fail fast, BEFORE anything is written: no live session holding the placement is an answer, not a wait.
        if (!sessions.liveFor(p.agentId(), p.ref())) {
            throw new RuntimeAdapterException(new ErrorClass(GatewayCode.AGENT_SESSION_ABSENT.wire()));
        }
        commands.supersedeOutstanding(p.ref(), ctx.intentKey());
        if (staged != null) {
            handoff.stage(p.ref().tenantId(), ctx.intentKey(), operation, staged.bytes(), staged.plainSha256(),
                    staged.expectedCurrentSha256());
        }
        boolean ended = staged == null;
        try {
            OperationCorrelation correlation = new OperationCorrelation(ctx.applyId().value().toString(),
                    ctx.intentId().toString(), ctx.intentKey(), ctx.attemptId().toString(), ctx.step().name());
            Instant deadline = clock.instant().plus(settings.commandDeadline());
            AgentCommandRef command = commands.enqueue(new AgentCommandIntent(p.agentId(), p.ref(), ctx.intentKey(), operation,
                    keyedHash, deadline, correlation, null));
            if (staged != null) {
                handoff.bind(p.ref().tenantId(), ctx.intentKey(), operation, command.commandId().value(), deadline);
            }
            CommandCompletion done = await(p, command, operation);
            ended = true;                   // acknowledged, refused or superseded: the command is over
            return done;
        } catch (RuntimeException failure) {
            // A wait that merely times out, or loses its session, leaves the command live and the bytes parked until its
            // deadline: a pull after the timeout must not re-render (AGW-D26 item 2). Anything else ends the entry.
            ended = !(failure instanceof RuntimeAdapterException rae)
                    || !(TIMEOUT.equals(rae.errorClass()) || GatewayCode.AGENT_SESSION_LOST.wire().equals(rae.errorClass().token()));
            throw failure;
        } finally {
            if (ended) {
                handoff.release(p.ref().tenantId(), ctx.intentKey(), operation);
            }
        }
    }

    private CommandCompletion await(AgentPlacementCatalog.AgentPlacement p, AgentCommandRef command, AgentOperation operation) {
        Instant ceilingAt = clock.instant().plus(settings.ceilingOf(operation));
        for (;;) {
            Optional<CommandCompletion> completion = commands.completion(p.ref(), command.commandId());
            if (completion.isPresent() && completion.get().terminal()) {
                return completion.get();
            }
            if (!sessions.liveFor(p.agentId(), p.ref())) {
                // a closed session or an expired lease ends the wait now; the command stays as it is for the engine's retry.
                // The command was enqueued (and may have been delivered): afterDispatch, so a LIFECYCLE step lands
                // APPLY_INDETERMINATE and not CONFIG_DIVERGED (OR-146 (1)).
                throw RuntimeAdapterException.afterDispatch(new ErrorClass(GatewayCode.AGENT_SESSION_LOST.wire()));
            }
            if (!clock.instant().isBefore(ceilingAt)) {
                throw RuntimeAdapterException.afterDispatch(TIMEOUT);
            }
            try {
                pause();
            } catch (RuntimeAdapterException interrupted) {
                throw RuntimeAdapterException.afterDispatch(interrupted.errorClass());
            }
        }
    }

    private void pause() {
        try {
            Thread.sleep(settings.pollInterval().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeAdapterException(INTERRUPTED);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private AgentPlacementCatalog.AgentPlacement resolve(PlacementRef placement) {
        return catalog.resolve(placement).orElseThrow(() -> new RuntimeAdapterException(UNRESOLVED));
    }

    private static RuntimeOperationContext require(RuntimeOperationContext context) {
        if (context == null) {
            throw new RuntimeAdapterException(NO_CONTEXT);
        }
        return context;
    }

    /** An identifiers-only reference: the agent and the container name the package gave the placement, never a path or a host. */
    private static RuntimeRef ref(AgentPlacementCatalog.AgentPlacement p) {
        return RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, p.containerName(), RuntimeRef.Key.HOST, p.agentId().toString());
    }

    private static RuntimeAdapterException failure(AgentOperation operation, CommandCompletion done) {
        String base = "agent." + operation.name().toLowerCase(Locale.ROOT);
        AgentOutcome outcome = done.outcome();
        if (outcome == AgentOutcome.REFUSED && GatewayCode.AGENT_PAYLOAD_RENDER_CHANGED.wire().equals(done.detailCode())) {
            // the bytes the journal promised are gone and a re-render differs (AGW-D26): that code, exactly, so it is recognisable
            return new RuntimeAdapterException(new ErrorClass(GatewayCode.AGENT_PAYLOAD_RENDER_CHANGED.wire()));
        }
        if (outcome == AgentOutcome.REFUSED) {
            return new RuntimeAdapterException(new ErrorClass(detailToken("agent.command.refused", done)));
        }
        return new RuntimeAdapterException(new ErrorClass(clip(base + "." + segment(outcome.name()))));
    }

    /** {@code prefix} plus the completion's detail code as a classifier segment, clipped to the grammar's length. */
    private static String detailToken(String prefix, CommandCompletion done) {
        String detail = done.detailCode();
        return clip(detail == null || detail.isBlank() ? prefix : prefix + "." + segment(detail));
    }

    /** The error-class grammar allows lowercase letters, digits and dots: {@code FAILED_NO_EFFECT} becomes {@code failed.no.effect}. */
    private static String segment(String value) {
        String s = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", ".").replaceAll("^\\.+|\\.+$", "");
        return s.isEmpty() ? "unknown" : s;
    }

    private static String clip(String token) {
        String clipped = token.length() <= ErrorClass.MAX_LENGTH ? token : token.substring(0, ErrorClass.MAX_LENGTH);
        return clipped.endsWith(".") ? clipped.substring(0, clipped.length() - 1) : clipped;
    }

    @Override
    public String toString() {
        return "AgentRuntimeAdapter";
    }
}
