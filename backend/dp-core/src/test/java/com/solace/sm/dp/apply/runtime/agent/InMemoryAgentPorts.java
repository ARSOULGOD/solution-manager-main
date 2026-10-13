package com.solace.sm.dp.apply.runtime.agent;

import com.solace.sm.dp.agent.AgentCommandPayloadSource;
import com.solace.sm.dp.api.agw.AgentAck;
import com.solace.sm.dp.api.agw.AgentCommandIntent;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentCommandRef;
import com.solace.sm.dp.api.agw.AgentFeature;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentOutcome;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.CloseReason;
import com.solace.sm.dp.api.agw.CommandAckResult;
import com.solace.sm.dp.api.agw.CommandCompletion;
import com.solace.sm.dp.api.agw.CommandId;
import com.solace.sm.dp.api.agw.CommandNotice;
import com.solace.sm.dp.api.agw.CommandOffer;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.OperationCorrelation;
import com.solace.sm.dp.api.agw.SessionOpening;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The agent command queue and the session, in memory, with the semantics the JDBC repositories give (plan 015 T3.1): an
 * {@code enqueue} returns the LIVE command of the same {@code (intent key, operation)}, else the latest SUCCEEDED one, else mints
 * the next wire generation of the {@code (agent, placement)}; {@code supersedeOutstanding} takes QUEUED/OFFERED commands of other
 * keys; a pull reads the real {@link AgentCommandPayloadSource}. Used by the adapter's kit and unit tests, where speed and
 * determinism matter and the database is not the subject; the JDBC ports are proved by their own kits and by the ITs.
 */
public final class InMemoryAgentPorts implements AgentCommandPort, AgentSessionPort {

    /** A command row. */
    public static final class Row {
        public final CommandId id;
        public final UUID agentId;
        public final AgentPlacementRef placement;
        public final String intentKey;
        public final AgentOperation operation;
        public final String keyedHash;
        public final long generation;
        public final OperationCorrelation correlation;
        public final Instant deadline;
        public volatile String state = "QUEUED";
        public volatile AgentOutcome outcome;
        public volatile String detailCode;

        Row(CommandId id, AgentCommandIntent intent, long generation) {
            this.id = id;
            this.agentId = intent.agentId();
            this.placement = intent.placement();
            this.intentKey = intent.intentKey();
            this.operation = intent.operation();
            this.keyedHash = intent.keyedConfigHash();
            this.generation = generation;
            this.correlation = intent.correlation();
            this.deadline = intent.deadline();
        }

        boolean live() {
            return state.equals("QUEUED") || state.equals("OFFERED") || state.equals("DELIVERED");
        }

        AgentCommandPayloadSource.Command command() {
            return new AgentCommandPayloadSource.Command(id, agentId, placement, operation, intentKey, keyedHash, generation);
        }
    }

    private final AgentCommandPayloadSource payloads;
    private final Map<CommandId, Row> rows = new LinkedHashMap<>();
    private volatile boolean live = true;
    private volatile Consumer<Row> afterEnqueue = row -> { };
    private int enqueues;

    public InMemoryAgentPorts(AgentCommandPayloadSource payloads) {
        this.payloads = payloads;
    }

    /** Whether the agent's session is open and its lease holds the placement: what {@code liveFor} answers. */
    public void live(boolean live) {
        this.live = live;
    }

    /** Runs after every enqueue that MINTED a command, on the enqueuing thread (the kit's synchronous agent). */
    public void afterEnqueue(Consumer<Row> hook) {
        this.afterEnqueue = hook;
    }

    public synchronized List<Row> rows() {
        return new ArrayList<>(rows.values());
    }

    public synchronized int enqueueCalls() {
        return enqueues;
    }

    public synchronized Optional<Row> row(CommandId id) {
        return Optional.ofNullable(rows.get(id));
    }

    // ---- AgentCommandPort ---------------------------------------------------------------------------------------------

    @Override
    public AgentCommandRef enqueue(AgentCommandIntent intent) {
        Row minted = null;
        AgentCommandRef result;
        synchronized (this) {
            enqueues++;
            Row live = null;
            Row done = null;
            long max = 0;
            for (Row r : rows.values()) {
                if (r.agentId.equals(intent.agentId()) && r.placement.equals(intent.placement())) {
                    max = Math.max(max, r.generation);
                }
                if (r.placement.tenantId().equals(intent.placement().tenantId()) && r.intentKey.equals(intent.intentKey())
                        && r.operation == intent.operation()) {
                    if (r.live()) {
                        live = r;
                    } else if (r.state.equals("ACKED") && (r.outcome == AgentOutcome.APPLIED || r.outcome == AgentOutcome.PREFLIGHT_OK)) {
                        done = r;
                    }
                }
            }
            if (live != null) {
                return new AgentCommandRef(live.id, live.generation, false);
            }
            if (done != null) {
                return new AgentCommandRef(done.id, done.generation, false);
            }
            minted = new Row(new CommandId(UUID.randomUUID()), intent, max + 1);
            rows.put(minted.id, minted);
            result = new AgentCommandRef(minted.id, minted.generation, true);
        }
        afterEnqueue.accept(minted);
        return result;
    }

