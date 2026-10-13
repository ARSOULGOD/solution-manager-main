package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.PreBaseline;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The one {@link RuntimeAdapter} the apply engine holds (plan 015 T3.1; {@code BE-D66}, global constraint 5): it sends each
 * operation to the adapter of the transport the placement's target uses. {@code dp-remote} placements reach SSH-Docker, agent
 * placements reach the agent adapter, in the same process, with one {@code ApplyExecutor}.
 *
 * <p><b>Nothing is defaulted.</b> A placement whose transport is unknown to this router, or that does not exist, is refused
 * with a fixed classifier and neither adapter is called: routing an unknown transport to the nearest one would try SSH to an
 * agent target, or hand a dp-remote placement to a queue nobody drains.
 *
 * <p>Every operation (including the context-carrying overloads and {@link #readConfig}) is routed by the placement it
 * names; this class holds no state and decides nothing else.
 */
public final class RoutingRuntimeAdapter implements RuntimeAdapter, WriteBaselinePolicy {

    /** The placement does not exist for this tenant and instance, or no transport is recorded for its target. */
    static final ErrorClass UNRESOLVABLE = new ErrorClass("placement.unresolvable");
    /** The transport is one this router has no adapter for. */
    static final ErrorClass UNKNOWN_TRANSPORT = new ErrorClass("placement.transport.unknown");

    private final PlacementTransports transports;
    private final RuntimeAdapter sshDocker;
    private final RuntimeAdapter agent;

    public RoutingRuntimeAdapter(PlacementTransports transports, RuntimeAdapter sshDocker, RuntimeAdapter agent) {
        this.transports = Objects.requireNonNull(transports, "transports");
        this.sshDocker = Objects.requireNonNull(sshDocker, "ssh-docker adapter");
        this.agent = Objects.requireNonNull(agent, "agent adapter");
    }

    private RuntimeAdapter route(PlacementRef placement) {
        Objects.requireNonNull(placement, "placement");
        String transport = transports.transportOf(placement).orElseThrow(() -> new RuntimeAdapterException(UNRESOLVABLE));
        if (PlacementTransports.SSH_DOCKER.equals(transport)) {
            return sshDocker;
        }
        if (PlacementTransports.AGENT.equals(transport)) {
            return agent;
        }
        throw new RuntimeAdapterException(UNKNOWN_TRANSPORT);
    }

    /** The adapter this router sends {@code transport} to, or empty for a transport it has none for (what a boot test inspects). */
    public java.util.Optional<RuntimeAdapter> delegateFor(String transport) {
        if (PlacementTransports.SSH_DOCKER.equals(transport)) {
            return java.util.Optional.of(sshDocker);
        }
        return PlacementTransports.AGENT.equals(transport) ? java.util.Optional.of(agent) : java.util.Optional.empty();
    }

    @Override
    public List<TargetReplica> replicas(PlacementRef placement) {
        return route(placement).replicas(placement);
    }

    @Override
    public PreflightResult prepare(PlacementRef placement, long generation) {
        return route(placement).prepare(placement, generation);
    }

    @Override
    public PreflightResult prepare(PlacementRef placement, long generation, RuntimeOperationContext context) {
        return route(placement).prepare(placement, generation, context);
    }

    @Override
    public MaterializeResult materializeConfig(TargetReplica replica, long generation, RenderedConfig config) {
        return route(replica.placement()).materializeConfig(replica, generation, config);
    }

    @Override
    public MaterializeResult materializeConfig(TargetReplica replica, long generation, RenderedConfig config,
            RuntimeOperationContext context) {
        return route(replica.placement()).materializeConfig(replica, generation, config, context);
    }

    @Override
    public MaterializeResult rematerializeIfCurrent(TargetReplica replica, long generation, RenderedConfig config,
            String expectedCurrentHash) {
        return route(replica.placement()).rematerializeIfCurrent(replica, generation, config, expectedCurrentHash);
    }

    @Override
    public MaterializeResult rematerializeIfCurrent(TargetReplica replica, long generation, RenderedConfig config,
            String expectedCurrentHash, RuntimeOperationContext context) {
        return route(replica.placement()).rematerializeIfCurrent(replica, generation, config, expectedCurrentHash,
                context);
    }

    @Override
    public LifecycleResult startOrUpdate(TargetReplica replica, long generation) {
        return route(replica.placement()).startOrUpdate(replica, generation);
    }

    @Override
    public LifecycleResult startOrUpdate(TargetReplica replica, long generation, RuntimeOperationContext context) {
        return route(replica.placement()).startOrUpdate(replica, generation, context);
    }

    @Override
    public LifecycleResult stop(TargetReplica replica) {
        return route(replica.placement()).stop(replica);
    }

    @Override
    public LifecycleResult stop(TargetReplica replica, RuntimeOperationContext context) {
        return route(replica.placement()).stop(replica, context);
    }

    @Override
    public RuntimeStatus status(TargetReplica replica) {
        return route(replica.placement()).status(replica);
    }

    @Override
    public PreBaseline writeBaseline(PlacementRef placement, UUID intentId, RuntimeStatus afterWrite) {
        // one more transport lookup per write than before (the router resolves again); the recorded value is unchanged for dp-remote
        return WriteBaselinePolicy.of(route(placement), placement, intentId, afterWrite);
    }

    @Override
    public RollbackAssessment assessRollback(PlacementRef placement, long generation, String intendedHash) {
        return route(placement).assessRollback(placement, generation, intendedHash);
    }

    @Override
    public RestoreResult restore(PlacementRef placement, long generation, String targetHash) {
        return route(placement).restore(placement, generation, targetHash);
    }

    @Override
    public ConfigReadBack readConfig(TargetReplica replica) {
        return route(replica.placement()).readConfig(replica);
    }

    @Override
    public String toString() {
        return "RoutingRuntimeAdapter";
    }
}
