package com.solace.sm.agw.archfixture;

import com.solace.sm.dp.api.DpMarker;
import io.grpc.Status;

/** The legal edges: dp-api and gRPC. The rules must not name this class, or they reject everything. */
public final class LegalAgwUsesDpApiAndGrpc {
    public Object legal() {
        return new Object[] {DpMarker.class, Status.OK};
    }
}
