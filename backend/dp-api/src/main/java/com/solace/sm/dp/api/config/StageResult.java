package com.solace.sm.dp.api.config;

/**
 * @param generation   the generation the content now is: the new one, or (when {@code unchanged}) the latest
 * @param unchanged    OR-33 guard 1 — the content equalled the latest generation, so nothing was written
 * @param stateVersion the instance {@code stateVersion} after this call: the base for the operator's next stage
 */
public record StageResult(GenerationView generation, boolean unchanged, long stateVersion) {
}
