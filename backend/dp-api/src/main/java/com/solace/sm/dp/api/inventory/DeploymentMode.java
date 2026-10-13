package com.solace.sm.dp.api.inventory;

import java.util.Locale;

/**
 * How an instance is deployed (plan 014): chosen per instance at declare and stored on {@code sm_data.instance_state}
 * ({@code V039}), like the acquisition mode. {@code DP_REMOTE} is the data plane acting on the host over a remote-server
 * Connection (or, for instances declared before plan 014, a Target). {@code HOST_LOCAL} and {@code FEDERATED} are
 * reserved for the agent contracts and are refused at declare until those land.
 */
public enum DeploymentMode {
    DP_REMOTE, HOST_LOCAL, FEDERATED;

    /** The closed wire vocabulary ({@code dp-remote}, {@code host-local}, {@code federated}) the CHECK and the contract share. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static DeploymentMode fromWire(String wire) {
        for (DeploymentMode m : values()) {
            if (m.wire().equals(wire)) {
                return m;
            }
        }
        throw new IllegalArgumentException("unknown deployment mode");
    }
}