    @Override
    public synchronized void supersedeOutstanding(AgentPlacementRef placement, String keepIntentKey) {
        rows.values().stream().filter(r -> r.placement.equals(placement) && !r.intentKey.equals(keepIntentKey)
                && (r.state.equals("QUEUED") || r.state.equals("OFFERED"))).forEach(r -> r.state = "SUPERSEDED");
    }

    @Override
    public synchronized List<CommandNotice> pendingFor(AgentSessionRef ref, int limit, Duration renotifyAfter) {
        List<CommandNotice> out = new ArrayList<>();
        for (Row r : rows.values()) {
            if (r.agentId.equals(ref.agentId()) && (r.state.equals("QUEUED") || r.state.equals("OFFERED")
                    || r.state.equals("DELIVERED")) && out.size() < limit) {
                if (r.state.equals("QUEUED")) {
                    r.state = "OFFERED";
                }
                out.add(new CommandNotice(r.id, r.placement.placementId(), r.generation, r.operation,
                        payloads.plainDigestFor(r.command()).orElse(null), r.deadline, r.correlation));
            }
        }
        return out;
    }

    @Override
    public Set<UUID> agentsWithDeliverableWork(List<AgentSessionRef> sessions, Duration renotifyAfter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CommandOffer pull(AgentSessionRef ref, CommandId id) {
        Row r;
        synchronized (this) {
            r = rows.get(id);
        }
        if (r == null || !r.agentId.equals(ref.agentId())) {
            throw new GatewayPortException(GatewayCode.UNKNOWN_COMMAND, "unknown");
        }
        if (r.state.equals("SUPERSEDED")) {
            throw new GatewayPortException(GatewayCode.SUPERSEDED, "superseded");
        }
        try {
            return new CommandOffer(r.id, r.generation, r.intentKey, r.placement.placementId(), r.operation, null, r.deadline,
                    r.correlation, payloads.payloadFor(r.command()));
        } catch (GatewayPortException e) {
            if (e.code() == GatewayCode.AGENT_PAYLOAD_RENDER_CHANGED) {
                synchronized (this) {
                    r.state = "REFUSED";
                    r.outcome = AgentOutcome.REFUSED;
                    r.detailCode = e.code().wire();
                }
            }
            throw e;
        }
    }

    @Override
    public synchronized void received(AgentSessionRef ref, String requestId, CommandId id, long wireGeneration) {
        Row r = rows.get(id);
        if (r == null) {
            throw new GatewayPortException(GatewayCode.UNKNOWN_COMMAND, "unknown");
        }
        if (r.state.equals("SUPERSEDED")) {
            throw new GatewayPortException(GatewayCode.SUPERSEDED, "superseded");
        }
        if (r.state.equals("OFFERED")) {
            r.state = "DELIVERED";
        }
    }

    @Override
    public synchronized CommandAckResult ack(AgentSessionRef ref, CommandId id, AgentAck ack) {
        Row r = rows.get(id);
        if (r == null) {
            return CommandAckResult.UNKNOWN_COMMAND;
        }
        if (r.state.equals("SUPERSEDED")) {
            throw new GatewayPortException(GatewayCode.SUPERSEDED, "superseded");
        }
        if (r.state.equals("ACKED") || r.state.equals("REFUSED")) {
            return CommandAckResult.DUPLICATE;
        }
        r.state = ack.outcome() == AgentOutcome.REFUSED ? "REFUSED" : "ACKED";
        r.outcome = ack.outcome();
        r.detailCode = ack.detailCode().isEmpty() ? null : ack.detailCode();
        return CommandAckResult.COMMITTED;
    }

    @Override
    public synchronized Optional<CommandCompletion> completion(AgentPlacementRef placement, CommandId id) {
        Row r = rows.get(id);
        if (r == null || !r.placement.equals(placement)) {
            return Optional.empty();
        }
        AgentOutcome outcome = r.state.equals("SUPERSEDED") ? AgentOutcome.SUPERSEDED : r.outcome;
        return Optional.of(new CommandCompletion(r.id, r.generation, r.operation, outcome, r.detailCode, null,
                outcome == null ? null : Instant.EPOCH));
    }

    // ---- AgentSessionPort (only the fail-fast read is real) -------------------------------------------------------------

    @Override
    public AgentSessionRef claim(String certificateThumbprint, SessionOpening opening, AgentRequestOrigin origin) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void heartbeat(AgentSessionRef ref, Instant agentClock) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close(AgentSessionRef ref, CloseReason reason) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean liveFor(UUID agentId, AgentPlacementRef placement) {
        return live;
    }

    @Override
    public Set<AgentFeature> featuresOf(UUID agentId, AgentPlacementRef placement) {
        return Set.of();
    }
}
