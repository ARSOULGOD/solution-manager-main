package com.solace.sm.dp.fleet;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code sm_data.fleet_action} row ({@code fleet-apply.md} section 4.3): the owning row of a fleet.
 *
 * @param state        {@code RUNNING | SUCCEEDED | COMPLETED_WITH_FAILURES | HALTED}
 * @param currentBatch the batch being waited on; moves forward only
 * @param terminalAt   null exactly while {@code RUNNING}
 * @param stateVersion the stamp the database assigned ({@code OR-35}); ignored on insert - nothing in Java sets it
 */
public record FleetRecord(UUID tenantId, UUID fleetId, UUID catalogSolutionId, String strategy,
        String outageConfirmedBy, int batchSize, String onFailure, String state, int currentBatch, String requestedBy,
        Instant requestedAt, UUID correlationId, Instant terminalAt, long stateVersion) {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String COMPLETED_WITH_FAILURES = "COMPLETED_WITH_FAILURES";
    public static final String HALTED = "HALTED";

    public static final String CONTINUE = "continue";
    public static final String HALT_REMAINING = "halt-remaining";

    public boolean running() {
        return RUNNING.equals(state);
    }
}
