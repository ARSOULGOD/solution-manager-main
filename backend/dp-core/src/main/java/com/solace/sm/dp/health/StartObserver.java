package com.solace.sm.dp.health;

import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.HostMetricExec;
import com.solace.sm.dp.apply.runtime.ssh.HostMetricProbe;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacementCatalog;
import com.solace.sm.dp.config.ConfigLadder;
import com.solace.sm.dp.config.LadderEvidence;
import com.solace.sm.dp.config.LadderEvidence.PlacementEvidence;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.config.LadderRows;
import com.solace.sm.dp.inventory.PlacementRecord;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Observes the container start of the placements that are waiting for one (OR-112 (1)): the second route to the config
 * ladder's {@code restart-observed} rung, for an apply whose strategy runs no health gate. Called by the
 * {@link HealthPoller}, once per round, for each placement it visits; it takes no tenant list of its own (OR-49).
 *
 * <p><b>Who is looked at.</b> {@link #awaiting} asks the ladder ({@link ConfigLadder#awaitsStart}): a placement whose
 * newest generation is {@code persisted} under an ungated strategy and has seen no start since the write. Every other
 * placement costs one journal read per round and no host call, so a fleet that is not mid-apply adds no SSH load.
 *
 * <p><b>What is read.</b> {@code docker inspect}'s {@code Id} and {@code State.StartedAt} over the same pooled SSH
 * session the health probe uses, through the same fixed command the collector uses
 * ({@link HostMetricProbe#inspect}): no shell string from data, a bound on the host. The binding is looked up fresh. It
 * reads any placement the data plane applies to, whatever its instance's metrics acquisition mode: this is the runtime's
 * own state, not a metric, so M-Q2's "no fallback between modes" does not apply to it.
 *
 * <p><b>What is recorded.</b> Only a CHANGED start ({@link StartObservationRows#record}), and then in one transaction: the
 * instance's state row is locked (as a landing does), the observation written, and the ladder settled
 * ({@link LadderRecorder}), so {@code reached_rung} and {@code instance.config-state} move together and only once: the
 * landing and this path share the one writer, and the writer fires only on a movement. Nothing is recorded for a read
 * that failed, was refused, or was not a start.
 *
 * <p>Failure is silence, by design: a busy pool, a lost session, an unreadable line are all "not observed this round", and
 * the next round asks again. Only a classifier is logged (a host's answer can carry anything).
 */
public final class StartObserver {

    private static final Logger log = LoggerFactory.getLogger(StartObserver.class);

    private final HostMetricExec exec;
    private final SshDockerPlacementCatalog catalog;
    private final LadderRows ladderRows;
    private final StartObservationRows observations;
    private final TransactionTemplate transactions;
    private final LadderRecorder ladder;
    private final ApplyEventEmitter events;
    private final Clock clock;
    private final Duration timeout;

    public StartObserver(HostMetricExec exec, SshDockerPlacementCatalog catalog, LadderRows ladderRows,
            StartObservationRows observations, TransactionTemplate transactions, LadderRecorder ladder,
            ApplyEventEmitter events, Clock clock, Duration timeout) {
        this.exec = Objects.requireNonNull(exec, "exec");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.ladderRows = Objects.requireNonNull(ladderRows, "ladderRows");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ladder = Objects.requireNonNull(ladder, "ladder");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** The instance's live placements that are still waiting for a container start (see the class doc). */
    public Set<UUID> awaiting(TenantId tenant, InstanceId instance) {
        LadderEvidence evidence = ladderRows.evidence(tenant, instance);
        Set<UUID> waiting = new HashSet<>();
        if (evidence.lastGeneration() == 0) {
            return waiting;
        }
        for (PlacementEvidence placement : evidence.livePlacements()) {
            if (ConfigLadder.awaitsStart(placement, evidence.lastGeneration())) {
                waiting.add(placement.placementId());
            }
        }
        return waiting;
    }

    /**
     * Reads the placement's container start and, if it is not the one already recorded, records it and settles the ladder.
     *
     * @return whether a new start was recorded (not whether the rung moved)
     */
    public boolean observe(PlacementRecord placement) {
        Optional<HostMetricProbe.ContainerStart> start = read(placement);
        if (start.isEmpty()) {
            return false;
        }
        TenantId tenant = new TenantId(placement.tenantId());
        InstanceId instance = new InstanceId(placement.instanceId());
        Boolean recorded = transactions.execute(status -> {
            observations.lockInstance(tenant.value(), instance.value());
            if (!observations.record(tenant.value(), placement.placementId(), start.get().startedAt(),
                    start.get().containerId(), clock.instant())) {
                return false;
            }
            ladder.settle(tenant, instance).ifPresent(move ->
                    ApplyEventEmitter.bestEffort("config-state", () -> events.onConfigStateReached(tenant, instance,
                            move.previous(), move.reached(), move.stateVersion())));
            // W4 lane D: the active list follows the newest restart-observed generation, so a start seen on this (dp-remote)
            // placement of a mixed-transport instance changes what its agent placements declare
            events.declarationsChanged(tenant, instance);
            return true;
        });
        return Boolean.TRUE.equals(recorded);
    }

    private Optional<HostMetricProbe.ContainerStart> read(PlacementRecord placement) {
        try {
            SshDockerPlacement binding = catalog.lookup(new PlacementRef(new TenantId(placement.tenantId()),
                    new InstanceId(placement.instanceId()), new PlacementId(placement.placementId())));
            HostMetricExec.InspectResult result = exec.inspect(binding,
                    HostMetricProbe.inspect(binding.containerName(), timeout));
            if (result.exitCode() != 0) {
                return Optional.empty();   // no such container, a slow host (124/137), or docker itself failed
            }
            return HostMetricProbe.parseInspect(result.stdout());
        } catch (RuntimeAdapterException e) {
            // a busy pool, a lost session, a row that moved: not observed this round
            log.debug("start observation: a placement could not be read ({})", e.errorClass().token());
            return Optional.empty();
        }
    }
}
