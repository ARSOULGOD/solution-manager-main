package com.solace.sm.dp.api.agw;

public enum AgentOperation {
    PREFLIGHT,
    APPLY,
    RESTART,
    STOP,
    /** Plan 015 T2.3: write only if the host file still has {@code expected_current_sha256}. */
    REMATERIALIZE
}
