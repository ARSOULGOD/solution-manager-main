package com.solace.sm.dp.api.agw;

/**
 * Correlates one agent operation with the durable DP apply journal. {@code attemptId} is correlation only — the
 * {@code intentKey} never contains the attempt. {@code intentKey} is DP-side: the wire's correlation message does
 * not carry it, so a value mapped from the wire has {@code null} there.
 */
public record OperationCorrelation(String applyId, String intentId, String intentKey, String attemptId, String step) {
    public OperationCorrelation {
        if (applyId == null || applyId.isBlank() || intentId == null || intentId.isBlank()
                || attemptId == null || attemptId.isBlank() || step == null || step.isBlank()) {
            throw new IllegalArgumentException("operation correlation is incomplete");
        }
    }
}
