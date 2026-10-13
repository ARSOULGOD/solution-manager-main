package com.solace.sm.dp.apply.runtime.agent;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.runtime.ConfigReadBack;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeOperationContext;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Gives the runtime-neutral kit, which calls the context-less operations, the journal identity the agent path insists on. It plays
 * the apply engine's part: one apply per generation, so the intent key ({@code applyId:step:replica}) is the same for the same
 * step of the same generation and different for any other, and a fresh attempt id on every call (correlation only).
 */
public final class ContextInjectingAdapter implements RuntimeAdapter {

    private final RuntimeAdapter delegate;

    public ContextInjectingAdapter(RuntimeAdapter delegate) {
        this.delegate = delegate;
    }

    private static ApplyId applyOf(long generation) {
        return new ApplyId(UUID.nameUUIDFromBytes(("kit-apply-" + generation).getBytes(StandardCharsets.UTF_8)));
    }

    private static RuntimeOperationContext context(long generation, Step step, ReplicaRef replica) {
        ApplyId apply = applyOf(generation);
        String key = ApplyExecutor.idempotencyKey(apply, step, replica);
        return new RuntimeOperationContext(apply, UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), key,
                UUID.randomUUID(), step);
    }

    @Override
    public List<TargetReplica> replicas(PlacementRef placement) {
        return delegate.replicas(placement);
    }

    @Override
    public PreflightResult prepare(PlacementRef placement, long generation) {
        return delegate.prepare(placement, generation, context(generation, Step.PREFLIGHT, ReplicaRef.PLACEMENT));
    }

    @Override
    public MaterializeResult materializeConfig(TargetReplica replica, long generation, RenderedConfig config) {
        return delegate.materializeConfig(replica, generation, config, context(generation, Step.MATERIALIZE, replica.ref()));
    }

    @Override
    public MaterializeResult rematerializeIfCurrent(TargetReplica replica, long generation, RenderedConfig config,
            String expectedCurrentHash) {
        return delegate.rematerializeIfCurrent(replica, generation, config, expectedCurrentHash,
                context(generation, Step.MATERIALIZE, replica.ref()));
    }

    @Override
    public LifecycleResult startOrUpdate(TargetReplica replica, long generation) {
        return delegate.startOrUpdate(replica, generation, context(generation, Step.LIFECYCLE, replica.ref()));
    }

    @Override
    public LifecycleResult stop(TargetReplica replica) {
        return delegate.stop(replica, context(0, Step.STOP, replica.ref()));
    }

    @Override
    public RuntimeStatus status(TargetReplica replica) {
        return delegate.status(replica);
    }

    @Override
    public RollbackAssessment assessRollback(PlacementRef placement, long generation, String intendedHash) {
        return delegate.assessRollback(placement, generation, intendedHash);
    }

    @Override
    public RestoreResult restore(PlacementRef placement, long generation, String targetHash) {
        return delegate.restore(placement, generation, targetHash);
    }

    @Override
    public ConfigReadBack readConfig(TargetReplica replica) {
        return delegate.readConfig(replica);
    }
}
