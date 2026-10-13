package com.solace.sm.dp.apply.runtime.archfixture;

import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;

/**
 * A planted {@link RuntimeAdapter} implementation that is NOT in the certified inventory and has
 * no {@code AbstractRuntimeAdapterContractTest} subclass.
 *
 * <p>It exists so {@code everyRuntimeAdapterImplementationIsInTheCertifiedInventory} can be shown
 * to actually SEE new implementations. Without it the inventory check is a comparison of a
 * hard-coded set against a scan nobody has proved is looking in the right place — and the set could
 * be grown invisibly.
 *
 * <p>Excluded from the real {@code @ArchTest} run by {@code ExcludeArchFixtures}.
 */
public final class BadUnkittedAdapterClass implements RuntimeAdapter {

    @Override
    public java.util.List<TargetReplica> replicas(PlacementRef placement) {
        throw new UnsupportedOperationException("a fixture, not an adapter");
    }

    @Override
    public PreflightResult prepare(PlacementRef placement, long generation) {
        throw new UnsupportedOperationException("a fixture, not an adapter");
    }

    @Override
    public MaterializeResult materializeConfig(TargetReplica replica, long generation,
            RenderedConfig config) {
        throw new UnsupportedOperationException("a fixture, not an adapter");
    }

    @Override
    public MaterializeResult rematerializeIfCurrent(TargetReplica replica, long generation, RenderedConfig config,
            String expectedCurrentHash) {
        throw new UnsupportedOperationException("not used by this test double");
    }

    @Override
    public LifecycleResult startOrUpdate(TargetReplica replica, long generation) {
        throw new UnsupportedOperationException("a fixture, not an adapter");
    }

    @Override
    public LifecycleResult stop(TargetReplica replica) {
        throw new UnsupportedOperationException("a fixture, not an adapter");
    }

    @Override
    public RuntimeStatus status(TargetReplica replica) {
        throw new UnsupportedOperationException("a fixture, not an adapter");
    }

    @Override
    public RollbackAssessment assessRollback(PlacementRef placement, long generation,
            String intendedHash) {
        throw new UnsupportedOperationException("a fixture, not an adapter");
    }

    @Override
    public RestoreResult restore(PlacementRef placement, long generation, String targetHash) {
        throw new UnsupportedOperationException("a fixture, not an adapter");
    }
}
