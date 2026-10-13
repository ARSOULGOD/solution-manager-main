package com.solace.sm.launcher.archfixture;

import com.google.protobuf.Timestamp;

/** Planted violation (T1.2 review finding 1): a module other than the gateway touching protobuf (rule {@code onlyAgentGatewayTouchesGrpc}). */
public final class PlantedLauncherTouchesProtobuf {
    public Timestamp forbidden() {
        return Timestamp.getDefaultInstance();
    }
}
