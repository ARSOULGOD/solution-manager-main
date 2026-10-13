package com.solace.sm.gateway;

/**
 * Package-root marker for {@code console-gateway} — the composition layer that serves the SPA,
 * terminates REST and (after gates G1/G2) proxies {@code /mesh}; it may reach the planes only
 * through {@code cp-api} and {@code dp-api} (backend design §2, {@code BE-D3}; {@code I7}).
 */
public final class GatewayMarker {

    private GatewayMarker() {
    }
}
