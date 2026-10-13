package com.solace.sm.dp.apply.runtime.agent;

import com.solace.sm.dp.api.agw.AgentAck;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentHealthObservation;
import com.solace.sm.dp.api.agw.AgentObservation;
import com.solace.sm.dp.api.agw.AgentObservedStatePort;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentOutcome;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.CommandId;
import com.solace.sm.dp.api.agw.CommandNotice;
import com.solace.sm.dp.api.agw.CommandOffer;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A host-local agent and its one container, in memory (plan 015 T3.1), speaking to the data plane only through
 * {@link AgentCommandPort}. It applies the Go agent's journal rule ({@code commands.go:198-246}): a notice for a
 * {@code (placement, generation)} already in its journal RE-ACKS the RECORDED outcome under the recorded command id and never
 * re-runs, so a command re-sent after an SM restart runs on the host exactly once.
 *
 * <p>It is also the {@link AgentObservedStatePort}: what it last REPORTED, which is not always what the host holds (a test can
 * defer reports to open the window an SM crash opens). The container, the file and the counters are public so a test can assert
 * what physically happened.
 */
public final class SimulatedAgent implements AgentObservedStatePort {

    private enum Phase { RECEIVED, TERMINAL }

    /** The start each terminal ack carried, kept as the Go journal keeps it, so a re-ack says the same thing. */
    private final java.util.Map<String, Instant> ackedStart = new java.util.HashMap<>();

    private record Journal(CommandId commandId, long generation, Phase phase, AgentOutcome outcome, String detail,
            String ackRequestId, AgentOperation operation) {
    }

    private final AgentCommandPort commands;
    private final UUID agentId;
    private final AgentPlacementRef placement;
    private volatile AgentSessionRef session;
    private final Map<String, Journal> journal = new LinkedHashMap<>();
    private int requestSeq;
    private long clockTicks;

    // ---- the host ---------------------------------------------------------------------------------------------------------
    private byte[] file;
    private boolean running = true;
    private Instant startedAt;
    private long persistedGeneration;
    private int restartCount;
    private int writeCount;
    private int stopCount;
    private int executed;

    // ---- what the last report said ------------------------------------------------------------------------------------------
    private boolean deferStartReports;
    private boolean omitStartOnAcks;
    private final java.util.Set<AgentOperation> dropAcksOf = new java.util.HashSet<>();
    private Instant reportedStart;
    private AgentObservation reported;
    private AgentHealthObservation healthFacts;

    public SimulatedAgent(AgentCommandPort commands, UUID agentId, AgentPlacementRef placement) {
        this(commands, agentId, placement, new AgentSessionRef(agentId, 1));
    }

    /** As above, on the session the data plane opened for this agent (the epoch it fences with). */
    public SimulatedAgent(AgentCommandPort commands, UUID agentId, AgentPlacementRef placement, AgentSessionRef session) {
        this.commands = commands;
        this.agentId = agentId;
        this.placement = placement;
        this.session = session;
        this.startedAt = nextHostTime();
        report();
    }

    private Instant nextHostTime() {
        return Instant.parse("2026-10-10T00:00:00Z").plusSeconds(++clockTicks);
    }

    public AgentSessionRef session() {
        return session;
    }

    /** The agent reconnected: its host and journal survive, and it fences with the new session's epoch from now on (plan 015 Final). */
    public SimulatedAgent rebindSession(AgentSessionRef reconnected) {
        this.session = reconnected;
        return this;
    }

    /**
     * When true the agent keeps running ops and reporting the file, but its reports keep the PREVIOUS container start: the window
     * in which an SM crash finds a restart that has happened but has not been observed yet.
     */
    /** The acks carry no {@code container_started_at} (a never-started container, or an agent that does not send it). */
    public synchronized SimulatedAgent omitStartOnAcks(boolean omit) {
        this.omitStartOnAcks = omit;
        return this;
    }

    /** Sets the container's host-clock start directly (a test of the data plane's clock bound). */
    public synchronized SimulatedAgent startedAtIs(Instant start) {
        this.startedAt = start;
        return this;
    }

    public synchronized SimulatedAgent deferStartReports(boolean defer) {
        this.deferStartReports = defer;
        return this;
    }

    /** The acks of these operations never reach the data plane (the connection dropped), though the journal records them. */
    public synchronized SimulatedAgent dropAcksOf(AgentOperation... operations) {
        dropAcksOf.clear();
        dropAcksOf.addAll(java.util.Arrays.asList(operations));
        return this;
    }

