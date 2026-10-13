package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.RuntimeRef;

/**
 * What a destructive restore touched ({@code BE-D22}).
 *
 * <p>Carries no per-replica outcome, for the same reason {@link RollbackAssessment} carries no
 * observations: the engine issues one {@code VERIFY} step per replica afterwards, and those attempt
 * rows are the record.
 */
public record RestoreResult(RuntimeRef runtimeRef) {
}
