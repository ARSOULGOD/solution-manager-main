package com.solace.sm.dp.apply.runtime;

/** Whether the replica is there at all, and running. */
public enum Liveness {
    RUNNING,
    STOPPED,
    ABSENT
}
