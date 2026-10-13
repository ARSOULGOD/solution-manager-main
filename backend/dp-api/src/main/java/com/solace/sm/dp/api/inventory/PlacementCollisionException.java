package com.solace.sm.dp.api.inventory;

/**
 * A second live placement on one target tried to reuse something an apply writes, a stop addresses or a probe reads
 * (OR-29). Names the collision CLASS only: never the other instance, never the value (Review Focus 2).
 */
public final class PlacementCollisionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind { CONTAINER_NAME, CONFIG_DIRECTORY, HEALTH_PORT }

    private final Kind kind;

    public PlacementCollisionException(Kind kind) {
        super("a live placement on this target already uses that " + kind.name().toLowerCase().replace('_', ' '));
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
