package com.solace.sm.dp.api.inventory;

/**
 * What {@link TargetConnectionManagement#retireConnection} did (plan 013 T1.5; D-REG section 5.1 T7). The gateway retires
 * the control-plane row after this answers, whichever it is: all three leave the connection record unusable or absent.
 */
public enum ConnectionRetirement {
    /** This call retired the live connection record (and, by V012's trigger, freed its host keys). */
    RETIRED,
    /** The record was retired already; nothing was written. */
    ALREADY_RETIRED,
    /** The data plane holds no connection record for the target (a registration that never reached it): nothing to retire. */
    NO_CONNECTION
}
