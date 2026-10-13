package com.solace.sm.dp.config;

import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Instant;
import java.util.UUID;

/**
 * One {@code config_generation} row, in full. The three JSON columns are canonical JSON text; they hold references
 * for secrets and never a resolved value (I6). {@code stateVersion} is stamped by the database on insert, so the
 * value a caller passes for a new row is ignored.
 */
public record GenerationRecord(TenantId tenant, InstanceId instance, long generation, String packageSolution,
        String packageVersion, String componentSelectionJson, String draftValuesJson, String renderedEntriesJson,
        String contentDigest, GenerationOrigin origin, Long derivedFromGeneration, UUID createdBy,
        Instant createdAt, long stateVersion) {

    /** Names the identity and the digest, never the JSON, which is configuration. */
    @Override
    public String toString() {
        return "GenerationRecord[" + generation + ", " + packageSolution + "@" + packageVersion + ", " + origin + "]";
    }
}
