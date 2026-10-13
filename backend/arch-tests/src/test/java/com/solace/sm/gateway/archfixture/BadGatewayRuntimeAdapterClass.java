package com.solace.sm.gateway.archfixture;

import com.solace.sm.dp.apply.runtime.RuntimeAdapter;

/**
 * Planted violation: an HTTP-layer class holding the mutating seam. Proves
 * {@code ApplyPathBoundaryTest.onlyProductionCodeTouchesTheMutatingRuntimeAdapter} is not scoped to one package.
 */
public final class BadGatewayRuntimeAdapterClass {

    private RuntimeAdapter adapter;

    public RuntimeAdapter adapter() {
        return adapter;
    }
}
