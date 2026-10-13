package com.solace.sm.dp.api.inventory;

/**
 * What undeclare does to the workload on the host. One value in v1 ({@code ops-501.md} section 4.2, U-C): SM releases
 * the instance and never touches the container, the config file or SM's hidden files beside it. A closed enum so a
 * second disposition is a decision, not a string.
 */
public enum WorkloadDisposition {
    LEAVE_RUNNING("leave-running");

    private final String wire;

    WorkloadDisposition(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static WorkloadDisposition fromWire(String wire) {
        for (WorkloadDisposition d : values()) {
            if (d.wire.equals(wire)) {
                return d;
            }
        }
        throw new IllegalArgumentException("unknown workload disposition");
    }
}
