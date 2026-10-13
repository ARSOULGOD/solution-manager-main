package com.solace.sm.dp.api.agw;

public enum AgentOutcome {
    APPLIED,
    FAILED_NO_EFFECT,
    FAILED_ROLLED_BACK,
    SUPERSEDED,
    REFUSED,
    APPLY_INDETERMINATE,
    PREFLIGHT_OK,
    PREFLIGHT_REFUSED
}
