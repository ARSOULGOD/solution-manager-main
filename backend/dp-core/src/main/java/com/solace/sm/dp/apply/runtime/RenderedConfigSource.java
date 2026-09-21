package com.solace.sm.dp.apply.runtime;

/**
 * Where the engine gets generation {@code G}'s rendered bytes.
 *
 * <p>A seam rather than a parameter because <b>resume</b> needs it: an intent with no attempt row
 * provably issued nothing (§2.2 rule 0) and must be re-run deterministically, which means
 * re-rendering after a process restart that kept nothing in memory.
 *
 * <p>An implementation may throw anything. The engine never reads the message: it classifies the
 * failure itself as {@code render.failed}, because a file-backed renderer's message carries the path
 * it could not read and a template renderer's could carry the value it choked on ({@code I6}).
 */
@FunctionalInterface
public interface RenderedConfigSource {

    RenderedConfig render(PlacementRef placement, long generation);
}
