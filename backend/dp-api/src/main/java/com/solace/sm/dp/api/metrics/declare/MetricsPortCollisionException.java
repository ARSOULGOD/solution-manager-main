package com.solace.sm.dp.api.metrics.declare;

/**
 * A declare tried to claim a host-side port another live placement on the same target already uses (plan 011 M-Q14,
 * Review Focus 1), or one placement mapped two source ports to one host port. Names the collision CLASS only: never
 * the other instance, never the port (the same rule as {@code PlacementCollisionException}).
 */
public final class MetricsPortCollisionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** Another live placement on the target maps this host port as a metrics port. */
        METRICS_HOST_PORT_IN_USE,
        /** Another live placement on the target probes its health on this host port. */
        METRICS_HOST_PORT_IS_ANOTHER_PLACEMENTS_HEALTH_PORT,
        /** This placement's health port is another live placement's metrics host port. */
        HEALTH_PORT_IS_ANOTHER_PLACEMENTS_METRICS_PORT,
        /** One declare mapped two source ports to the same host port. */
        TWO_SOURCE_PORTS_ONE_HOST_PORT
    }

    private final Kind kind;

    public MetricsPortCollisionException(Kind kind) {
        super("a host port named by this declare is already in use on the target: "
                + kind.name().toLowerCase().replace('_', ' '));
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