    /** The health facts a report carries (a probe the package declared); {@code null} for none. */
    public synchronized SimulatedAgent healthFacts(AgentHealthObservation facts) {
        this.healthFacts = facts;
        report();
        return this;
    }

    // ---- the host's counters ----------------------------------------------------------------------------------------------

    public synchronized int restartCount() {
        return restartCount;
    }

    public synchronized int writeCount() {
        return writeCount;
    }

    public synchronized int stopCount() {
        return stopCount;
    }

    /** Commands the agent actually ran on the host (a re-ack from the journal is not one). */
    public synchronized int executed() {
        return executed;
    }

    public synchronized boolean running() {
        return running;
    }

    public synchronized byte[] file() {
        return file == null ? null : file.clone();
    }

    public synchronized Instant startedAt() {
        return startedAt;
    }

    /** Somebody else writes the file on the host (not a command): what a human or another process does. Reports follow. */
    public synchronized void foreignWrite(byte[] bytes) {
        file = bytes.clone();
        report();
    }

    // ---- reports ------------------------------------------------------------------------------------------------------------

    /** Puts what the host holds NOW into the agent's report (the container start only unless start reports are deferred). */
    public synchronized void report() {
        if (!deferStartReports) {
            reportedStart = startedAt;
        }
        AgentHealthObservation health = running ? healthFacts
                : new AgentHealthObservation(AgentHealthObservation.State.UNREACHABLE, Instant.EPOCH,
                        AgentHealthObservation.Strength.UNSPECIFIED, 0, "", AgentHealthObservation.Failure.NONE);
        reported = new AgentObservation(true, health, file == null ? null : AgentHashBridge.plainSha256(file),
                persistedGeneration == 0 ? null : persistedGeneration, reportedStart, Instant.EPOCH);
    }

    @Override
    public synchronized Optional<AgentObservation> latest(AgentPlacementRef ref) {
        return ref.placementId().equals(placement.placementId()) ? Optional.ofNullable(reported) : Optional.empty();
    }

    // ---- the agent loop ---------------------------------------------------------------------------------------------------

    /** One iteration: ask for what is pending and handle each notice. Returns how many notices were handled. */
    public int step() {
        List<CommandNotice> notices = commands.pendingFor(session, 32, Duration.ZERO);
        int handled = 0;
        for (CommandNotice notice : notices) {
            handle(notice);
            handled++;
        }
        return handled;
    }

    private static String key(UUID placementId, long generation) {
        return placementId + "@" + generation;
    }

    private void handle(CommandNotice notice) {
        String key = key(notice.placementId(), notice.generation());
        Journal known;
        synchronized (this) {
            known = journal.get(key);
        }
        if (known != null && known.phase() == Phase.TERMINAL) {
            // the journal rule: re-ack the RECORDED outcome under the recorded command id; never re-run
            tryAck(known.commandId(), known.generation(), known.operation(), known.outcome(), known.detail(), known.ackRequestId(),
                    startOf(key));
            return;
        }
        CommandOffer offer;
        try {
            offer = commands.pull(session, notice.commandId());
        } catch (GatewayPortException e) {
            terminate(notice.commandId(), notice.generation(), notice.operation(), key,
                    e.code() == GatewayCode.SUPERSEDED ? AgentOutcome.SUPERSEDED : AgentOutcome.REFUSED, e.code().wire());
            return;
        }
        synchronized (this) {
            journal.put(key, new Journal(offer.commandId(), offer.generation(), Phase.RECEIVED, null, null, null,
                    offer.operation()));
        }
        try {
            commands.received(session, "received-" + (++requestSeq), offer.commandId(), offer.generation());
        } catch (GatewayPortException e) {
            if (e.code() == GatewayCode.SUPERSEDED) {
                terminate(offer.commandId(), offer.generation(), offer.operation(), key, AgentOutcome.SUPERSEDED, "SUPERSEDED");
                return;
            }
        }
        run(offer, key);
    }

    /**
     * Pulls and journals a command as RECEIVED but never announces it: the agent journalled it and the connection dropped before
     * {@code CommandReceived} reached the data plane (the T2.3 integrator note's window). Returns the offer.
     */
    public CommandOffer receiveWithoutAnnouncing(CommandNotice notice) {
        CommandOffer offer = commands.pull(session, notice.commandId());
        synchronized (this) {
            journal.put(key(offer.placementId(), offer.generation()), new Journal(offer.commandId(), offer.generation(),
                    Phase.RECEIVED, null, null, null, offer.operation()));
            spooled.put(offer.commandId(), offer);
        }
        return offer;
    }

    private final Map<CommandId, CommandOffer> spooled = new LinkedHashMap<>();

