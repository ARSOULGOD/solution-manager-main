package com.solace.sm.dp.apply.runtime.archfixture;

import com.solace.sm.dp.apply.engine.ApplyOutcome;

/**
 * A planted violation: a runtime-seam type reaching back into the engine.
 *
 * <p>The seam points ONE way. {@code BE-D12} §2.1 — "the core apply engine speaks only these;
 * everything runtime-specific lives behind the seam" — is a claim about the direction of the arrow,
 * and an adapter that could reach the engine or the journal would be able to decide its own
 * outcome. Holding an {@link ApplyOutcome} is the shortest way to say that out loud.
 *
 * <p>Excluded from the real {@code @ArchTest} run by {@code ExcludeArchFixtures}.
 */
public final class BadRuntimeSeamEngineClass {

    private ApplyOutcome outcome;

    public ApplyOutcome outcome() {
        return outcome;
    }
}
