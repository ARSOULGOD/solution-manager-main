package com.solace.sm.launcher.archfixture;

import io.grpc.Status;

/** Planted violation: a module other than the gateway touching gRPC (rule {@code onlyAgentGatewayTouchesGrpc}). */
public final class PlantedLauncherTouchesGrpc {
    public Status forbidden() {
        return Status.OK;
    }
}
