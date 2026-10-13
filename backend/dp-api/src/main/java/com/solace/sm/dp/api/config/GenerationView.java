package com.solace.sm.dp.api.config;

import java.time.Instant;
import java.util.UUID;

/**
 * A generation as history shows it: identity, provenance and the KEYED digest of its content (OR-32) — and no
 * value, ever (I6). The values are re-projected from the stored draft by the Designer; history never returns them.
 */
public record GenerationView(long generation, String packageSolution, String packageVersion, String contentDigest,
        GenerationOrigin origin, Long derivedFromGeneration, UUID createdBy, Instant createdAt, long stateVersion) {
}
