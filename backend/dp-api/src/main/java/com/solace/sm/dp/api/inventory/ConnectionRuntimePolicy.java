package com.solace.sm.dp.api.inventory;

/** Temporary switch for the connection-runtime projection gate during instance creation. */
public final class ConnectionRuntimePolicy {

    private ConnectionRuntimePolicy() {
    }

    /** Re-enable after runtime-specific connection projection is required for every deployment path. */
    public static final boolean REQUIRE_LIVE_PROJECTION = false;
}