    /**
     * The first lease grant after an outage: every RECEIVED command in the journal is resumed FROM THE SPOOL and dispatched at
     * once, before the data plane has had the chance to answer SUPERSEDED (the agent's STALE_GENERATION guard covers only a
     * generation below one it has applied). Announcement and ack come after the operation has run.
     */
    public void resumeReceivedFromSpool() {
        List<CommandOffer> resumable;
        synchronized (this) {
            resumable = new ArrayList<>(spooled.values());
            spooled.clear();
        }
        for (CommandOffer offer : resumable) {
            run(offer, key(offer.placementId(), offer.generation()), true);
        }
    }

    private void run(CommandOffer offer, String key) {
        run(offer, key, false);
    }

    private void run(CommandOffer offer, String key, boolean announceAfter) {
        AgentOutcome outcome;
        String detail = "";
        Instant started = null;
        synchronized (this) {
            executed++;
            switch (offer.operation()) {
                case PREFLIGHT -> outcome = AgentOutcome.PREFLIGHT_OK;
                case APPLY -> {
                    byte[] bytes = offer.payload().bytes();
                    if (!AgentHashBridge.plainSha256(bytes).equals(offer.payload().configSha256())) {
                        outcome = AgentOutcome.REFUSED;
                        detail = "KEY_HASH_MISMATCH";
                    } else if (offer.generation() < persistedGeneration) {
                        outcome = AgentOutcome.REFUSED;
                        detail = "STALE_GENERATION";
                    } else {
                        file = bytes;
                        writeCount++;
                        persistedGeneration = offer.generation();
                        outcome = AgentOutcome.APPLIED;
                        started = startedAt;
                    }
                }
                case REMATERIALIZE -> {
                    String expected = offer.payload().expectedCurrentSha256();
                    String current = file == null ? "" : AgentHashBridge.plainSha256(file);
                    byte[] bytes = offer.payload().bytes();
                    if (expected == null || !expected.equals(current)) {
                        outcome = AgentOutcome.REFUSED;
                        detail = "TARGET_MOVED";
                    } else if (!AgentHashBridge.plainSha256(bytes).equals(offer.payload().configSha256())) {
                        outcome = AgentOutcome.REFUSED;
                        detail = "KEY_HASH_MISMATCH";
                    } else {
                        file = bytes;
                        writeCount++;
                        persistedGeneration = Math.max(persistedGeneration, offer.generation());
                        outcome = AgentOutcome.APPLIED;
                        started = startedAt;
                    }
                }
                case RESTART -> {
                    restartCount++;
                    running = true;
                    startedAt = nextHostTime();
                    started = startedAt;
                    outcome = AgentOutcome.APPLIED;
                }
                case STOP -> {
                    stopCount++;
                    running = false;
                    outcome = AgentOutcome.APPLIED;
                }
                default -> throw new IllegalStateException();
            }
            report();
        }
        if (announceAfter) {
            try {
                commands.received(session, "received-" + (++requestSeq), offer.commandId(), offer.generation());
            } catch (GatewayPortException superseded) {
                // the data plane's answer arrives AFTER the operation ran: nothing to take back
            }
        }
        terminateRan(offer.commandId(), offer.generation(), offer.operation(), key, outcome, detail, started);
    }

    private void terminate(CommandId id, long generation, AgentOperation operation, String key, AgentOutcome outcome,
            String detail) {
        terminateRan(id, generation, operation, key, outcome, detail, null);
    }

    private synchronized Instant startOf(String key) {
        return ackedStart.get(key);
    }

    private void terminateRan(CommandId id, long generation, AgentOperation operation, String key, AgentOutcome outcome,
            String detail, Instant started) {
        String requestId = "ack-" + (++requestSeq);
        synchronized (this) {
            if (omitStartOnAcks) {
                started = null;
            }
            if (started != null) {
                ackedStart.put(key, started);
            }
            journal.put(key, new Journal(id, generation, Phase.TERMINAL, outcome, detail, requestId, operation));
        }
        tryAck(id, generation, operation, outcome, detail, requestId, started);
    }

    private void tryAck(CommandId id, long generation, AgentOperation operation, AgentOutcome outcome, String detail,
            String requestId, Instant started) {
        synchronized (this) {
            if (dropAcksOf.contains(operation)) {
                return;                         // lost on the wire; the journal remembers, and re-acks when asked again
            }
        }
        try {
            commands.ack(session, id, new AgentAck(requestId, generation, outcome, detail, "", started));
        } catch (GatewayPortException refused) {
            // SUPERSEDED / UNKNOWN: the data plane has no use for it; the journal record is closed either way
        }
    }
}
